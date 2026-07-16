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

package org.entrystore.impl;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.rdf4j.model.Resource;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.RepositoryException;
import org.eclipse.rdf4j.repository.RepositoryResult;
import org.entrystore.AuthorizationException;
import org.entrystore.Entry;
import org.entrystore.EntryType;
import org.entrystore.GraphType;
import org.entrystore.Group;
import org.entrystore.PrincipalManager;
import org.entrystore.User;
import org.entrystore.repository.config.Settings;
import org.entrystore.repository.security.Password;
import org.entrystore.repository.util.URISplit;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;


/**
 * Creates a
 * @author Olov Wikberg, IML Umeå University
 * @author matthias
 * @author Hannes Ebner
 *
 */
@Slf4j
public class PrincipalManagerImpl extends EntryNamesContext implements PrincipalManager {

	private static final ThreadLocal<URI> authenticatedUserURI = new ThreadLocal<>();
	@Getter
	public User adminUser = null;
	@Getter
	public Group adminGroup = null;
	@Getter
	public User guestUser = null;
	@Getter
	public Group userGroup = null;

	private static final String ENV_ADMIN_PASSWORD = "ENTRYSTORE_ADMIN_PASSWORD";

	/**
	 * ENTRYSTORE-1085: cross-request user→groups cache. {@link #getGroupUris(URI)} scans the whole
	 * principals context per miss; this map amortises that across authorization decisions.
	 * Invalidation is listener-based (see RepositoryManagerImpl#registerGroupCacheInvalidationListener)
	 * plus a clear after every committed batch. Package-private for tests.
	 */
	final Map<URI, Set<URI>> userGroupsCache = new ConcurrentHashMap<>();

	/**
	 * Bumped on every invalidation. A loader records the epoch before scanning and only publishes
	 * its result while the epoch is unchanged (re-checking after the put), so a scan racing an
	 * invalidation can never park pre-mutation state in the cache indefinitely.
	 */
	private final AtomicLong userGroupsCacheEpoch = new AtomicLong();

	private volatile Boolean groupCacheEnabled;

	/**
	 * Creates a principal manager
	 * @param entry this principal managers entry
	 * @param uri this principal managers URI
	 * @param softCache
	 */
	public PrincipalManagerImpl(EntryImpl entry, String uri, SoftCache softCache) {
		super(entry, uri, softCache);
	}


	public String getPrincipalName(URI principal) {
		Entry principalEntry = null;
		User u = getUser(principal);
		if (u != null) {
			principalEntry = u.getEntry();
		} else {
			Group g = getGroup(principal);
			if (g != null) {
				principalEntry = g.getEntry();
			}
		}

		if (principalEntry == null) {
			throw new org.entrystore.repository.RepositoryException("Unable to resolve URI into principal: " + principal);
		}

		checkAuthenticatedUserAuthorized(principalEntry, AccessProperty.ReadMetadata);
		return getName(principalEntry.getEntryURI());
	}

	public Entry getPrincipalEntry(String name) {
		Entry principalEntry = getEntryByName(name.toLowerCase());
		if (principalEntry == null) {
			return null;
		} else if (principalEntry.getGraphType() == GraphType.User ||
						principalEntry.getGraphType() == GraphType.Group) {
			return principalEntry;
		}
		throw new org.entrystore.repository.RepositoryException("""
				Found entry for the name is not a principal...
				this is either a programming error or someone have been tampering with the RDF directly.""");
	}

	public boolean setPrincipalName(URI principal, String newName) {
		if (principal == null || newName == null) {
			throw new IllegalArgumentException("Parameters must not be null");
		}
		String noZeroLengthSpaceName = newName.replaceAll("[\\p{Cf}]", "");
		String trimmedName = org.apache.commons.lang3.StringUtils.trimToNull(noZeroLengthSpaceName);
		if (trimmedName == null) {
			throw new IllegalArgumentException("Principal name must not be null");
		}
		String normalizedName = trimmedName.toLowerCase();
		URISplit us = new URISplit(principal, this.entry.getRepositoryManager().getRepositoryURL());
		Entry principalEntry = getByEntryURI(us.getMetaMetadataURI());
		if (principalEntry == null) {
			throw new org.entrystore.repository.RepositoryException("Cannot find an entry for the specified URI");
		} else if (principalEntry.getGraphType() == GraphType.User || principalEntry.getGraphType() == GraphType.Group) {
			return setEntryName(us.getMetaMetadataURI(), normalizedName);
		}
		throw new org.entrystore.repository.RepositoryException("Given URI does not refer to a Principal.");
	}

