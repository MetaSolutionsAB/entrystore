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

package se.kmr.scam.repository;

import java.util.Set;

public interface User extends Resource, java.security.Principal {
	
	String getName(); // Already declared in java.security.Principal

	String getSecret();

	Context getHomeContext();
	
	/**
	 * @return Preferred language of the user.
	 */
	String getLanguage();

	boolean setName(String newName);

	boolean setSecret(String newSecret);

	boolean setHomeContext(Context context);
	
	boolean setLanguage(String language);
	
	/**
	 * @return a list of groups, each group represented by a java.net.URI
	 * referring the resource of the group entry.
	 */
	Set<java.net.URI> getGroups();
}