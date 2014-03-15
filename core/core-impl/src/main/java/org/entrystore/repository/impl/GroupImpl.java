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

package org.entrystore.repository.impl;

import java.util.Iterator;
import java.util.List;
import java.util.Vector;

import org.entrystore.GraphType;
import org.entrystore.Entry;
import org.entrystore.Group;
import org.entrystore.PrincipalManager;
import org.entrystore.User;
import org.openrdf.model.URI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * A class that represents a group of users. This group may be assigned privilegies to some entries or other resources at the same way as a user may is.
 * @author olov
 *
 */
public class GroupImpl extends ListImpl implements Group {
	
	/** Logger */
	static Logger log = LoggerFactory.getLogger(UserImpl.class);
	
	/**
	 * Creates a new group with the specified URI
	 * @param entry the entry for the new group
	 * @param uri the URI for the new group
	 */
	public GroupImpl(EntryImpl entry, URI uri, SoftCache cache) {
		super(entry, uri);
	}
	
	/**
	 * Returns the name of the user
	 * @return the name of the user
	 */
	public String getName() {
		return ((PrincipalManager) this.entry.getContext()).getPrincipalName(this.getURI());
	}

	/**
	 * Tries to sets the users name.
	 * @param newName the requested name
	 * @param pm The PrincipalManager that contains this principal
	 * @return true if the name was approved, false otherwise
	 */
	public boolean setName(String newName) {
		return ((PrincipalManager) this.entry.getContext()).setPrincipalName(this.getURI(), newName);
	}

	/**
	 * Adds a user to the group through adding its URI to the groups list of URIs.
	 * @param The user to add to the group
	 */
	public void addMember(User user) {
		addChild(user.getEntry().getEntryURI());
	}

	/**
	 * Removes a user from the group.
	 * @param The user to remove
	 * @return true if the removing was successful, false otherwise
	 */
	public boolean removeMember(User user) {
		return removeChild(user.getEntry().getEntryURI());
	}

	/**
	 * Tells whether a user is member of the group.
	 * @param user the user to test if has group member
	 * @return true if the user is a member, false otherwise
	 */
	public boolean isMember(User user) {
		if (user == null) {
			return false;
		}
		
		List<java.net.URI> children = getChildren();
		Entry userEntry = user.getEntry();
		java.net.URI userEntryURI = userEntry.getEntryURI();
		
		return children.contains(userEntryURI);
	}

	/**
	 * Returns a list of all members URIs (entryURIs)
	 * @return a list of all members URIs
	 */
	public List<java.net.URI> memberUris() {
		return getChildren();
	}

	/**
	 * Returns a list of all members
	 * @return a list of all members
	 */
	public List<User> members() {
		List<User> userList = new Vector<User>();
		Iterator<java.net.URI> memberUriIterator = memberUris().iterator();
		boolean contentError = false;

		while(memberUriIterator.hasNext()) {
			java.net.URI entryURI = memberUriIterator.next();
			try {
				Entry userEntry = entry.getContext().getByEntryURI(entryURI);
				if(userEntry.getGraphType() == GraphType.User) {
					userList.add((User) userEntry.getResource());
				}
				else {
					contentError = true;
				}
			}
			catch (NullPointerException e) {
				log.error(e.getMessage());
			}
		}
		
		if (contentError) {
			log.error("Error in group " + getURI().toString() + " . All members does not seem to be of the type User.");
		}

		return userList;
	}

	public Vector<java.net.URI> setChildren(Vector<java.net.URI> children) {
		setChildren(children, true, true);
		return new Vector<java.net.URI>(getChildren()); 

	}
	
		

	
	

}