    public boolean isUserAdminOrAdminGroup(URI principal) {
		URI currentUserURI = getAuthenticatedUserURI();
		if (principal == null) {
			principal = currentUserURI;
		}
		URI adminUserURI = getAdminUser().getURI();

		try {
			setAuthenticatedUserURI(adminUserURI);
			User user = getUser(principal);
			if (adminUserURI.equals(principal) || getAdminGroup().isMember(user)) {
				return true;
			}
		} finally {
			setAuthenticatedUserURI(currentUserURI);
		}

		return false;
    }


    /**
	 * Returns this principal managers all user URIs
	 * @return all user URIs in this principal manager
	 */
	public List<URI> getUsersAsUris() {
		Iterator <URI> entryIterator = getEntries().iterator();
		List<URI> userUris = new ArrayList<>();

		//sort out the users
		while(entryIterator.hasNext()) {
			URI nextURI = entryIterator.next();

			Entry nextEntry = getByEntryURI(nextURI);
			if(nextEntry.getGraphType() == GraphType.User) {
				userUris.add(nextEntry.getResourceURI());
			}
		}

		return userUris;
	}

	/**
	 * Returns this principal managers all user URIs
	 * @return all user URIs in this principal manager
	 */
	public List<User> getUsers() {
		Iterator<URI> entryIterator = getEntries().iterator();
		List<User> userUris = new ArrayList<>();

		//sort out the users
		while(entryIterator.hasNext()) {
			URI nextURI = entryIterator.next();

			Entry nextEntry = getByEntryURI(nextURI);
			if(nextEntry.getGraphType() == GraphType.User) {
				userUris.add((User) nextEntry.getResource());
			}
		}

		return userUris;
	}


	/**
	 * Returns a User object representing a user.
	 * @param userUri The URI to the user.
	 * @return the User object
	 */
	public User getUser(URI userUri) {
		for(Entry user: getByResourceURI(userUri)) {
			if (user.getGraphType() == GraphType.User) {
				return (User) user.getResource();
			}
		}
		return null;
	}

	/**
	 * Returns a Group object representing a group of users.
	 * @param groupUri URI to the group.
	 * @return the Group object
	 */
	public Group getGroup(URI groupUri) {
		for(Entry user: getByResourceURI(groupUri)) {
			if (user.getGraphType() == GraphType.Group) {
				return (Group) user.getResource();
			}
		}
		return null;
	}

	public Set <URI> getGroupUris() {
		Iterator <URI> entryIterator = getEntries().iterator();
		Set <URI> groupUris = new HashSet<>();

		//sort out the groups
		while(entryIterator.hasNext()) {
			URI nextURI = entryIterator.next();

			Entry nextGroup = getByEntryURI(nextURI);
			if (GraphType.Group.equals(nextGroup.getGraphType())) {
				groupUris.add(nextGroup.getResourceURI());
			}
		}

		return groupUris;
	}

	public Set <URI> getGroupUris(URI userUri) {
		Iterator <URI> entryIterator = getEntries().iterator();
		Set <URI> groupUris = new HashSet<>();

		User user = getUser(userUri);
		if (user != null) {
			while(entryIterator.hasNext()) {
				URI nextURI = entryIterator.next();
				Entry nextEntry = getByEntryURI(nextURI);
				// Skip if the entry was removed by a concurrent delete between getEntries()
				// returning the URI and getByEntryURI(URI) being called for it. Sibling iterations
				// elsewhere in this class share the same race shape; this guard targets only the
				// access-control hot path.
				if (nextEntry == null) {
					log.debug("Skipping null entry for URI {} in getGroupUris (likely concurrent delete)", nextURI);
					continue;
				}
				if(GraphType.Group.equals(nextEntry.getGraphType())) {
					Group nextGroup = (Group) nextEntry.getResource();
					if(nextGroup != null) {
						if(nextGroup.isMember(user)) {
							groupUris.add(nextGroup.getURI());
						}
					}
				}
			}
		}

		return groupUris;
	}

