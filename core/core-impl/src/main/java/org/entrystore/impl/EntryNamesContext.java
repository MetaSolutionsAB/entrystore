/*
 * Copyright (c) 2007-2026 MetaSolutions AB
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

import lombok.extern.slf4j.Slf4j;
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
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;


/**
 * A context whose entries have unique names, such as the users and groups of {@code _principals} and the
 * contexts of {@code _contexts}.
 *
 * <p>The name index is published through one volatile field, and only once it is filled, so a reader either
 * loads it or sees all of it. It is loaded and written inside the {@code entry.repository} monitor.
 *
 * @author Olov Wikberg, IML Umeå University
 * @author matthias
 */
@Slf4j
public class EntryNamesContext extends ContextImpl {

	private record NameIndex(ConcurrentMap<String, URI> byName, ConcurrentMap<URI, String> byEntryURI) {}

	private volatile NameIndex nameIndex;

	/** Run between filling the name index and publishing it, or null; see {@link ContextImpl#indexScanHookForTests}. */
	volatile Runnable nameScanHookForTests;

	public EntryNamesContext(EntryImpl entry, String uri, SoftCache cache) {
		super(entry, uri, cache);
	}

	protected String getName(URI entryURI) {
		return entryURI == null ? null : nameIndex().byEntryURI().get(entryURI);
	}

	public Entry getEntryByName(String name) {
		return name == null ? null : getByEntryURI(nameIndex().byName().get(name));
	}

	protected void removeFromIndex(EntryImpl entry, RepositoryConnection rc, java.util.List<IndexOp> txOps) throws RepositoryException {
		super.removeFromIndex(entry, rc, txOps);
		NameIndex index = nameIndex();

		URI entryURI = entry.getEntryURI();
		IRI cURI = rc.getValueFactory().createIRI(entryURI.toString());

		String oldName = index.byEntryURI().remove(entryURI);
		if (oldName != null) {
			index.byName().remove(oldName);
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
		NameIndex index = nameIndex();

		if (newName != null && index.byName().containsKey(newName)) {
			if (newName.equals(index.byEntryURI().get(entryURI))) {
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
					String oldName = index.byEntryURI().remove(entryURI);
					if (oldName != null) {
						index.byName().remove(oldName);
						Literal oldAliasLiteral = vf.createLiteral(oldName);
						rc.remove(cURI, RepositoryProperties.alias, oldAliasLiteral, this.resourceURI);
					}
					if (newName != null) {
						index.byName().put(newName, entryURI);
						index.byEntryURI().put(entryURI, newName);
						Literal nameLiteral = vf.createLiteral(newName);
						rc.add(cURI, RepositoryProperties.alias, nameLiteral, this.resourceURI);
						this.entry.updateModifiedDateSynchronized(rc, vf);
						((EntryImpl) forEntry).updateModifiedDateSynchronized(rc, vf);
					}
					rc.commit();
					log.info("Successfully set the name {} for entry with URI: {}", newName, entryURI);
					entry.getRepositoryManager().fireRepositoryEvent(new RepositoryEventObject(entry, RepositoryEvent.ResourceUpdated));
					entry.getRepositoryManager().fireRepositoryEvent(new RepositoryEventObject(forEntry, RepositoryEvent.ResourceUpdated));
					return true;
				} catch (Exception e) {
					rc.rollback();
					throw new org.entrystore.repository.RepositoryException(
							"Unable to set the name " + newName + " for entry " + entryURI, e);
				} finally {
					rc.close();
				}
			}
		} catch (RepositoryException e) {
			throw new org.entrystore.repository.RepositoryException("Cannot connect to repository", e);
		}
	}

	private NameIndex nameIndex() {
		NameIndex index = nameIndex;
		return index != null ? index : loadNameIndex();
	}

	/** An alias statement that cannot be read is logged and left out: failing here would fail every name lookup. */
	private NameIndex loadNameIndex() {
		try {
			synchronized (this.entry.repository) {
				NameIndex published = nameIndex;
				if (published != null) {
					return published;
				}
				NameIndex loaded = new NameIndex(new ConcurrentHashMap<>(), new ConcurrentHashMap<>());
				try (RepositoryConnection rc = entry.repository.getConnection()) {
					List<Statement> statements = rc.getStatements(null, RepositoryProperties.alias, null, false, this.resourceURI).asList();
					for (Statement statement : statements) {
						try {
							URI entryURI = URI.create(statement.getSubject().stringValue());
							String name = statement.getObject().stringValue();
							loaded.byName().put(name, entryURI);
							loaded.byEntryURI().put(entryURI, name);
						} catch (Exception e) {
							log.error("Unable to read name statement {} of context {}", statement, this.resourceURI, e);
						}
					}
				}
				Runnable hook = nameScanHookForTests;
				if (hook != null) {
					hook.run();
				}
				nameIndex = loaded;
				return loaded;
			}
		} catch (RepositoryException e) {
			log.error(e.getMessage());
			throw new org.entrystore.repository.RepositoryException("Cannot connect to repository", e);
		}
	}

	protected Set<String> getEntryNames() {
		checkAccess(null, AccessProperty.ReadResource);
		return nameIndex().byName().keySet();
	}

}
