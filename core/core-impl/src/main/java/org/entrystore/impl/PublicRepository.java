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

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.google.common.collect.Queues;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Model;
import org.eclipse.rdf4j.model.Resource;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.repository.Repository;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.RepositoryException;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.eclipse.rdf4j.sail.memory.MemoryStore;
import org.eclipse.rdf4j.sail.nativerdf.NativeStore;
import org.entrystore.AuthorizationException;
import org.entrystore.Context;
import org.entrystore.ContextManager;
import org.entrystore.Entry;
import org.entrystore.EntryType;
import org.entrystore.GraphType;
import org.entrystore.PrincipalManager;
import org.entrystore.config.Config;
import org.entrystore.repository.RepositoryManager;
import org.entrystore.repository.config.Settings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.net.URI;
import java.util.Date;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentMap;

/**
 * @author Hannes Ebner
 */
public class PublicRepository {
	
	Logger log = LoggerFactory.getLogger(PublicRepository.class);
	
	private boolean rebuilding = false;
	
	private Repository repository;
	
	private RepositoryManager rm;
	
	private PrincipalManager pm;

	private Thread entrySubmitter;

	private final Cache<URI, Entry> postQueue = Caffeine.newBuilder().build();

	private final Queue<Entry> deleteQueue = Queues.newConcurrentLinkedQueue();

	private static final int BATCH_SIZE = 1000;

	public class EntrySubmitter extends Thread {