	/**
	 * Cache-fronted {@link #getGroupUris(URI)} (ENTRYSTORE-1085). Returns the cached group set for
	 * the user, scanning at most once per invalidation window. The returned set must not be
	 * mutated — callers copy (see UserGroupsMemo).
	 */
	Set<URI> getGroupUrisCached(URI userURI) {
		if (!isGroupCacheEnabled()) {
			return getGroupUris(userURI);
		}
		Set<URI> cached = userGroupsCache.get(userURI);
		if (cached != null) {
			return cached;
		}
		long epochBefore = userGroupsCacheEpoch.get();
		Set<URI> groups = Set.copyOf(getGroupUris(userURI));
		if (userGroupsCacheEpoch.get() == epochBefore) {
			userGroupsCache.putIfAbsent(userURI, groups);
			if (userGroupsCacheEpoch.get() != epochBefore) {
				// An invalidation raced the put — our snapshot may predate the mutation.
				userGroupsCache.remove(userURI);
			}
		}
		return groups;
	}

	void invalidateGroupCache() {
		userGroupsCacheEpoch.incrementAndGet();
		userGroupsCache.clear();
	}

	void evictFromGroupCache(URI userURI) {
		userGroupsCacheEpoch.incrementAndGet();
		userGroupsCache.remove(userURI);
	}

	private boolean isGroupCacheEnabled() {
		Boolean enabled = groupCacheEnabled;
		if (enabled == null) {
			enabled = entry.getRepositoryManager().getConfiguration().getBoolean(Settings.AUTH_GROUP_CACHE, true);
			groupCacheEnabled = enabled;
		}
		return enabled;
	}

	public List<URI> getGroupEntryUris() {
		Iterator < URI > entryIterator = getEntries().iterator();
		List<URI> groupUris = new ArrayList<>();

		//sort out the groups
		while(entryIterator.hasNext()) {
			URI nextURI = entryIterator.next();
			Entry e = getByEntryURI(nextURI);
			if(e.getGraphType() == GraphType.Group) {
				groupUris.add(nextURI);
			}
		}

		return groupUris;
	}

	/**
	 * Sets which user that is authenticated in this specific thread.
	 * @param userUri The URI to the user that was authenticated.
	 */
	public void setAuthenticatedUserURI(URI userUri) {
		authenticatedUserURI.set(userUri);
	}

	public URI getAuthenticatedUserURI() {
		return authenticatedUserURI.get();
	}

	@Override
	public boolean currentUserIsGuest() {
		URI currentUserUri = authenticatedUserURI.get();
		return currentUserUri == null || getGuestUser().getURI().equals(currentUserUri);
	}

	@Override
	public boolean currentUserIsAdminOrAdminGroup() {
		URI currentUserUri = authenticatedUserURI.get();
		return isUserAdminOrAdminGroup(currentUserUri);
	}

	/**
	 * Checks if the authenticated user it authorized to perform a specific task on a Entry. The task is defined by an access property.
	 * @param entry the entry on which to check the specified accessProperty.
	 * @param accessProperty the access property to check the entry for.
	 * @throws AuthorizationException if not allowed.
	 */
	public void checkAuthenticatedUserAuthorized(Entry entry, AccessProperty accessProperty) throws AuthorizationException {

		//is check authorization on?
		if(!entry.getRepositoryManager().isCheckForAuthorization()) {
			return;
		}

		URI currentUserURI = getAuthenticatedUserURI();

		//is anyone logged in on this thread?
		if (currentUserURI == null) {
			currentUserURI = getGuestUser().getURI();
			log.warn("Authenticated user not set, assuming guest user");
		}

		if (isUserAuthorized(currentUserURI, entry, accessProperty)) {
			return;
		}

		//Load the user for the exception message under admin rights, matching the historic behaviour.
		URI savedUserURI = getAuthenticatedUserURI();
		try {
			setAuthenticatedUserURI(getAdminUser().getURI());
			throw new AuthorizationException(getUser(currentUserURI), entry, accessProperty);
		} finally {
			setAuthenticatedUserURI(savedUserURI);
		}
	}

