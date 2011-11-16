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

import java.net.URI;

public interface Group extends Resource {
	boolean setChildren(java.util.List<URI> children);
	public String 							getName();
	public boolean 							setName(String name);
	public void 							addMember(User user);
	public boolean 							removeMember(User user);
	public boolean 							isMember(User user);
	public java.util.List < User > 			members();
	public java.util.List < java.net.URI > 	memberUris();
}
