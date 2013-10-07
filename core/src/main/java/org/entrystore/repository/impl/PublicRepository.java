package org.entrystore.repository.impl;

import java.io.File;
import java.util.Set;

import org.entrystore.repository.AuthorizationException;
import org.entrystore.repository.BuiltinType;
import org.entrystore.repository.Context;
import org.entrystore.repository.ContextManager;
import org.entrystore.repository.Entry;
import org.entrystore.repository.LocationType;
import org.entrystore.repository.PrincipalManager;
import org.entrystore.repository.RepositoryManager;
import org.entrystore.repository.PrincipalManager.AccessProperty;
import org.entrystore.repository.config.Config;
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
			if (BuiltinType.Graph.equals(e.getBuiltinType()) && LocationType.Local.equals(e.getLocationType())) {
				resGraph = (Graph) e.getResource();
				resNG = vf.createURI(e.getResourceURI().toString());
			}

			synchronized (repository) {
				RepositoryConnection rc = null;
				try {
					rc = repository.getConnection();
					rc.setAutoCommit(false);
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
		
		// If entry is BuiltinType.Context we update all its
		// entries, just in case the ACL has changed
		if (BuiltinType.Context.equals(e.getBuiltinType()) && LocationType.Local.equals(e.getLocationType())) {
			
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
					rc.setAutoCommit(false);
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
				log.warn("The public repository is already being rebuilt: ignoring additional rebuilding request");
				return;
			} else {
				rebuilding = true;
			}
		}

		log.info("Rebuilding public repository");
		
		try {
			RepositoryConnection rc = repository.getConnection();
			rc.clear();
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