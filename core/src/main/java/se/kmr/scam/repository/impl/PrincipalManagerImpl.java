/**
 * Copyright (c) 2007-2010
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

package se.kmr.scam.repository.impl;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.openrdf.repository.RepositoryException;

import se.kmr.scam.repository.AuthorizationException;
import se.kmr.scam.repository.BuiltinType;
import se.kmr.scam.repository.Entry;
import se.kmr.scam.repository.Group;
import se.kmr.scam.repository.LocationType;
import se.kmr.scam.repository.PrincipalManager;
import se.kmr.scam.repository.User;
import se.kmr.scam.repository.util.URISplit;
import se.kmr.scam.repository.util.URISplit.URIType;

/**
 * Creates a 
 * @author Olov Wikberg, IML Umeå University
 * @author matthias
 *
 */
public class PrincipalManagerImpl extends EntryNamesContext implements PrincipalManager {
	private static Log log = LogFactory.getLog(PrincipalManagerImpl.class);
	private static final ThreadLocal < URI > authenticatedUserURI = new ThreadLocal < URI > ();
	public User adminUser = null;
	public Group adminGroup = null;
	public User guestUser = null;
	public Group userGroup = null;
	private EntryImpl allPrincipals;
	
	/**
	 * Creates a principal manager
	 * @param entry this principal managers entry
	 * @param uri this principal managers URI 
	 * @param cache
	 */
	public PrincipalManagerImpl(EntryImpl entry, String uri, SoftCache cache) {
		super(entry, uri, cache);
	}
	

	public String getPrincipalName(URI principal) {
		URISplit us = new URISplit(principal, this.entry.getRepositoryManager().getRepositoryURL());
		if (us.getURIType() == URIType.Resource) {
			return getName(us.getMetaMetadataURI());
		}
		throw new se.kmr.scam.repository.RepositoryException("Given URI is not an existing resourceURI of a Principal.");			
	}

	public Entry getPrincipalEntry(String name) {
		Entry principalEntry = getEntryByName(name);
		if (principalEntry == null) {
			return null;
		} else if (principalEntry.getBuiltinType() == BuiltinType.User ||
						principalEntry.getBuiltinType() == BuiltinType.Group) {
			return principalEntry;
		}
		throw new se.kmr.scam.repository.RepositoryException("Found entry for the name is not a principal...\n" +
				"this is either a programming error or someone have been tampering with the RDF directly.");
	}

	public boolean setPrincipalName(URI principal, String newName) {
		URISplit us = new URISplit(principal, this.entry.getRepositoryManager().getRepositoryURL());
		Entry principalEntry = getByEntryURI(us.getMetaMetadataURI());
		if (principalEntry == null) {
			throw new se.kmr.scam.repository.RepositoryException("Cannot find an entry for the specified URI");
		} else if (principalEntry.getBuiltinType() == BuiltinType.User ||
					principalEntry.getBuiltinType() == BuiltinType.Group) {
			return setEntryName(us.getMetaMetadataURI(), newName);
		}
		throw new se.kmr.scam.repository.RepositoryException("Given URI does not refer to a Principal.");			
	}
	
