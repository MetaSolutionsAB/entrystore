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

import org.entrystore.AuthorizationException;
import org.entrystore.Context;
import org.entrystore.ContextManager;
import org.entrystore.Entry;
import org.entrystore.EntryType;
import org.entrystore.GraphType;
import org.entrystore.PrincipalManager;
import org.entrystore.PrincipalManager.AccessProperty;
import org.entrystore.config.Config;
import org.entrystore.repository.RepositoryManager;
import org.entrystore.repository.config.Settings;
import org.openrdf.model.Graph;
import org.openrdf.model.Resource;
import org.openrdf.model.URI;
import org.openrdf.model.Value;
import org.openrdf.model.ValueFactory;
import org.openrdf.repository.Repository;
import org.openrdf.repository.RepositoryConnection;
import org.openrdf.repository.RepositoryException;
import org.openrdf.repository.sail.SailRepository;
import org.openrdf.sail.memory.MemoryStore;
import org.openrdf.sail.nativerdf.NativeStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.Set;


/**
 * @author Hannes Ebner
 */
public class PublicRepository {
	
	Logger log = LoggerFactory.getLogger(PublicRepository.class);
	
	private boolean rebuilding = false;
	
	private Repository repository;
	
	private RepositoryManager rm;
	
	private PrincipalManager pm;
	
	public PublicRepository(RepositoryManager rm) {
		this.rm = rm;
		this.pm = rm.getPrincipalManager();
		Config config = rm.getConfiguration();

		String storeType = config.getString(Settings.REPOSITORY_PUBLIC_TYPE, "memory").trim();
		log.info("Public repository type: " + storeType);
		
		if (storeType.equalsIgnoreCase("memory")) {
			this.repository = new SailRepository(new MemoryStore());
		} else if (storeType.equalsIgnoreCase("native")) {
			if (!config.containsKey(Settings.REPOSITORY_PUBLIC_PATH)) {
				log.error("Incomplete configuration of public repository");
			} else {
				File path = new File(config.getURI(Settings.REPOSITORY_PUBLIC_PATH));
				String indexes = config.getString(Settings.REPOSITORY_PUBLIC_INDEXES);
				
				log.info("Public repository path: " + path);
				log.info("Public repository indexes: " + indexes);
				
				NativeStore store = null;
				if (indexes != null) {
					store = new NativeStore(path, indexes);
				} else {
					store = new NativeStore(path);
				}
				if (store != null) {
					this.repository = new SailRepository(store);
				}
			}
		}
		
		if (this.repository == null) {
			log.error("Failed to create public repository");
			return;
		}

		try {
			repository.initialize();
		} catch (RepositoryException e) {
			log.error(e.getMessage());
		}
		
		if (getTripleCount() == 0 ||
				"on".equalsIgnoreCase(config.getString(Settings.REPOSITORY_PUBLIC_REBUILD_ON_STARTUP, "off"))) {
			rebuildRepository();
		}
	}
	
	public RepositoryConnection getConnection() {
		try {
			return repository.getConnection();
		} catch (RepositoryException e) {
			log.error(e.getMessage());
		}
		return null;
	}

	public void addEntry(Entry e) {
		if (isAdministrative(e)) {
			return;
		}
		pm.setAuthenticatedUserURI(pm.getGuestUser().getURI());
		try {
			ValueFactory vf = repository.getValueFactory();
			URI contextURI = vf.createURI(e.getContext().getURI().toString());

			// entry
			Graph entryGraph = e.getGraph();
			URI entryNG = vf.createURI(e.getEntryURI().toString());

			// metadata
			Graph mdGraph = null;
			URI mdNG = null;
			if (e.getLocalMetadata() != null) {
				mdGraph = e.getLocalMetadata().getGraph();
				mdNG = vf.createURI(e.getLocalMetadataURI().toString());
			}

			// ext metadata
			Graph extMdGraph = null;
			URI extMdNG = null;
			if (e.getCachedExternalMetadata() != null) {
				extMdGraph = e.getCachedExternalMetadata().getGraph();
				extMdNG = vf.createURI(e.getCachedExternalMetadataURI().toString()); 
			}

			// resource
			Graph resGraph = null;
			URI resNG = null;
			if (GraphType.Graph.equals(e.getGraphType()) && EntryType.Local.equals(e.getEntryType())) {
				resGraph = (Graph) e.getResource();
				resNG = vf.createURI(e.getResourceURI().toString());
			}

			synchronized (repository) {
				RepositoryConnection rc = null;
				try {
					rc = repository.getConnection();
					rc.begin();
					if (entryGraph != null) {
						rc.add(entryGraph, entryNG, contextURI);
					}
					if (mdGraph != null) {
						rc.add(mdGraph, mdNG, contextURI);
					}
					if (extMdGraph != null) {
						rc.add(extMdGraph, extMdNG, contextURI);
					}
					if (resGraph != null) {
						rc.add(resGraph, resNG, contextURI);
					}
					rc.commit();
				} catch (RepositoryException re) {
					try {
						rc.rollback();
					} catch (RepositoryException re1) {
						log.error(re1.getMessage());
					}
					log.error(re.getMessage());
				} finally {
					if (rc != null) {
						try {
							rc.close();
						} catch (RepositoryException re2) {
							log.error(re2.getMessage());
						}
					}
				}
			}
		} catch (AuthorizationException ae) {}
	}
	