	@Override
	public boolean isUserAuthorized(URI userURI, Entry entry, AccessProperty accessProperty) {

		//is check authorization on?
		if (!entry.getRepositoryManager().isCheckForAuthorization()) {
			return true;
		}

		if (userURI == null) {
			userURI = getGuestUser().getURI();
		}

		//is admin?
		if (userURI.equals(getAdminUser().getURI())) {
			return true;
		}

		if (userURI.equals(entry.getResourceURI()) &&
				(accessProperty == AccessProperty.ReadMetadata || accessProperty == AccessProperty.ReadResource)) {
			return true;
		}

		URI savedUserURI = getAuthenticatedUserURI();
		try {
			//Switch to admin so that the PrincipalManager can perform all
			//neccessary checks without being hindered by itself (results in loops).
			setAuthenticatedUserURI(getAdminUser().getURI());

			User user = getUser(userURI);

			//Check if user is in admingroup.
			if (getAdminGroup().isMember(user)) {
				return true;
			}

			//B1: resolve the user's groups at most once for this whole decision.
			UserGroupsMemo groupsMemo = new UserGroupsMemo(userURI);

			Entry contextEntry = entry.getContext().getEntry();
			//Check if user is owner of surrounding context
			if (hasAccess(user, contextEntry, AccessProperty.Administer, groupsMemo)) {
				return true;
			} else {
				//If entry overrides Context ACL (only relevant if the user is not an owner of the context)
				if (entry.hasAllowedPrincipals()) {
					if (hasAccess(user, entry, AccessProperty.Administer, groupsMemo)
							|| hasAccess(user, entry, accessProperty, groupsMemo)) {
						return true;
					} else if (accessProperty == AccessProperty.ReadMetadata
							&& hasAccess(user, entry, AccessProperty.WriteMetadata, groupsMemo)) {
						return true; //WriteMetadata implies ReadMetadata
					} else if (accessProperty == AccessProperty.ReadResource
							&& hasAccess(user, entry, AccessProperty.WriteResource, groupsMemo)) {
						return true; //WriteResource implies ReadResource
					}
				} else {
					//Check if user has access to the surrounding context of the entry.
					if (accessProperty == AccessProperty.ReadMetadata || accessProperty == AccessProperty.ReadResource) {
						if (hasAccess(user, contextEntry, AccessProperty.ReadResource, groupsMemo)
								|| hasAccess(user, contextEntry, AccessProperty.WriteResource, groupsMemo)) {
							return true; //Both read and write on the context resource implies read on all entries for both the metadata and the resource.
						}
					} else {
						if (hasAccess(user, contextEntry, AccessProperty.WriteResource, groupsMemo)) {
							return true;
						}
					}
				}
			}

			return false;
		} finally {
			//Switch back to the previously authenticated user.
			setAuthenticatedUserURI(savedUserURI);
		}
	}

	protected boolean hasAccess(User currentUser, Entry entry, AccessProperty prop) {
		// A null currentUser is denied by the overload below, before the memo is ever consulted, so
		// there is nothing to resolve groups for in that case.
		return hasAccess(currentUser, entry, prop,
				currentUser == null ? null : new UserGroupsMemo(currentUser.getURI()));
	}

	/**
	 * B1: per-authorization-decision lazy memo of a user's group URIs. One {@link #isUserAuthorized}
	 * /{@link #getRights} decision can consult the groups up to a dozen times across repeated
	 * hasAccess calls; the memo collapses those to at most one lookup. Since ENTRYSTORE-1085 the
	 * lookup itself goes through {@link #getGroupUrisCached(URI)}, so the principals-context scan
	 * is also amortised across decisions (with listener-based invalidation).
	 */
	private final class UserGroupsMemo {
		private final URI userURI;
		private Set<URI> groups;

