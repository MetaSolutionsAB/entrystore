/*
 * Copyright (c) 2007-2026 MetaSolutions AB
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.entrystore.rest.springboot.security;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.entrystore.Entry;
import org.entrystore.GraphType;
import org.entrystore.PrincipalManager;
import org.entrystore.User;
import org.entrystore.repository.RepositoryEvent;
import org.entrystore.repository.RepositoryEventObject;
import org.entrystore.repository.RepositoryListener;
import org.entrystore.repository.RepositoryManager;
import org.entrystore.rest.springboot.model.auth.SessionInfo;
import org.entrystore.rest.springboot.model.auth.UserAuthRole;
import org.entrystore.rest.springboot.service.UserService;
import org.entrystore.rest.springboot.util.PrincipalManagerUtil;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import java.net.URI;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

/**
 * ESUserDetailsService is a Spring Security {@link UserDetailsService} implementation
 * responsible for loading user-specific data during authentication. This class is
 * tightly coupled with the security mechanism of the application and provides
 * a way to retrieve and map user details from the application's data store.
 * <p>
 * The `loadUserByUsername` method will be called during form login to check if
 * a given logging-in user has correct credentials.
 * <p>
 * It performs the following primary functionalities:
 * - Fetches the user details from the data store (PrincipalManager) based on the username.
 * - Maps the retrieved user information to a Spring Security compatible {@link UserDetails} object.
 * <p>
 * Dependencies:
 * - {@link PrincipalManager}: Manages user and principal data.
 * - {@link UserService}: Provides utility methods for user-related operations including
 * admin role checks.
 * <p>
 * Exception Handling:
 * - Throws {@link UsernameNotFoundException} if the user is not found in the data store
 * or their credentials are invalid.
 *
 */
@Slf4j
@RequiredArgsConstructor
@Service
public class ESUserDetailsService implements UserDetailsService {

	private final PrincipalManager pm;
	private final UserService userService;
	private final RepositoryManager repositoryManager;

	/**
	 * B4 (ENTRYSTORE-1090): ReloadUserPropertiesFilter re-resolves the user on every
	 * authenticated request; this short-TTL cache amortises the RDF4J lookups. The cached value
	 * is the immutable identity payload only — a fresh {@link ESUserSessionDetails} wrapper is
	 * minted per call because callers mutate its session info. Any repository event on a User
	 * or Group entry (disable, password change, delete, group-membership change — they all fire
	 * events; Group events matter because {@code ROLE_ADMIN} derives from admin-group
	 * membership) clears the cache, so the TTL is a safety net, not the staleness bound.
	 */
	private record CachedUserDetails(UserDetails userDetails, User user) {
	}

	/**
	 * Caffeine bounds the cache (an unbounded map would pin a core {@link User} per principal
	 * that ever authenticated) and expires entries after the TTL without waiting for a same-key
	 * lookup. Built in {@link #init()} because the TTL is injected.
	 */
	private Cache<String, CachedUserDetails> userDetailsCache;

	/**
	 * Guards the load→put race the same way {@code PrincipalManagerImpl.getGroupUrisCached}
	 * does: every invalidation bumps the epoch, and a load only publishes its result if no
	 * invalidation happened while it read the repository — otherwise a disable or password
	 * change firing mid-load would be re-parked for up to the TTL.
	 */
	private final AtomicLong cacheEpoch = new AtomicLong();

	@Value("${entrystore.auth.userdetails-cache.ttl-seconds:5}")
	private long cacheTtlSeconds;

	@PostConstruct
	void init() {
		userDetailsCache = Caffeine.newBuilder()
				.expireAfterWrite(Duration.ofSeconds(Math.max(cacheTtlSeconds, 1)))
				.maximumSize(10_000)
				.build();
		RepositoryListener invalidator = new RepositoryListener() {
			@Override
			public void repositoryUpdated(RepositoryEventObject eventObject) {
				if (eventObject.getSource() instanceof Entry source
						&& (GraphType.User.equals(source.getGraphType())
								|| GraphType.Group.equals(source.getGraphType()))) {
					cacheEpoch.incrementAndGet();
					userDetailsCache.invalidateAll();
				}
			}
		};
		repositoryManager.registerListener(invalidator, RepositoryEvent.EntryUpdated);
		repositoryManager.registerListener(invalidator, RepositoryEvent.ResourceUpdated);
		// setChildren (the REST group-membership replace) fires RelationsUpdated on the member
		// User entries and ResourceUpdated on the Group — cover both so admin de-elevation
		// evicts immediately instead of riding out the TTL.
		repositoryManager.registerListener(invalidator, RepositoryEvent.RelationsUpdated);
		repositoryManager.registerListener(invalidator, RepositoryEvent.EntryDeleted);
	}