	public void updateEntry(Entry e) {
		if (e == null) {
			return;
		}
		
		// If entry is ResourceType.Context we update all its
		// entries, just in case the ACL has changed
		if (GraphType.Context.equals(e.getGraphType()) && EntryType.Local.equals(e.getEntryType())) {
			
			// TODO needs to be tested
			
			String contextURI = e.getResourceURI().toString();
			String id = contextURI.substring(contextURI.lastIndexOf("/") + 1);
			Context context = rm.getContextManager().getContext(id);
			if (context != null) {
				Set<java.net.URI> entries = context.getEntries();
				for (java.net.URI entryURI : entries) {
					if (entryURI != null) {
						try {
							updateEntry(rm.getContextManager().getEntry(entryURI));
						} catch (AuthorizationException ae) {
							continue;
						}
					}
				}
			}			
		} else {
			removeEntry(e);
			addEntry(e);
		}
	}
	
	public void removeEntry(Entry e) {
		PrincipalManager pm = e.getRepositoryManager().getPrincipalManager();
		java.net.URI currentUser = pm.getAuthenticatedUserURI();
		pm.setAuthenticatedUserURI(pm.getAdminUser().getURI());
		try {
			ValueFactory vf = repository.getValueFactory();
			URI contextURI = vf.createURI(e.getContext().getURI().toString());

			URI entryNG = vf.createURI(e.getEntryURI().toString());
			URI mdNG = vf.createURI(e.getLocalMetadataURI().toString());
			URI resNG = vf.createURI(e.getResourceURI().toString());
			URI extMdNG = null;
			if (e.getExternalMetadataURI() != null) {
				extMdNG = vf.createURI(e.getCachedExternalMetadataURI().toString());
			}

			synchronized (repository) {
				RepositoryConnection rc = null;
				try {
					rc = repository.getConnection();
					rc.begin();
					if (extMdNG != null) {
						rc.remove(rc.getStatements((Resource) null, (URI) null, (Value) null, false, entryNG, mdNG, extMdNG, resNG), contextURI, entryNG, mdNG, extMdNG, resNG);
					} else {
						rc.remove(rc.getStatements((Resource) null, (URI) null, (Value) null, false, entryNG, mdNG, extMdNG, resNG), contextURI, entryNG, mdNG, resNG);
					}
					rc.commit();
				} catch (RepositoryException re) {
					log.error(re.getMessage());
				} finally {
					if (rc != null) {
						try {
							rc.close();
						} catch (RepositoryException re) {
							log.error(re.getMessage());
						}
					}
				}
			}
		} finally {
			pm.setAuthenticatedUserURI(currentUser);
		}
	}

	public void rebuildRepository() {
		synchronized (repository) {
			if (rebuilding) {
				log.warn("The public repository is already being rebuilt: ignoring additional rebuilding requests");
				return;
			} else {
				rebuilding = true;
			}
		}

		log.info("Rebuilding public repository");

		try {
			RepositoryConnection rc = repository.getConnection();
			rc.begin();
			rc.clear();
			rc.commit();
			rc.close();

			ContextManager cm = rm.getContextManager();
			Set<java.net.URI> contexts = cm.getEntries();

			for (java.net.URI contextURI : contexts) {
				String id = contextURI.toString().substring(contextURI.toString().lastIndexOf("/") + 1);
				Context context = cm.getContext(id);
				if (context != null) {
					log.info("Adding context " + contextURI + " to public repository");
					Set<java.net.URI> entries = context.getEntries();
					for (java.net.URI entryURI : entries) {
						if (entryURI != null) {
							try {
								Entry entry = cm.getEntry(entryURI);
								if (entry == null) {
									continue;
								}
								addEntry(entry);
							} catch (AuthorizationException ae) {
								continue;
							}
						}
					}
					log.info("Done adding context " + contextURI);
				}
			}
		} catch (RepositoryException re) {
			log.error(re.getMessage());
		} finally {
			log.info("Rebuild of public repository complete");
			log.info("Number of triples in public repository: " + getTripleCount());
			rebuilding = false;
		}
	}
	
	private boolean isAdministrative(Entry e) {
		GraphType gt = e.getGraphType();
		if (GraphType.Graph.equals(gt) ||
				GraphType.String.equals(gt) ||
				GraphType.None.equals(gt) ||
				GraphType.List.equals(gt)) {
			return false;
		}
		return true;
	}

	private boolean isPublic(Entry e) {
		boolean result = false;
		PrincipalManager pm = e.getRepositoryManager().getPrincipalManager();
		pm.setAuthenticatedUserURI(pm.getGuestUser().getURI());
		try {
			pm.checkAuthenticatedUserAuthorized(e, AccessProperty.ReadMetadata);
			result = true;
		} catch (AuthorizationException ae) {}
		pm.setAuthenticatedUserURI(pm.getAdminUser().getURI());
		return result;
	}

	public long getTripleCount() {
		long amountTriples = 0;
		RepositoryConnection rc = null;
		try {
			rc = repository.getConnection();
			amountTriples = rc.size();
		} catch (RepositoryException re) {
			log.error(re.getMessage());
		} finally {
			if (rc != null) {
				try {
					rc.close();
				} catch (RepositoryException e) {
					log.error(e.getMessage());
				}
			}
		}
		return amountTriples;
	}
	
	public void shutdown() {
		try {
			repository.shutDown();
		} catch (RepositoryException e) {}
	}

}