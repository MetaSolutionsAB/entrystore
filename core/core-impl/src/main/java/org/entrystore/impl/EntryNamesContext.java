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

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Literal;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.RepositoryException;
import org.entrystore.Entry;
import org.entrystore.PrincipalManager;
import org.entrystore.PrincipalManager.AccessProperty;
import org.entrystore.repository.RepositoryEvent;
import org.entrystore.repository.RepositoryEventObject;

import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Set;


/**
 * Creates a 
 * @author Olov Wikberg, IML Umeå University
 * @author matthias
 *
 */
public class EntryNamesContext extends ContextImpl {
	private static final Log log = LogFactory.getLog(EntryNamesContext.class);

	HashMap<String, URI> names2EntryURI;
	HashMap<URI, String> entryURI2Name;

	
	/**
	 * Creates a principal manager
	 * @param entry this principal managers entry
	 * @param uri this principal managers URI 
	 * @param cache
	 */
	public EntryNamesContext(EntryImpl entry, String uri, SoftCache cache) {
		super(entry, uri, cache);
	}

	protected String getName(URI entryURI) {
		if(names2EntryURI == null) {
			loadNameIndex();
		}

		return entryURI2Name.get(entryURI);
	}

	public Entry getEntryByName(String name) {
		if(names2EntryURI == null) {
			loadNameIndex();
		}

		return getByEntryURI(names2EntryURI.get(name));
	}

    protected void removeFromIndex(EntryImpl entry, RepositoryConnection rc) throws RepositoryException {
        super.removeFromIndex(entry, rc);
        if(names2EntryURI == null) {
            loadNameIndex();
        }

        URI entryURI = entry.getEntryURI();
        IRI cURI = rc.getValueFactory().createIRI(entryURI.toString());

        if (entryURI2Name.containsKey(entryURI)) {
            String oldName = entryURI2Name.get(entryURI);
            entryURI2Name.remove(entryURI);
            names2EntryURI.remove(oldName);
            Literal oldAliasLiteral = rc.getValueFactory().createLiteral(oldName);
            rc.remove(cURI, RepositoryProperties.alias, oldAliasLiteral, this.resourceURI);
        }
    }

    protected boolean setEntryName(URI entryURI, String newName) {
        if (entryURI == null) {
			throw new IllegalArgumentException("Arguments must not be null");
		}
		
		Entry forEntry = getByEntryURI(entryURI);
		if (forEntry == null) {
			//Entry must exist to allow Name to be set.
			throw new org.entrystore.repository.RepositoryException("Unable to set name for non-existing entry");
		}
		
		PrincipalManager pm = this.entry.getRepositoryManager().getPrincipalManager();
		if (pm != null) {
			pm.checkAuthenticatedUserAuthorized(forEntry, AccessProperty.WriteResource);
		}
		if(names2EntryURI == null) {
			loadNameIndex();
		}

		if (newName != null && names2EntryURI.containsKey(newName)) {
			if (newName.equals(entryURI2Name.get(entryURI))) {
				return true;
			} else {
				log.error("The name "+newName+" is already in use.");
				return false;
			}
		}

		try {
			synchronized (this.entry.repository) {
				RepositoryConnection rc = entry.repository.getConnection();
				try {
					ValueFactory vf = entry.repository.getValueFactory();
					rc.begin();
					IRI cURI = vf.createIRI(entryURI.toString());
					if (entryURI2Name.containsKey(entryURI)) {
						String oldName = entryURI2Name.get(entryURI);
						entryURI2Name.remove(entryURI);
						names2EntryURI.remove(oldName);
						Literal oldAliasLiteral = vf.createLiteral(oldName);
						rc.remove(cURI, RepositoryProperties.alias, oldAliasLiteral, this.resourceURI);
					}
					if (newName != null) {
						names2EntryURI.put(newName, entryURI);
						entryURI2Name.put(entryURI, newName);
						Literal nameLiteral = vf.createLiteral(newName);
						rc.add(cURI, RepositoryProperties.alias, nameLiteral, this.resourceURI);
						this.entry.updateModifiedDateSynchronized(rc, vf);
						((EntryImpl) forEntry).updateModifiedDateSynchronized(rc, vf);
					}
					rc.commit();
					entry.getRepositoryManager().fireRepositoryEvent(new RepositoryEventObject(entry, RepositoryEvent.ResourceUpdated));
					entry.getRepositoryManager().fireRepositoryEvent(new RepositoryEventObject(forEntry, RepositoryEvent.ResourceUpdated));
					return true;
				} catch (Exception e) {
					rc.rollback();
					throw new org.entrystore.repository.RepositoryException("Error in connection to repository", e);
				} finally {
					log.info("Successfully set the name " + newName + " for entry with URI: " + entryURI);
					rc.close();
				}
			}
		} catch (RepositoryException e) {
			throw new org.entrystore.repository.RepositoryException("Cannot connect to repository", e);
		}
	}

	private void loadNameIndex() {
		try {
			synchronized (this.entry.repository) {
				if (names2EntryURI != null) {
					return;
				}
				RepositoryConnection rc = entry.repository.getConnection();
				try {
					names2EntryURI = new HashMap<String, URI>();
					entryURI2Name = new HashMap<URI, String>();
					List<Statement> statements = rc.getStatements(null, RepositoryProperties.alias, null, false, this.resourceURI).asList();
					for (Statement statement : statements) {
						try {
							URI entryURI = URI.create(statement.getSubject().stringValue());
							String name = statement.getObject().stringValue();
							names2EntryURI.put(name, entryURI);
							entryURI2Name.put(entryURI, name);
						} catch (Exception e) {
							log.error(e.getMessage());
							throw new org.entrystore.repository.RepositoryException("Error in connection to repository", e);
						}
					}
				} finally {
					rc.close();
				}
			}
		} catch (RepositoryException e) {
			log.error(e.getMessage());
			throw new org.entrystore.repository.RepositoryException("Cannot connect to repository", e);
		}
	}
	
	protected Set<String> getEntryNames() {
		checkAccess(null, AccessProperty.ReadResource);
		if(names2EntryURI == null) {
			loadNameIndex();
		}

		return names2EntryURI.keySet();
	}

}