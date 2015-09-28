/*
 * Copyright (c) 2007-2014 MetaSolutions AB
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
import java.util.Set;
import java.util.List;

public interface PrincipalManager extends Context {

	public enum AccessProperty {
		ReadResource, ReadMetadata, WriteResource, WriteMetadata, Administer
	};

	String getPrincipalName(URI principal);

	Entry getPrincipalEntry(String name);

    boolean isUserAdminOrAdminGroup(URI principal);

	boolean setPrincipalName(URI principal, String newName);

	public List<URI> getUsersAsUris();

	public List<User> getUsers();

	public User getUser(URI userEntryUri);

	public Set<URI> getGroupUris();

	public Group getGroup(URI groupEntryUri);

	public void setAuthenticatedUserURI(URI userUri);

	public URI getAuthenticatedUserURI();

	public Set<AccessProperty> getRights(Entry entry);

	public void checkAuthenticatedUserAuthorized(Entry entry, AccessProperty accessProperty)
			throws AuthorizationException;

	public boolean isValidSecret(String name);

	public User getAdminUser();

	public Group getAdminGroup();

	public User getGuestUser();

	public Group getUserGroup();
	
	public User getUserByExternalID(String openid);

}