	/**
	 * Returns this principal managers all user URIs
	 * @return all user URIs in this principal manager
	 */
	public List<URI> getUsersAsUris() {
		Iterator < URI > entryIterator = getEntries().iterator();
		List< URI > userUris = new ArrayList<URI>();

		//sort out the users
		while(entryIterator.hasNext()) {
			URI nextURI = entryIterator.next();

			Entry nextEntry = getByEntryURI(nextURI);
			if(nextEntry.getBuiltinType() == BuiltinType.User) {
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
		List<User> userUris = new ArrayList<User>();

		//sort out the users
		while(entryIterator.hasNext()) {
			URI nextURI = entryIterator.next();
			
			Entry nextEntry = getByEntryURI(nextURI);
			if(nextEntry.getBuiltinType() == BuiltinType.User) {
				userUris.add((User) nextEntry.getResource());
			}
		}

		return userUris;
	}

	
	/**
	 * Returns a User object representing a user.
	 * @param the URI to the user.
	 * @return the User object
	 */
	public User getUser(URI userUri) {
		for(Entry user: getByResourceURI(userUri)) {
			if (user.getBuiltinType() == BuiltinType.User) {
				return (User) user.getResource();
			}
		}
		return null;
	}

	/**
	 * Returns a Group object representing a group of users.
	 * @param the URI to the group.
	 * @return the Group object
	 */
	public Group getGroup(URI groupUri) {
		for(Entry user: getByResourceURI(groupUri)) {
			if (user.getBuiltinType() == BuiltinType.Group) {
				return (Group) user.getResource();
			}
		}
		return null;
	}
	
	public Set <URI> getGroupUris() {
		Iterator <URI> entryIterator = getEntries().iterator();
		Set < URI > groupUris = new HashSet < URI > ();

		//sort out the groups
		while(entryIterator.hasNext()) {
			URI nextURI = entryIterator.next();

			Entry nextGroup = getByEntryURI(nextURI);
			if(nextGroup.getBuiltinType() == BuiltinType.Group) {
				groupUris.add(nextGroup.getResourceURI());
			}
		}

		return groupUris;
	}

	public Set < URI > getGroupUris(URI userUri) {
		Iterator < URI > entryIterator = getEntries().iterator();
		Set < URI > groupUris = new HashSet < URI > ();

		User user = getUser(userUri);
		if (user != null) {
			while(entryIterator.hasNext()) {
				URI nextURI = entryIterator.next();
				Entry nextEntry = getByEntryURI(nextURI); 
				if(nextEntry.getBuiltinType() == BuiltinType.Group) {
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

	public List<URI> getGroupEntryUris() {
		Iterator < URI > entryIterator = getEntries().iterator();
		List<URI> groupUris = new ArrayList<URI>();

		//sort out the groups
		while(entryIterator.hasNext()) {
			URI nextURI = entryIterator.next();
			Entry e = getByEntryURI(nextURI); 
			if(e.getBuiltinType() == BuiltinType.Group) {
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

		//is admin?
		if(currentUserURI.equals(getAdminUser().getURI())) {
			return;
		}

		//Switch to admin so that the PrincipalManager can perform all
		//neccessary checks without being hindered by itself (results in loops).
		setAuthenticatedUserURI(getAdminUser().getURI());
		
		try {
			
			//Fetch the current user from thread local.
			User currentUser = getUser(currentUserURI);

			//Check if user is in admingroup.
			if (getAdminGroup().isMember(currentUser)) {
				return;
			}

			Entry contextEntry = entry.getContext().getEntry();
			//Check if user is owner of surrounding context
			if (hasAccess(currentUser, contextEntry, AccessProperty.Administer)) {
				return;
			} else {
				//If entry overrides Context ACL (only relevant if the user is not an owner of the context)
				if(entry.hasAllowedPrincipals()) {
					if (hasAccess(currentUser, entry, accessProperty)) {
						return;
					}
				} else {
					//Check if user has access to the surrounding context of the entry.
					if (accessProperty == AccessProperty.ReadMetadata || accessProperty == AccessProperty.ReadResource) {
						if (hasAccess(currentUser, contextEntry, AccessProperty.ReadResource)) {
							return;
						}
					} else {
						if (hasAccess(currentUser, contextEntry, AccessProperty.WriteResource)) {
							return;
						}	
					}					
				}
			}
			
			throw new AuthorizationException(currentUser, entry, accessProperty);
		} finally {
			//Switch back to the current user.
			setAuthenticatedUserURI(currentUserURI);
		}
	}
	
	protected boolean hasAccess(User currentUser, Entry entry, AccessProperty prop) {
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
			Set<URI> groups = getGroupUris(currentUser.getURI());
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
				Set<URI> groups = getGroupUris(currentUser.getURI());
				groups.retainAll(principals);
				if (!groups.isEmpty()) {
					return true;
				}
			}
		}
		return false;
	}

	public Set<AccessProperty> getRights(Entry entry) {
		Set<AccessProperty> set = new HashSet<AccessProperty>();
		//is check authorization on?
		if(!entry.getRepositoryManager().isCheckForAuthorization()) {
			set.add(AccessProperty.Administer);
			return set;
		}

		URI currentUserURI = getAuthenticatedUserURI();

		//is anyone logged in on this thread?
		if(currentUserURI == null) {
			//TODO, should we perhaps assume guest if none set?
			log.error("Authenticated user not set, should at least be guest.");
			throw new AuthorizationException(null, entry, null);
		}

		//is admin?
		if(currentUserURI.equals(getAdminUser().getURI())) {
			set.add(AccessProperty.Administer);
			return set;
		}

		//Switch to admin so that the PrincipalManager can perform all
		//neccessary checks without being hindered by itself (results in loops).
		setAuthenticatedUserURI(getAdminUser().getURI());
		
		try {
			
			//Fetch the current user from thread local.
			User currentUser = getUser(currentUserURI);

			//Check if user is in admingroup.
			if (getAdminGroup().isMember(currentUser)) {
				set.add(AccessProperty.Administer);
				return set;
			}

			Entry contextEntry = entry.getContext().getEntry();
			//Check if user is owner of surrounding context
			if (hasAccess(currentUser, contextEntry, AccessProperty.Administer)) {
				set.add(AccessProperty.Administer);
			} else {
				//If entry overrides Context ACL (only relevant if the user is not an owner of the context)
				if(entry.hasAllowedPrincipals()) {
					if (hasAccess(currentUser, entry, AccessProperty.Administer)) {
						set.add(AccessProperty.Administer);
						return set;						
					} else {
						if (hasAccess(currentUser, entry, AccessProperty.WriteMetadata)) {
							set.add(AccessProperty.WriteMetadata);
						} else if (hasAccess(currentUser, entry, AccessProperty.ReadMetadata)) {
							set.add(AccessProperty.ReadMetadata);							
						}
						if (hasAccess(currentUser, entry, AccessProperty.WriteResource)) {
							set.add(AccessProperty.WriteResource);
						} else if (hasAccess(currentUser, entry, AccessProperty.ReadResource)) {
							set.add(AccessProperty.ReadResource);
						}
					}
				} else {
					if (hasAccess(currentUser, contextEntry, AccessProperty.WriteResource)) {
						set.add(AccessProperty.Administer);
					} else if (hasAccess(currentUser, contextEntry, AccessProperty.ReadResource)) {
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
	 * Checks if a secret is valid. This includes that is is safe enough (according to function isSecureSecret).
	 * @param secret email address to check if valid
	 * @return true if the email address was valid, false otherwise
	 */
	public boolean isValidSecret(String secret) {
		if(secret == null) {
			return false;
		} else if(secret.length() < 8) {
			return false;
		} else {
			return isSecureSecret(secret);
		}
	}

	/**
	 * Checks if a secret is secure enough.
	 * @param secret email address to check if secure enough
	 * @return true if the email address was valid, false otherwise
	 */
	public boolean isSecureSecret(String secret) {
		if(secret.length() >= 8) {
			return true;
		} else {
			return false;
		}
	}

	public User getAdminUser() {
		return adminUser;
	}

	public Group getAdminGroup() {
		return adminGroup;
	}

	public User getGuestUser() {
		return guestUser;
	}

	public Group getUserGroup() {
		return userGroup;
	}

	@Override
	public void initResource(EntryImpl newEntry) throws RepositoryException {
		if (newEntry.getLocationType() != LocationType.Local) {
			return;
		}
		switch (newEntry.getBuiltinType()) {
		case User:
			newEntry.setResource(new UserImpl(newEntry, newEntry.getSesameResourceURI(), cache));
			break;
		case Group:
			newEntry.setResource(new GroupImpl(newEntry, newEntry.getSesameResourceURI(), cache));
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
		Entry top;
		
		top = get("_top");
		if(top == null) {
			top = this.createNewMinimalItem(null, null, LocationType.Local, BuiltinType.List, null, "_top");
			setMetadata(top, "Top folder", null);
			log.info("Successfully added the top list");
		}
		addSystemEntryToSystemEntries(top.getEntryURI());

		
		guestUserEntry = get("_guest");
		if(guestUserEntry != null) {
			guestUser = (User) guestUserEntry.getResource();
		} else {
			guestUserEntry = this.createNewMinimalItem(null, null, LocationType.Local, BuiltinType.User, null, "_guest");
			setMetadata(guestUserEntry, "Guest user", "All non logged in users will automatically appear as this user.");
			guestUser = (User) guestUserEntry.getResource();
			guestUser.setName("guest");
			guestUserEntry.addAllowedPrincipalsFor(AccessProperty.ReadMetadata, guestUser.getURI());
			log.info("Successfully added the guest user");
		}
		addSystemEntryToSystemEntries(guestUserEntry.getEntryURI());				

		
		adminUserEntry = get("_admin");
		if(adminUserEntry != null) {
			adminUser = (User) adminUserEntry.getResource();
		} else {
			adminUserEntry = this.createNewMinimalItem(null, null, LocationType.Local, BuiltinType.User, null, "_admin");
			setMetadata(adminUserEntry, "Admin user", "Default super user, has all rights.");
			adminUser = (User) adminUserEntry.getResource();
			adminUser.setName("admin");
			adminUser.setSecret("adminadmin");				
			adminUserEntry.addAllowedPrincipalsFor(AccessProperty.ReadMetadata, guestUser.getURI());
			log.info("Successfully added the admin user");
		}
		addSystemEntryToSystemEntries(adminUserEntry.getEntryURI());

		adminGroupEntry = get("_admins");
		if(adminGroupEntry != null) {
			adminGroup = (Group) adminGroupEntry.getResource();
		} else {
			adminGroupEntry = this.createNewMinimalItem(null, null, LocationType.Local, BuiltinType.Group, null, "_admins");
			setMetadata(adminGroupEntry, "Admin group", "All members of this group have super user rights.");
			adminGroup = (Group) adminGroupEntry.getResource();
			adminGroup.setName("admins");
			adminGroupEntry.addAllowedPrincipalsFor(AccessProperty.ReadMetadata, guestUser.getURI());
			log.info("Successfully added the admin group");
		}
		addSystemEntryToSystemEntries(adminGroupEntry.getEntryURI());

		userGroupEntry = get("_users");
		if(userGroupEntry == null) {
			userGroupEntry = this.createNewMinimalItem(null, null, LocationType.Local, BuiltinType.Group, null, "_users");
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
				return getUsers();
			}
			@Override
			public List<URI> memberUris() {
				return getUsersAsUris();
			}
		});
		
		userGroup = (Group) userGroupEntry.getResource();
		addSystemEntryToSystemEntries(userGroupEntry.getEntryURI());
		
		allPrincipals = (EntryImpl) get("_all");
		if(allPrincipals == null) {
			allPrincipals = this.createNewMinimalItem(null, null, LocationType.Local, BuiltinType.List, null, "_all");
			setMetadata(allPrincipals, "all principals", "This is a list of all principals in the PrincipalManager.");
			allPrincipals.addAllowedPrincipalsFor(AccessProperty.ReadMetadata, this.getGuestUser().getURI());
			allPrincipals.addAllowedPrincipalsFor(AccessProperty.ReadResource, this.getGuestUser().getURI());
			log.info("Successfully added the _all contexts list");
		}
		allPrincipals.setResource(new SystemList(allPrincipals, allPrincipals.getSesameResourceURI()) {
			@Override
			public List<URI> getChildren() {
				Iterator<URI> entryIterator = getEntries().iterator();
				List<URI> principalUris = new ArrayList<URI>();

				//sort out the principals
				while(entryIterator.hasNext()) {
					URI nextURI = entryIterator.next();
					
					Entry nextEntry = getByEntryURI(nextURI);
					BuiltinType bt = nextEntry.getBuiltinType(); 
					if(bt == BuiltinType.User || bt == BuiltinType.Group) {
						principalUris.add(nextEntry.getEntryURI());
					}
				}
				return principalUris;
			}
		});
		addSystemEntryToSystemEntries(allPrincipals.getEntryURI());

	}
}