		UserGroupsMemo(URI userURI) {
			this.userURI = userURI;
		}

		/** Returns a fresh copy each call; callers mutate the result via retainAll. */
		Set<URI> copy() {
			if (groups == null) {
				groups = getGroupUrisCached(userURI);
			}
			return new HashSet<>(groups);
		}
	}

	private boolean hasAccess(User currentUser, Entry entry, AccessProperty prop, UserGroupsMemo groupsMemo) {
		if (currentUser == null) {
			// Fail closed on a principal that could not be resolved. Without this, the "_users" check below
			// reads currentUser != getGuestUser() as true, so every entry granting the user group would be
			// granted to an unresolvable principal, and the check after it dereferences null. getUser
			// returns null whenever the principal's resHasEntry mapping is unreadable — for instance from a
			// context whose index is incomplete (ENTRYSTORE-1095) — and "I could not read the principal"
			// must not answer the same as "this principal is allowed".
			//
			// This deliberately returns before the guest check below, so an unresolvable principal is also
			// denied entries that are explicitly guest-readable: a session carrying the URI of a deleted
			// user entry loses access to public content it could read before. That is the conservative
			// direction — the alternative is to keep serving a session whose principal we demonstrably
			// cannot read, and public content is reachable by simply not authenticating.
			log.warn("Denying {} access: the authenticated principal could not be resolved to a user", prop);
			return false;
		}
		Set<URI> principals = entry.getAllowedPrincipalsFor(prop);
		if (!principals.isEmpty()) {

			//Check if guest is in principals.
			if (principals.contains(getGuestUser().getURI())) {
				return true;
			}

			//Check if the special "user" group is in principals and user is not guest.
			if (currentUser != getGuestUser() && principals.contains(getUserGroup().getURI())) {
				return true;
			}

			//Check if user is in principals.
			if (principals.contains(currentUser.getURI())) {
				return true;
			}

			//Check if any of the groups the user belongs to is in principals
			Set<URI> groups = groupsMemo.copy();
			groups.retainAll(principals);
			if (!groups.isEmpty()) {
				return true;
			}
		}

		if (prop != AccessProperty.Administer) {
			principals = entry.getAllowedPrincipalsFor(AccessProperty.Administer);
			if (!principals.isEmpty()) {

				//Check if user is in principals.
				if (principals.contains(currentUser.getURI())) {
					return true;
				}

				//Check if any of the groups the user belongs to is in principals
				Set<URI> groups = groupsMemo.copy();
				groups.retainAll(principals);
				return !groups.isEmpty();
			}
		}
		return false;
	}