		@Override
		public void run() {
			while (!interrupted()) {
				postQueue.cleanUp();
				int batchCount = 0;

				if (postQueue.estimatedSize() > 0 || !deleteQueue.isEmpty()) {
					if (!deleteQueue.isEmpty()) {
						Set<Entry> entriesToRemove = new HashSet<>();
						synchronized (deleteQueue) {
							while (batchCount < BATCH_SIZE) {
								Entry e = deleteQueue.poll();
								if (e == null) {
									break;
								}
								entriesToRemove.add(e);
								batchCount++;
							}
						}
						if (batchCount > 0) {
							log.info("Removing " + batchCount + " entries from Public Repository, " + deleteQueue.size() + " entries remaining in removal queue");
							removeEntries(entriesToRemove);
						}
					}
					if (postQueue.estimatedSize() > 0) {
						Set<Entry> entriesToUpdate = new HashSet<>();
						synchronized (postQueue) {
							ConcurrentMap<URI, Entry> postQueueMap = postQueue.asMap();
							Iterator<URI> it = postQueueMap.keySet().iterator();
							while (batchCount < BATCH_SIZE && it.hasNext()) {
								URI key = it.next();
								Entry entry = postQueueMap.get(key);
								postQueueMap.remove(key, entry);
								if (entry == null) {
									log.warn("Value for key " + key + " is null in Public Repository submit queue");
								}
								entriesToUpdate.add(entry);
								batchCount++;
							}
						}
						postQueue.cleanUp();
						log.info("Sending " + entriesToUpdate.size() + " entries for update in Public Repository, " + postQueue.estimatedSize() + " entries remaining in post queue");
						updateEntries(entriesToUpdate);
					}
				} else {
					try {
						Thread.sleep(10000);
					} catch (InterruptedException ie) {
						log.info("Public Repository submitter got interrupted, shutting down submitter thread");
						return;
					}
				}
			}
		}

	}

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
				((RepositoryManagerImpl) rm).checkAndUpgradeNativeStore(path, indexes);
				log.info("Public repository: using Native Store at {} with indexes {}", path, indexes);
				
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
			repository.init();
		} catch (RepositoryException e) {
			log.error(e.getMessage());
		}

		if (getTripleCount() == 0 ||
				"on".equalsIgnoreCase(config.getString(Settings.REPOSITORY_PUBLIC_REBUILD_ON_STARTUP, "off"))) {
			rebuildRepository();
		}

		entrySubmitter = new PublicRepository.EntrySubmitter();
		entrySubmitter.start();
	}
	
	public RepositoryConnection getConnection() {
		try {
			return repository.getConnection();
		} catch (RepositoryException e) {
			log.error(e.getMessage());
		}
		return null;
	}

	public void enqueue(Entry entry) {
		URI entryURI = entry.getEntryURI();
		synchronized (postQueue) {
			log.info("Adding document to update queue: " + entryURI);
			postQueue.put(entryURI, entry);
		}
	}

	public void remove(Entry entry) {
		URI entryURI = entry.getEntryURI();
		synchronized (deleteQueue) {
			log.info("Adding entry to delete queue: " + entryURI);
			deleteQueue.add(entry);
		}
	}

	private void addEntry(Entry e, RepositoryConnection rc) throws RepositoryException {
		if (isAdministrative(e)) {
			return;
		}
		URI currentUser = pm.getAuthenticatedUserURI();
		try {
			pm.setAuthenticatedUserURI(pm.getGuestUser().getURI());
			try {
				ValueFactory vf = repository.getValueFactory();
				IRI contextURI = vf.createIRI(e.getContext().getURI().toString());

				// entry
				/* DEACTIVATED
				Graph entryGraph = e.getGraph();
				URI entryNG = vf.createIRI(e.getEntryURI().toString());
				if (entryGraph != null) {
					rc.add(entryGraph, entryNG, contextURI);
				}
				*/

				// metadata
				Model mdGraph = null;
				IRI mdNG = null;
				if (e.getLocalMetadata() != null) {
					mdGraph = e.getLocalMetadata().getGraph();
					mdNG = vf.createIRI(e.getLocalMetadataURI().toString());
				}

				// ext metadata
				Model extMdGraph = null;
				IRI extMdNG = null;
				if (e.getCachedExternalMetadata() != null) {
					extMdGraph = e.getCachedExternalMetadata().getGraph();
					extMdNG = vf.createIRI(e.getCachedExternalMetadataURI().toString());
				}

				// resource
				Model resGraph = null;
				IRI resNG = null;
				if (GraphType.Graph.equals(e.getGraphType()) && EntryType.Local.equals(e.getEntryType())) {
					resGraph = (Model) e.getResource();
					resNG = vf.createIRI(e.getResourceURI().toString());
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
			} catch (AuthorizationException ae) {
			}
		} finally {
			pm.setAuthenticatedUserURI(currentUser);
		}
	}

	private void updateEntries(Set<Entry> entries) {
		URI currentUser = pm.getAuthenticatedUserURI();
		try {
			pm.setAuthenticatedUserURI(pm.getGuestUser().getURI());
			synchronized (repository) {
				RepositoryConnection rc = null;
				try {
					rc = repository.getConnection();
					rc.begin();
					for (Entry e : entries) {
						updateEntry(e, rc);
					}
					rc.commit();
				} catch (RepositoryException re) {
					try {
						if (rc != null) {
							rc.rollback();
						}
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
		} finally {
			pm.setAuthenticatedUserURI(currentUser);
		}
	}

	private void updateEntry(Entry e, RepositoryConnection rc) throws RepositoryException {
		if (e == null) {
			return;
		}
		
		// If entry is ResourceType.Context we update all its
		// entries, just in case the ACL has changed
		if (GraphType.Context.equals(e.getGraphType()) && EntryType.Local.equals(e.getEntryType())) {
			String contextURI = e.getResourceURI().toString();
			String id = contextURI.substring(contextURI.lastIndexOf("/") + 1);
			Context context = rm.getContextManager().getContext(id);
			if (context != null) {
				Set<URI> entries = context.getEntries();
				for (URI entryURI : entries) {
					if (entryURI != null) {
						try {
							updateEntry(rm.getContextManager().getEntry(entryURI), rc);
						} catch (AuthorizationException ae) {
							continue;
						}
					}
				}
			}
		} else {
			log.debug("Processing entry: " + e.getEntryURI());
			removeEntry(e, rc);
			addEntry(e, rc);
		}
	}

	private void removeEntries(Set<Entry> entries) {
		URI currentUser = pm.getAuthenticatedUserURI();
		try {
			pm.setAuthenticatedUserURI(pm.getGuestUser().getURI());
			synchronized (repository) {
				RepositoryConnection rc = null;
				try {
					rc = repository.getConnection();
					rc.begin();
					for (Entry e : entries) {
						removeEntry(e, rc);
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
		} finally {
			pm.setAuthenticatedUserURI(currentUser);
		}
	}

	private void removeEntry(Entry e, RepositoryConnection rc) throws RepositoryException {
		PrincipalManager pm = e.getRepositoryManager().getPrincipalManager();
		URI currentUser = pm.getAuthenticatedUserURI();
		try {
			// we need to be admin, in case the ACL has become
			// more restrictive since adding the entry
			pm.setAuthenticatedUserURI(pm.getAdminUser().getURI());
			ValueFactory vf = repository.getValueFactory();
			IRI contextURI = vf.createIRI(e.getContext().getURI().toString());

			IRI entryNG = vf.createIRI(e.getEntryURI().toString());
			IRI mdNG = vf.createIRI(e.getLocalMetadataURI().toString());
			IRI resNG = vf.createIRI(e.getResourceURI().toString());
			IRI extMdNG = null;

			if (e.getExternalMetadataURI() != null) {
				extMdNG = vf.createIRI(e.getCachedExternalMetadataURI().toString());
			}

			if (extMdNG != null) {
				rc.remove(rc.getStatements((Resource) null, (IRI) null, (Value) null, false, entryNG, mdNG, extMdNG, resNG), contextURI, entryNG, mdNG, extMdNG, resNG);
			} else {
				rc.remove(rc.getStatements((Resource) null, (IRI) null, (Value) null, false, entryNG, mdNG, extMdNG, resNG), contextURI, entryNG, mdNG, resNG);
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

		synchronized (repository) {
			RepositoryConnection rc = null;
			try {
				rc = repository.getConnection();
				Date before = new Date();
				rc.begin();
				rc.clear();
				log.info("Clearing public repository took " + (new Date().getTime() - before.getTime()) + " ms");

				ContextManager cm = rm.getContextManager();
				Set<URI> contexts = cm.getEntries();

				for (URI contextURI : contexts) {
					String id = contextURI.toString().substring(contextURI.toString().lastIndexOf("/") + 1);
					Context context = cm.getContext(id);
					if (context != null) {
						log.info("Adding context " + contextURI + " to public repository");
						before = new Date();
						Set<URI> entries = context.getEntries();
						log.info("Fetching entries took " + (new Date().getTime() - before.getTime()) + " ms");
						before = new Date();
						Date timeTracker = new Date();
						long publicEntryCount = 0;
						long processedCount = 0;
						for (URI entryURI : entries) {
							if (entryURI != null) {
								processedCount++;
								if ((new Date().getTime() - timeTracker.getTime()) > 60000) {
									if (processedCount > 0) {
										log.debug("Average time per entry after " + (new Date().getTime() - before.getTime()) + " ms: " + ((new Date().getTime() - before.getTime()) / processedCount) + " ms");
										timeTracker = new Date();
									}
								}
								try {
									Entry entry = cm.getEntry(entryURI);
									if (entry == null) {
										continue;
									}
									addEntry(entry, rc);
									publicEntryCount++;
								} catch (AuthorizationException ae) {
									continue;
								}
							}
						}
						log.info("Added " + publicEntryCount + " entries to public repository");
						log.info("Total time for context: " + (new Date().getTime() - before.getTime()) + " ms");
						if (entries.size() > 0) {
							log.debug("Total average time per entry: " + ((new Date().getTime() - before.getTime()) / entries.size()) + " ms");
						}
						log.info("Done processing context " + contextURI);
					}
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
				try {
					rc.close();
				} catch (RepositoryException re) {
					log.error(re.getMessage());
				}
				log.info("Rebuild of public repository complete");
				log.info("Number of triples in public repository: " + getTripleCount());
				rebuilding = false;
			}
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
		if (entrySubmitter != null) {
			entrySubmitter.interrupt();
		}

		try {
			repository.shutDown();
		} catch (RepositoryException e) {
			log.error("Error when shutting down public repository: " + e.getMessage());
		}
	}

}