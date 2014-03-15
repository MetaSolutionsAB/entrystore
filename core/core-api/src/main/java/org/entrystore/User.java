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

public interface User extends Resource, java.security.Principal {
	
	String getName(); // Already declared in java.security.Principal

	boolean setName(String newName);

	Context getHomeContext();

	boolean setHomeContext(Context context);
	
	String getLanguage();

	boolean setLanguage(String language);

	@Deprecated
	String getSecret();
	
	String getSaltedHashedSecret();
	
	boolean setSecret(String newSecret);
	
	String getExternalID();
	
	boolean setExternalID(String id);
	
	/**
	 * @return a list of groups, each group represented by a java.net.URI
	 * referring the resource of the group entry.
	 */
	Set<URI> getGroups();

}