	public Set<AccessProperty> getRights(Entry entry) {
		Set<AccessProperty> set = new HashSet<>();
		//is check authorization on?
		if(!entry.getRepositoryManager().isCheckForAuthorization()) {
			set.add(AccessProperty.Administer);
			return set;
		}

		URI currentUserURI = getAuthenticatedUserURI();

		//is anyone logged in on this thread?
		if (currentUserURI == null) {
			//TODO, should we perhaps assume guest if none set?
			log.error("Authenticated user not set, should at least be guest");
			throw new AuthorizationException(null, entry, null);
		}

		//is admin?
		if(currentUserURI.equals(getAdminUser().getURI())) {
			set.add(AccessProperty.Administer);
			return set;
		}

		try {
			//Switch to admin so that the PrincipalManager can perform all
			//neccessary checks without being hindered by itself (results in loops).
			setAuthenticatedUserURI(getAdminUser().getURI());

			//Fetch the current user from thread local.
			User currentUser = getUser(currentUserURI);

			//Check if user is in admingroup.
			if (getAdminGroup().isMember(currentUser)) {
				set.add(AccessProperty.Administer);
				return set;
			}

			//B1: resolve the user's groups at most once for this whole decision.
			UserGroupsMemo groupsMemo = new UserGroupsMemo(currentUserURI);

			Entry contextEntry = entry.getContext().getEntry();
			//Check if user is owner of surrounding context
			if (hasAccess(currentUser, contextEntry, AccessProperty.Administer, groupsMemo)) {
				set.add(AccessProperty.Administer);
			} else {
				//If entry overrides Context ACL (only relevant if the user is not an owner of the context)
				if(entry.hasAllowedPrincipals()) {
					if (hasAccess(currentUser, entry, AccessProperty.Administer, groupsMemo)) {
						set.add(AccessProperty.Administer);
						return set;
					} else {
						if (hasAccess(currentUser, entry, AccessProperty.WriteMetadata, groupsMemo)) {
							set.add(AccessProperty.WriteMetadata);
						} else if (hasAccess(currentUser, entry, AccessProperty.ReadMetadata, groupsMemo)) {
							set.add(AccessProperty.ReadMetadata);
						}
						if (hasAccess(currentUser, entry, AccessProperty.WriteResource, groupsMemo)) {
							set.add(AccessProperty.WriteResource);
						} else if (hasAccess(currentUser, entry, AccessProperty.ReadResource, groupsMemo)) {
							set.add(AccessProperty.ReadResource);
						}
					}
				} else {
					if (hasAccess(currentUser, contextEntry, AccessProperty.WriteResource, groupsMemo)) {
						set.add(AccessProperty.Administer);
					} else if (hasAccess(currentUser, contextEntry, AccessProperty.ReadResource, groupsMemo)) {
						set.add(AccessProperty.ReadMetadata);
						set.add(AccessProperty.ReadResource);
					}
				}
			}
		} finally {
			//Switch back to the current user.
			setAuthenticatedUserURI(currentUserURI);
		}
		return set;
	}


	/**
	 * Checks if a secret is valid.
	 * @param secret Secret to be checked.
	 * @return true If the secret fullfils minimum requirements, currently a minimum length of 8 characters.
	 */
	public boolean isValidSecret(String secret) {
		return Password.conformsToRules(secret);
	}

	@Override
	public void initResource(EntryImpl newEntry) throws RepositoryException {
		if (newEntry.getEntryType() != EntryType.Local) {
			return;
		}
		switch (newEntry.getGraphType()) {
		case User:
			newEntry.setResource(new UserImpl(newEntry, newEntry.getSesameResourceURI(), softCache));
			break;
		case Group:
			newEntry.setResource(new GroupImpl(newEntry, newEntry.getSesameResourceURI(), softCache));
			break;
		default:
			super.initResource(newEntry);
		}
	}

