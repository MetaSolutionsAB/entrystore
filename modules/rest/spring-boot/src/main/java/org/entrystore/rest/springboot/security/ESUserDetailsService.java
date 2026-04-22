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

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.entrystore.Entry;
import org.entrystore.GraphType;
import org.entrystore.PrincipalManager;
import org.entrystore.User;
import org.entrystore.rest.springboot.model.auth.SessionInfo;
import org.entrystore.rest.springboot.model.auth.UserAuthRole;
import org.entrystore.rest.springboot.service.UserService;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.net.URI;

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

	@Override
	public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {

		final URI currentUser = pm.getAuthenticatedUserURI();
		try {
			pm.setAuthenticatedUserURI(pm.getAdminUser().getURI());
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
					return mapESUserToUserSessionDetails(user, sessionInfo.build());
				} else {
					log.error("No secret found for user: '{}'", username);
				}
			} else {
				log.info("User Entry not found for username: '{}'", username);
			}
		} finally {
			pm.setAuthenticatedUserURI(currentUser);
		}

		throw new UsernameNotFoundException("User not found " + username);
	}

	/**
	 * Loads Entrystore User by username using PrincipalManager
	 *
	 * @param username User entity name to be loaded
	 * @return Entrystore User or null if not found
	 */
	public User loadUser(String username) {

		final URI currentUser = pm.getAuthenticatedUserURI();
		try {
			pm.setAuthenticatedUserURI(pm.getAdminUser().getURI());
			Entry userEntry = pm.getPrincipalEntry(username);
			if (userEntry != null && GraphType.User.equals(userEntry.getGraphType())) {
				return ((User) userEntry.getResource());
			} else {
				log.info("User Entry not found for username: '{}'", username);
			}
		} finally {
			pm.setAuthenticatedUserURI(currentUser);
		}

		return null;
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
		final URI currentUser = pm.getAuthenticatedUserURI();
		Entry entry = null;
		try {
			pm.setAuthenticatedUserURI(pm.getAdminUser().getURI());

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
		} catch (RuntimeException e) {
			// Any failure after createResource succeeded leaves an orphaned nameless User entry.
			// Remove it unless it was already removed (name-collision branch above).
			if (entry != null) {
				removeOrphanedEntry(entry, username);
			}
			throw e;
		} finally {
			pm.setAuthenticatedUserURI(currentUser);
		}
	}

	private void removeOrphanedEntry(Entry entry, String username) {
		try {
			pm.remove(entry.getEntryURI());
		} catch (Exception cleanup) {
			log.error("Failed to remove orphaned user entry {} for '{}' during cleanup",
					entry.getEntryURI(), username, cleanup);
		}
	}

	private UserDetails mapESUserToUserSessionDetails(User user, SessionInfo sessionInfo) {

		UserDetails userDetails = org.springframework.security.core.userdetails.User
				.withUsername(user.getEntry().getResourceURI().toString())
				.password(user.getSaltedHashedSecret())
				.disabled(user.isDisabled())
				.roles(userService.isAdmin(user) ? UserAuthRole.ADMIN.name() : UserAuthRole.USER.name())
				.build();

		return new ESUserSessionDetails(userDetails, user, sessionInfo);
	}
}
