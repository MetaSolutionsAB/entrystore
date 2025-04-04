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

package org.entrystore.impl;

import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.RepositoryResult;
import org.entrystore.Context;
import org.entrystore.Entry;
import org.entrystore.GraphType;
import org.entrystore.Group;
import org.entrystore.PrincipalManager;
import org.entrystore.User;
import org.entrystore.repository.RepositoryEvent;
import org.entrystore.repository.RepositoryEventObject;
import org.entrystore.repository.RepositoryException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.util.Iterator;
import java.util.List;
import java.util.Vector;


/**
 * A class that represents a group of users.
 * This group may be assigned privileges to some entries or other resources in the same way as a user may be.
 *
 * @author olov
 */
public class GroupImpl extends ListImpl implements Group {

	/**
	 * Logger
	 */
	static Logger log = LoggerFactory.getLogger(UserImpl.class);

	private URI homeContext;

	/**
	 * Creates a new group with the specified URI
	 *
	 * @param entry the entry for the new group
	 * @param uri   the URI for the new group
	 */
	public GroupImpl(EntryImpl entry, IRI uri, SoftCache cache) {
		super(entry, uri);
	}

	/**
	 * Returns the name of the user
	 *
	 * @return the name of the user
	 */
	public String getName() {
		return ((PrincipalManager) this.entry.getContext()).getPrincipalName(this.getURI());
	}

	/**
	 * Tries to set the user's name.
	 *
	 * @param newName the requested name
	 * @return true if the name was approved, false otherwise
	 */
	public boolean setName(String newName) {
		return ((PrincipalManager) this.entry.getContext()).setPrincipalName(this.getURI(), newName);
	}

	public Context getHomeContext() {
		if (this.homeContext == null) {
			try (RepositoryConnection rc = this.entry.repository.getConnection()) {
				List<Statement> matches = rc.getStatements(resourceURI, RepositoryProperties.homeContext, null, false, entry.getSesameEntryURI()).stream().toList();
				if (!matches.isEmpty()) {
					this.homeContext = URI.create(matches.getFirst().getObject().stringValue());
				}
			} catch (org.eclipse.rdf4j.repository.RepositoryException e) {
				log.error(e.getMessage(), e);
				throw new RepositoryException("Failed to connect to repository", e);
			}
		}

		if (this.homeContext != null) {
			Entry eContext = this.entry.getRepositoryManager().getContextManager().getByEntryURI(this.homeContext);
			if (eContext != null) {
				return (Context) eContext.getResource();
			}
		}

		return null;
	}

	public boolean setHomeContext(Context context) {
		this.entry.getRepositoryManager().getPrincipalManager().checkAuthenticatedUserAuthorized(entry, PrincipalManager.AccessProperty.WriteResource);
		synchronized (this.entry.repository) {
			RepositoryConnection rc = null;
			try {
				rc = this.entry.repository.getConnection();
				rc.begin();
				ValueFactory vf = this.entry.repository.getValueFactory();

				//Remove homecontext and remove inverse relation cache.
				RepositoryResult<Statement> iter = rc.getStatements(resourceURI, RepositoryProperties.homeContext, null, false, entry.getSesameEntryURI());
				while (iter.hasNext()) {
					Statement statement = iter.next();
					URI sourceEntryURI = URI.create(statement.getObject().stringValue());
					EntryImpl sourceEntry = (EntryImpl) this.entry.getRepositoryManager().getContextManager().getEntry(sourceEntryURI);
					if (sourceEntry != null) {
						sourceEntry.removeRelationSynchronized(statement, rc);
					}
					rc.remove(statement, entry.getSesameEntryURI());
				}
				iter.close();

				//Add new homecontext and add inverse relational cache
				if (context != null) {
					Statement newStatement = vf.createStatement(resourceURI, RepositoryProperties.homeContext, ((EntryImpl) context.getEntry()).getSesameEntryURI(), entry.getSesameEntryURI());
					rc.add(newStatement);
					((EntryImpl) context.getEntry()).addRelationSynchronized(newStatement, rc);
				}
				rc.commit();
				entry.getRepositoryManager().fireRepositoryEvent(new RepositoryEventObject(entry, RepositoryEvent.ResourceUpdated));
			} catch (org.eclipse.rdf4j.repository.RepositoryException e) {
				log.error(e.getMessage(), e);
				try {
					rc.rollback();
				} catch (org.eclipse.rdf4j.repository.RepositoryException e1) {
					log.error(e.getMessage(), e1);
				}
			} finally {
				try {
					rc.close();
					//We poke in the internals of entryImpl, to notify that it has relations for later setGraph calls to work
					entry.invRelations = true;
				} catch (org.eclipse.rdf4j.repository.RepositoryException e) {
					log.error(e.getMessage());
				}
				this.homeContext = context.getEntry().getEntryURI();
			}
		}
		return true;
	}

	/**
	 * Adds a user to the group through adding its URI to the group's list of URIs.
	 *
	 * @param user The user to add to the group
	 */
	public void addMember(User user) {
		addChild(user.getEntry().getEntryURI());
	}

	/**
	 * Removes a user from the group.
	 *
	 * @param user The user to remove
	 * @return true if the removing was successful, false otherwise
	 */
	public boolean removeMember(User user) {
		return removeChild(user.getEntry().getEntryURI());
	}

	/**
	 * Tells whether a user is a member of the group.
	 *
	 * @param user the user to test if is group member
	 * @return true if the user is a member, false otherwise
	 */
	public boolean isMember(User user) {
		if (user == null) {
			return false;
		}

		List<URI> children = getChildren();
		Entry userEntry = user.getEntry();
		URI userEntryURI = userEntry.getEntryURI();

		return children.contains(userEntryURI);
	}

	/**
	 * Returns a list of all members URIs (entryURIs)
	 *
	 * @return a list of all members URIs
	 */
	public List<URI> memberUris() {
		return getChildren();
	}

	/**
	 * Returns a list of all members
	 *
	 * @return a list of all members
	 */
	public List<User> members() {
		List<User> userList = new Vector<>();
		Iterator<URI> memberUriIterator = memberUris().iterator();
		boolean contentError = false;

		while (memberUriIterator.hasNext()) {
			URI entryURI = memberUriIterator.next();
			try {
				Entry userEntry = entry.getContext().getByEntryURI(entryURI);
				if (userEntry.getGraphType() == GraphType.User) {
					userList.add((User) userEntry.getResource());
				} else {
					contentError = true;
				}
			} catch (NullPointerException e) {
				log.error(e.getMessage());
			}
		}

		if (contentError) {
			log.error("Error in group {} . All members does not seem to be of the type User.", getURI().toString());
		}

		return userList;
	}

	public Vector<URI> setChildren(Vector<URI> children) {
		setChildren(children, true, true);
		return new Vector<>(getChildren());
	}

}