	public void initializeSystemEntries() {
		super.initializeSystemEntries();
		Entry adminUserEntry;
		Entry adminGroupEntry;
		Entry userGroupEntry;
		Entry guestUserEntry;

		guestUserEntry = get("_guest");
		if (guestUserEntry != null) {
			guestUser = (User) guestUserEntry.getResource();
		} else {
			guestUserEntry = this.createNewMinimalItem(null, null, EntryType.Local, GraphType.User, null, "_guest");
			setMetadata(guestUserEntry, "Guest user", "All non logged in users will automatically appear as this user.");
			guestUser = (User) guestUserEntry.getResource();
			guestUser.setName("guest");
			guestUserEntry.addAllowedPrincipalsFor(AccessProperty.ReadMetadata, guestUser.getURI());
			log.info("Successfully added the guest user");
		}
		addSystemEntryToSystemEntries(guestUserEntry.getEntryURI());


		adminUserEntry = get("_admin");
		if (adminUserEntry != null) {
			adminUser = (User) adminUserEntry.getResource();
		} else {
			adminUserEntry = this.createNewMinimalItem(null, null, EntryType.Local, GraphType.User, null, "_admin");
			setMetadata(adminUserEntry, "Admin user", "Default super user, has all rights.");
			adminUser = (User) adminUserEntry.getResource();
			adminUser.setName("admin");
			String adminSecret = System.getenv(ENV_ADMIN_PASSWORD);
			if (adminSecret != null) {
				log.info("Setting admin password based on environment variable {}", ENV_ADMIN_PASSWORD);
				if (!adminUser.setSecret(adminSecret)) {
					log.warn("Password in environment variable does not conform to configured rules, initializing admin user without password");
				}
			} else {
				log.warn("Environment variable {} not found, initializing admin user without password", ENV_ADMIN_PASSWORD);
			}
			adminUserEntry.addAllowedPrincipalsFor(AccessProperty.ReadMetadata, guestUser.getURI());
			log.info("Successfully added the admin user");
		}
		addSystemEntryToSystemEntries(adminUserEntry.getEntryURI());

		adminGroupEntry = get("_admins");
		if(adminGroupEntry != null) {
			adminGroup = (Group) adminGroupEntry.getResource();
		} else {
			adminGroupEntry = this.createNewMinimalItem(null, null, EntryType.Local, GraphType.Group, null, "_admins");
			setMetadata(adminGroupEntry, "Admin group", "All members of this group have super user rights.");
			adminGroup = (Group) adminGroupEntry.getResource();
			adminGroup.setName("admins");
			adminGroupEntry.addAllowedPrincipalsFor(AccessProperty.ReadMetadata, guestUser.getURI());
			log.info("Successfully added the admin group");
		}
		addSystemEntryToSystemEntries(adminGroupEntry.getEntryURI());

		userGroupEntry = get("_users");
		if(userGroupEntry == null) {
			userGroupEntry = this.createNewMinimalItem(null, null, EntryType.Local, GraphType.Group, null, "_users");
			setMetadata(userGroupEntry, "Users group", "All regular users are part of this group.");
			setPrincipalName(userGroupEntry.getResourceURI(), "users");
			userGroupEntry.addAllowedPrincipalsFor(AccessProperty.ReadMetadata, guestUser.getURI());
			log.info("Successfully added the user group");
		}
		EntryImpl e = (EntryImpl) userGroupEntry;
		e.setResource(new SystemGroup(e, e.getSesameResourceURI()) {
			@Override
			public boolean isMember(User user) {
				return (user != null &&
						PrincipalManagerImpl.this.guestUser != null &&
						!user.getURI().equals(PrincipalManagerImpl.this.guestUser.getURI()));
				// return true;
			}
			@Override
			public List<User> members() {
                this.entry.getRepositoryManager().getPrincipalManager().checkAuthenticatedUserAuthorized(this.entry, AccessProperty.ReadResource);
                return getUsers();
			}
			@Override
			public List<URI> memberUris() {
                this.entry.getRepositoryManager().getPrincipalManager().checkAuthenticatedUserAuthorized(this.entry, AccessProperty.ReadResource);
                return getUsersAsUris();
			}
		});

		userGroup = (Group) userGroupEntry.getResource();
		addSystemEntryToSystemEntries(userGroupEntry.getEntryURI());
	}

	/**
	 * @param externalID
	 *            An E-Mail address
	 * @return A user that can be mapped to the external E-Mail address (that
	 *         e.g. originates from an OpenID service)
	 * @see org.entrystore.PrincipalManager#getUserByExternalID(java.lang.String)
	 */
	public User getUserByExternalID(String externalID) {
		RepositoryConnection rc = null;
		Resource userResourceURI = null;
		try {
			rc = entry.getRepository().getConnection();
			ValueFactory vf = rc.getValueFactory();
			RepositoryResult<Statement> rr = rc.getStatements(null, RepositoryProperties.externalID, vf.createIRI("mailto:", externalID), false);
			if (rr.hasNext()) {
				userResourceURI = rr.next().getSubject();
			}
			rr.close();
		} catch (RepositoryException re) {
			log.error(re.getMessage(), re);
		} finally {
			if (rc != null) {
				try {
					rc.close();
				} catch (RepositoryException ignore) {}
			}
		}
		if (userResourceURI == null) {
			return null;
		}
		return getUser(URI.create(userResourceURI.stringValue()));
	}

}
