/*
 * Copyright (c) 2007-2017 MetaSolutions AB
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

package org.entrystore;

import java.net.URI;
import java.util.List;
import java.util.Set;

public interface PrincipalManager extends Context {

	enum AccessProperty {
		ReadResource, ReadMetadata, WriteResource, WriteMetadata, Administer
	}

	String getPrincipalName(URI principal);

	Entry getPrincipalEntry(String name);

    boolean isUserAdminOrAdminGroup(URI principal);

	boolean setPrincipalName(URI principal, String newName);

	List<URI> getUsersAsUris();

	List<User> getUsers();

	User getUser(URI userEntryUri);

	Set<URI> getGroupUris();

	Group getGroup(URI groupEntryUri);

	void setAuthenticatedUserURI(URI userUri);

	URI getAuthenticatedUserURI();

	Set<AccessProperty> getRights(Entry entry);

	void checkAuthenticatedUserAuthorized(Entry entry, AccessProperty accessProperty)
			throws AuthorizationException;

	/**
	 * Non-throwing authorization check for an explicitly given user. Returns the same decision
	 * {@link #checkAuthenticatedUserAuthorized(Entry, AccessProperty)} would make for that user
	 * (including admin, admin-group, context-ACL inheritance and the read/write implication rules),
	 * but as a boolean instead of throwing {@link AuthorizationException}. Intended for probes such
	 * as "is this entry guest-readable?" where the throw is pure control flow.
	 *
	 * @param userURI the resource URI of the user to check (null is treated as the guest user)
	 * @param entry the entry to check
	 * @param accessProperty the access to check for
	 * @return true if the given user is authorized
	 */
	boolean isUserAuthorized(URI userURI, Entry entry, AccessProperty accessProperty);

	boolean isValidSecret(String name);

	User getAdminUser();

	Group getAdminGroup();

	User getGuestUser();

	Group getUserGroup();

	User getUserByExternalID(String openid);

	boolean currentUserIsGuest();

	boolean currentUserIsAdminOrAdminGroup();
}
