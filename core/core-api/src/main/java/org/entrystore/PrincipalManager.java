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

	boolean isValidSecret(String name);

	User getAdminUser();

	Group getAdminGroup();

	User getGuestUser();

	Group getUserGroup();

	User getUserByExternalID(String openid);

	boolean currentUserIsGuest();

	boolean currentUserIsAdminOrAdminGroup();
}