	/**
	 * Evicts a principal's cached identity snapshot. Called on logout so a subsequent login
	 * re-reads the repository even within the TTL window. Accepts either key form (principal
	 * name or resource URI) — the two forms are cached under separate keys.
	 */
	public void evictCachedUserDetails(String username) {
		if (username != null) {
			cacheEpoch.incrementAndGet();
			userDetailsCache.invalidate(username.toLowerCase());
		}
	}

	@Override
	public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
		String cacheKey = username.toLowerCase();
		if (cacheTtlSeconds > 0) {
			CachedUserDetails cached = userDetailsCache.getIfPresent(cacheKey);
			if (cached != null) {
				return new ESUserSessionDetails(cached.userDetails(), cached.user(),
						SessionInfo.builder().userName(cacheKey).build());
			}
		}
		log.debug("Loading user details from repository for '{}'", username);
		final long epochBeforeLoad = cacheEpoch.get();

		return PrincipalManagerUtil.runAsAdmin(pm, () -> {
			Entry userEntry;
			if (username.contains("/resource/")) {
				userEntry = pm.getPrincipalEntry(pm.getPrincipalName(URI.create(username)));
			} else {
				userEntry = pm.getPrincipalEntry(username);
			}
			if (userEntry != null && GraphType.User.equals(userEntry.getGraphType())) {
				User user = ((User) userEntry.getResource());
				if (user.getSaltedHashedSecret() != null) {
					SessionInfo.SessionInfoBuilder sessionInfo = SessionInfo.builder()
							.userName(username.toLowerCase());
					UserDetails identity = buildIdentity(user);
					if (cacheTtlSeconds > 0 && cacheEpoch.get() == epochBeforeLoad) {
						// Only publish if no invalidation raced the repository read — a stale
						// identity put after a concurrent disable/password change would
						// otherwise survive until the TTL.
						userDetailsCache.put(cacheKey, new CachedUserDetails(identity, user));
					}
					return new ESUserSessionDetails(identity, user, sessionInfo.build());
				} else {
					log.error("No secret found for user: '{}'", username);
				}
			} else {
				log.info("User Entry not found for username: '{}'", username);
			}
			throw new UsernameNotFoundException("User not found " + username);
		});
	}

	/**
	 * Loads Entrystore User by username using PrincipalManager
	 *
	 * @param username User entity name to be loaded
	 * @return Entrystore User or null if not found
	 */
	public User loadUser(String username) {

		return PrincipalManagerUtil.runAsAdmin(pm, () -> {
			Entry userEntry = pm.getPrincipalEntry(username);
			if (userEntry != null && GraphType.User.equals(userEntry.getGraphType())) {
				return ((User) userEntry.getResource());
			}
			log.info("User Entry not found for username: '{}'", username);
			return null;
		});
	}

	/**
	 * Creates a new EntryStore User with the given principal name.
	 * Used by SSO login handlers (CAS, SAML) for auto-provisioning.
	 *
	 * @param username principal name for the new user
	 * @return the created User
	 * @throws IllegalStateException if the entry cannot be created or the principal name is already in use
	 */
	public User createUser(String username) {
		return PrincipalManagerUtil.runAsAdmin(pm, () -> {
			Entry entry = null;
			try {
				entry = pm.createResource(null, GraphType.User, null, null);
				if (entry == null) {
					throw new IllegalStateException("createResource returned null when provisioning user '%s'".formatted(username));
				}
				boolean nameSet = pm.setPrincipalName(entry.getResourceURI(), username);
				if (!nameSet) {
					log.warn("Principal name '{}' already in use — removing orphaned entry to prevent account takeover", username);
					removeOrphanedEntry(entry, username);
					entry = null; // prevent double-removal in the catch block
					throw new IllegalStateException("Principal name '%s' already in use".formatted(username));
				}
				User u = (User) entry.getResource();
				log.info("Created user '{}'", u.getURI());
				return u;
			} catch (Throwable t) {
				// Any failure after createResource succeeded leaves an orphaned nameless User entry.
				// Remove it unless it was already removed (name-collision branch above).
				if (entry != null) {
					removeOrphanedEntry(entry, username);
				}
				throw t;
			}
		});
	}

	private void removeOrphanedEntry(Entry entry, String username) {
		try {
			pm.remove(entry.getEntryURI());
		} catch (Exception cleanup) {
			log.error("Failed to remove orphaned user entry {} for '{}' during cleanup",
					entry.getEntryURI(), username, cleanup);
		}
	}

	/**
	 * Builds the immutable Spring Security identity snapshot for a user. This is the value the
	 * TTL cache stores; the mutable {@link ESUserSessionDetails} wrapper is minted per call.
	 */
	private UserDetails buildIdentity(User user) {
		return org.springframework.security.core.userdetails.User
				.withUsername(user.getEntry().getResourceURI().toString())
				.password(user.getSaltedHashedSecret())
				.disabled(user.isDisabled())
				.roles(userService.isAdmin(user) ? UserAuthRole.ADMIN.name() : UserAuthRole.USER.name())
				.build();
	}
}
