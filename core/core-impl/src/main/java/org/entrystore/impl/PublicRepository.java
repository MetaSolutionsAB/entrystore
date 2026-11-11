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

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.google.common.collect.Queues;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Model;
import org.eclipse.rdf4j.model.Resource;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.repository.Repository;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.RepositoryException;
import org.eclipse.rdf4j.repository.RepositoryResult;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.eclipse.rdf4j.sail.memory.MemoryStore;
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
import org.entrystore.repository.util.URISplit;

import java.io.File;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentMap;
import java.util.function.BiConsumer;

/**
 * The public entries of all contexts, for the public SPARQL endpoint. Each entry's statements are stored in the
 * entry's own graphs (metadata, cached external metadata, resource) and in its context's graph, so the context graph
 * holds the union of the context's public entry graphs.
 *
 * @author Hannes Ebner
 */
@Slf4j
public class PublicRepository {

	private final Cache<URI, Entry> postQueue = Caffeine.newBuilder().build();

	private final Queue<Entry> deleteQueue = Queues.newConcurrentLinkedQueue();

	private final RepositoryManager rm;

	private final PrincipalManager pm;

	private boolean rebuilding = false;
	
	private Repository repository;

	Thread entrySubmitter;
						
	// Wakes the EntrySubmitter when an entry is enqueued so it does not have to wait out its
	// idle timeout. The 10 s timeout on wait() is defense against spurious wakeups (per
	// Object.wait spec) and a heartbeat for programming errors elsewhere; in steady state the
	// submitter wakes within microseconds of an enqueue. Lost-notify is prevented by the
	// recheck-under-lock pattern in run(): the producer (enqueue/remove → signalSubmitter)
	// holds the same queueSignal monitor while calling notifyAll, so a notify that arrived
	// during the consumer's outer emptiness check is observed via the now-non-empty queue
	// when the recheck-under-lock runs, instead of being delivered to a wait that hasn't
	// started.
	private final Object queueSignal = new Object();

	private static final int BATCH_SIZE = 1000;

	/** Statements of an entry read and removed at a time; see removeEntry. */
	static final int REMOVAL_CHUNK_SIZE = 10_000;

	private static final long IDLE_TIMEOUT_MS = 10_000;

	private static final long FAILURE_BACKOFF_BASE_MS = 1_000;

	private static final long FAILURE_BACKOFF_MAX_MS = 60_000;

	public class EntrySubmitter extends Thread {

		/**
		 * Sends queued removals and updates until interrupted. A RuntimeException from an iteration does not end the
		 * thread, which would stop public-repository mirroring until a restart; the entries it could not write are
		 * dropped and the next iteration waits for a backoff that grows with each consecutive failure
		 * ({@link PublicRepository#backoffMillis}). An Error does end it, and is logged. The backoff is a sleep rather
		 * than a wait on {@code queueSignal}, so new work cannot end it: entries queued meanwhile wait for the next
		 * attempt instead of being sent into a failing one. Only an iteration that writes entries without throwing ends
		 * a run of failures.
		 */
		@Override
		public void run() {
			int consecutiveFailures = 0;
			long firstFailureNanos = 0;
			long lastFailureNanos = 0;
			while (!interrupted()) {
				try {
					if (runOnce() && consecutiveFailures > 0) {
						log.info("Public Repository submitter recovered after {} consecutive failures over {} s",
								consecutiveFailures, secondsBetween(firstFailureNanos, lastFailureNanos));
						consecutiveFailures = 0;
					}
				} catch (RuntimeException e) {
					lastFailureNanos = System.nanoTime();
					if (consecutiveFailures == 0) {
						firstFailureNanos = lastFailureNanos;
					}
					consecutiveFailures++;
					long backoffMs = backoffMillis(consecutiveFailures);
					log.error("Public Repository submitter failed (consecutive failures: {}, failing for {} s), "
							+ "backing off {} ms: {}", consecutiveFailures,
							secondsBetween(firstFailureNanos, lastFailureNanos), backoffMs, e.getMessage(), e);
					try {
						Thread.sleep(backoffMs);
					} catch (InterruptedException ie) {
						Thread.currentThread().interrupt();
						log.info("Public Repository submitter got interrupted during failure backoff, "
								+ "shutting down submitter thread");
						break;
					}
				} catch (Error e) {
					log.error("Public Repository submitter stopped, the public repository is not updated until a "
							+ "restart: {}", e.getMessage(), e);
					throw e;
				}
			}
		}

		/**
		 * Sends up to {@code BATCH_SIZE} queued entries, removals then updates in separate transactions, or waits for
		 * work if there is none; returns whether it sent any. Removals stay committed if the updates then fail.
		 */
		private boolean runOnce() {
			postQueue.cleanUp();
			int batchCount = 0;

			// Use asMap().isEmpty() instead of estimatedSize() > 0: Caffeine's estimatedSize() is
			// documented as approximate (per Cache.estimatedSize JavaDoc) and can lag concurrent
			// puts; asMap().isEmpty() defers to the backing ConcurrentHashMap and is authoritative,
			// so the idle branch never sleeps with work already enqueued.
			if (!postQueue.asMap().isEmpty() || !deleteQueue.isEmpty()) {
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
						log.info("Removing {} entries from Public Repository, {} entries remaining in removal queue",
								batchCount, deleteQueue.size());
						try {
							removeEntries(entriesToRemove);
						} catch (RuntimeException e) {
							// Surface which entries were lost; the outer fault-isolation catch keeps the
							// thread alive but otherwise discards this information.
							log.error("removeEntries failed; dropped {} entries: {}",
									entriesToRemove.size(),
									entriesToRemove.stream().map(Entry::getEntryURI).toList());
							throw e;
						}
					}
				}
				if (!postQueue.asMap().isEmpty()) {
					Set<Entry> entriesToUpdate = new HashSet<>();
					synchronized (postQueue) {
						ConcurrentMap<URI, Entry> postQueueMap = postQueue.asMap();
						Iterator<URI> it = postQueueMap.keySet().iterator();
						while (batchCount < BATCH_SIZE && it.hasNext()) {
							URI key = it.next();
							Entry entry = postQueueMap.get(key);
							postQueueMap.remove(key, entry);
							if (entry == null) {
								log.warn("Value for key {} is null in Public Repository submit queue", key);
							}
							entriesToUpdate.add(entry);
							batchCount++;
						}
					}
					postQueue.cleanUp();
					log.info("Sending {} entries for update in Public Repository, {} entries remaining in post queue",
							entriesToUpdate.size(), postQueue.asMap().size());
					try {
						updateEntries(entriesToUpdate);
					} catch (RuntimeException e) {
						log.error("updateEntries failed; dropped {} entries: {}",
								entriesToUpdate.size(),
								entriesToUpdate.stream()
										.filter(java.util.Objects::nonNull)
										.map(Entry::getEntryURI)
										.toList());
						throw e;
					}
				}
			} else {
				// Block until enqueue()/remove() signals work is available, or until the timeout
				// fires as defense against spurious wakeups (per Object.wait spec) and as a
				// heartbeat. The recheck below closes the lost-notify race: if a notify arrived
				// between the outer if and entering this synchronized block, it is observed here
				// via the now-non-empty queue instead of being delivered to a wait that hasn't
				// started.
				synchronized (queueSignal) {
					if (postQueue.asMap().isEmpty() && deleteQueue.isEmpty()) {
						try {
							queueSignal.wait(IDLE_TIMEOUT_MS);
						} catch (InterruptedException ie) {
							log.info("Public Repository submitter got interrupted, shutting down submitter thread");
							Thread.currentThread().interrupt();
						}
					}
				}
			}
			return batchCount > 0;
		}

	}

	/** The wait after the given number of consecutive submitter failures: 1 s, doubling up to 60 s. */
	static long backoffMillis(int consecutiveFailures) {
		// the shift is bounded only against overflow; FAILURE_BACKOFF_MAX_MS is the cap
		return Math.min(FAILURE_BACKOFF_MAX_MS, FAILURE_BACKOFF_BASE_MS << Math.min(consecutiveFailures - 1, 30));
	}

	private static long secondsBetween(long fromNanos, long toNanos) {
		return Duration.ofNanos(toNanos - fromNanos).toSeconds();
	}

	public PublicRepository(RepositoryManager rm) {
		this.rm = rm;
		this.pm = rm.getPrincipalManager();
		Config config = rm.getConfiguration();

		String storeType = config.getString(Settings.REPOSITORY_PUBLIC_TYPE, "memory").trim();
		log.info("Public repository type: {}", storeType);
		
		if (storeType.equalsIgnoreCase("memory")) {
			this.repository = new SailRepository(new MemoryStore());
		} else if (storeType.equalsIgnoreCase("native")) {
			if (!config.containsKey(Settings.REPOSITORY_PUBLIC_PATH)) {
				log.error("Incomplete configuration of public repository");
			} else {
				File path = new File(config.getURI(Settings.REPOSITORY_PUBLIC_PATH));
				String indexes = config.getString(Settings.REPOSITORY_PUBLIC_INDEXES);
				boolean wal = config.getBoolean(Settings.REPOSITORY_PUBLIC_WAL, false);
				((RepositoryManagerImpl) rm).checkAndUpgradeNativeStore(path, indexes);
				log.info("Public repository: using Native Store at {} with indexes {}, value-store WAL {}",
						path, indexes, RepositoryManagerImpl.walState(wal));
				this.repository = new SailRepository(RepositoryManagerImpl.newNativeStore(path, indexes, wal));
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
				config.getBoolean(Settings.REPOSITORY_PUBLIC_REBUILD_ON_STARTUP, false)) {
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
			log.info("Adding document to update queue: {}", entryURI);
			postQueue.put(entryURI, entry);
		}
		signalSubmitter();
	}

	public void remove(Entry entry) {
		URI entryURI = entry.getEntryURI();
		synchronized (deleteQueue) {
			log.info("Adding entry to delete queue: {}", entryURI);
			deleteQueue.add(entry);
		}
		signalSubmitter();
	}

	private void signalSubmitter() {
		synchronized (queueSignal) {
			queueSignal.notifyAll();
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

	void updateEntries(Set<Entry> entries) {
		writeBatch(entries, this::updateEntry);
	}

	void removeEntries(Set<Entry> entries) {
		writeBatch(entries, this::removeEntry);
	}

	/**
	 * Writes the entries in one transaction, as guest. If that fails, each entry of a larger batch is written in a
	 * transaction of its own, so that an entry that always fails is dropped alone instead of together with everything
	 * queued during the backoff. The RuntimeException is rethrown, so that the submitter counts it and backs off, when
	 * the store itself fails or no entry could be written.
	 */
	private void writeBatch(Set<Entry> entries, BiConsumer<Entry, RepositoryConnection> write) {
		URI currentUser = pm.getAuthenticatedUserURI();
		try {
			pm.setAuthenticatedUserURI(pm.getGuestUser().getURI());
			synchronized (repository) {
				try {
					writeInOneTransaction(entries, write);
				} catch (RuntimeException batchFailure) {
					if (entries.size() < 2) {
						throw batchFailure;
					}
					writeEachAlone(entries, write, batchFailure);
				}
			}
		} finally {
			pm.setAuthenticatedUserURI(currentUser);
		}
	}

	private void writeInOneTransaction(Set<Entry> entries, BiConsumer<Entry, RepositoryConnection> write) {
		RepositoryConnection rc = repository.getConnection();
		try {
			rc.begin();
			for (Entry e : entries) {
				write.accept(e, rc);
			}
			rc.commit();
		} catch (RuntimeException e) {
			EntryImpl.rollbackQuietly(rc, e);
			throw e;
		} finally {
			closeQuietly(rc);
		}
	}

	/**
	 * Writes each entry in a transaction of its own and logs the entries that fail, or {@code batchFailure} if none
	 * does. A failing begin or commit is a store failure and, like a batch of which no entry could be written,
	 * rethrows {@code batchFailure}.
	 */
	private void writeEachAlone(Set<Entry> entries, BiConsumer<Entry, RepositoryConnection> write,
			RuntimeException batchFailure) {
		Map<Entry, RuntimeException> dropped = new LinkedHashMap<>();
		boolean anyWritten = false;
		RepositoryConnection rc;
		try {
			rc = repository.getConnection();
		} catch (RuntimeException storeFailure) {
			batchFailure.addSuppressed(storeFailure);
			throw batchFailure;
		}
		try {
			for (Entry e : entries) {
				rc.begin();
				try {
					write.accept(e, rc);
				} catch (RuntimeException entryFailure) {
					EntryImpl.rollbackQuietly(rc, entryFailure);
					dropped.put(e, entryFailure);
					continue;
				}
				rc.commit();
				if (e != null) {
					anyWritten = true;
				}
			}
		} catch (RuntimeException storeFailure) {
			EntryImpl.rollbackQuietly(rc, storeFailure);
			batchFailure.addSuppressed(storeFailure);
			throw batchFailure;
		} finally {
			closeQuietly(rc);
		}
		if (!anyWritten) {
			throw batchFailure;
		}
		if (dropped.isEmpty()) {
			log.warn("Public Repository batch of {} entries failed, but each entry was written on its own: {}",
					entries.size(), batchFailure.getMessage(), batchFailure);
		}
		dropped.forEach((e, failure) -> log.error("Public Repository dropped entry {}, which fails on its own: {}",
				e.getEntryURI(), failure.getMessage(), failure));
	}

	private void closeQuietly(RepositoryConnection rc) {
		try {
			rc.close();
		} catch (RepositoryException re) {
			log.error(re.getMessage());
		}
	}

	/**
	 * A context's entries are looked up without an access check, so that an entry guest can no longer read is still
	 * removed; {@link #addEntry} decides as guest whether it is added again.
	 */
	private void updateEntry(Entry e, RepositoryConnection rc) throws RepositoryException {
		if (e == null) {
			return;
		}
		
		// A context's entries are published again together, just in case its ACL has changed: its graphs are cleared
		// at once, without per-statement lookups, and the entries the guest can still read are added again.
		if (isContextEntry(e)) {
			boolean canonical = hasCanonicalContextURI(e);
			if (canonical) {
				clearContext(repository.getValueFactory().createIRI(e.getResourceURI().toString()), rc);
			}
			Context context = rm.getContextManager().getContext(e.getId());
			if (context != null) {
				Set<URI> entries = context instanceof ContextImpl contextImpl
						? contextImpl.getChildEntryURIsFromStore()
						: context.getEntries();
				for (URI entryURI : entries) {
					if (entryURI != null) {
						Entry child = ((ContextManagerImpl) rm.getContextManager()).getEntryIgnoreACL(entryURI);
						if (child != null) {
							republishChild(child, canonical, rc);
						}
					}
				}
			}
		} else {
			log.debug("Processing entry: {}", e.getEntryURI());
			removeEntry(e, rc);
			addEntry(e, rc);
		}
	}

	/**
	 * Removes the entry's graphs and, from its context's graph, the statements no other entry of the context states.
	 * A context entry instead takes its context's graph and the graphs of all its entries with it. The statements are
	 * read and removed {@value #REMOVAL_CHUNK_SIZE} at a time, which bounds the list held in memory; the transaction
	 * still holds every removal until it commits, and the loop relies on reading its own removals when it reads the
	 * entry's graphs again.
	 */
	private void removeEntry(Entry e, RepositoryConnection rc) throws RepositoryException {
		PrincipalManager pm = e.getRepositoryManager().getPrincipalManager();
		URI currentUser = pm.getAuthenticatedUserURI();
		try {
			// we need to be admin, in case the ACL has become
			// more restrictive since adding the entry
			pm.setAuthenticatedUserURI(pm.getAdminUser().getURI());
			ValueFactory vf = repository.getValueFactory();
			IRI contextURI = vf.createIRI(e.getContext().getURI().toString());

			Resource[] entryGraphs = entryGraphs(e);
			// The whole context goes; its own graphs were never in its context's graph, which is _contexts.
			boolean contextEntry = isContextEntry(e);
			if (contextEntry && hasCanonicalContextURI(e)) {
				clearContext(vf.createIRI(e.getResourceURI().toString()), rc);
			}
			List<Statement> chunk;
			do {
				chunk = new ArrayList<>();
				try (RepositoryResult<Statement> result = rc.getStatements(null, null, null, false, entryGraphs)) {
					while (result.hasNext() && chunk.size() < REMOVAL_CHUNK_SIZE) {
						chunk.add(result.next());
					}
				}
				rc.remove(chunk, entryGraphs);
				if (!contextEntry) {
					rc.remove(notHeldByAnotherEntryGraph(chunk, contextURI, rc), contextURI);
				}
			} while (chunk.size() == REMOVAL_CHUNK_SIZE);
		} finally {
			pm.setAuthenticatedUserURI(currentUser);
		}
	}

	/**
	 * After a bulk clear, only the child's graphs outside its context's URI can be left, which clearing its own
	 * graphs covers; without one, the child is removed and added as on its own.
	 */
	private void republishChild(Entry child, boolean afterBulkClear, RepositoryConnection rc) {
		if (afterBulkClear) {
			URI currentUser = pm.getAuthenticatedUserURI();
			try {
				pm.setAuthenticatedUserURI(pm.getAdminUser().getURI());
				rc.clear(entryGraphs(child));
			} finally {
				pm.setAuthenticatedUserURI(currentUser);
			}
		} else {
			removeEntry(child, rc);
		}
		addEntry(child, rc);
	}

	/** A context whose resource URI is {@code {repository URL}{id}}, as core creates it, owns the graphs below it. */
	private boolean hasCanonicalContextURI(Entry contextEntry) {
		return contextEntry.getResourceURI().equals(
				URISplit.createURI(rm.getRepositoryURL().toString(), contextEntry.getId()));
	}

	/**
	 * The graphs the entry published, read as admin. Of the resource graphs, it is the one named by the entry's own
	 * resource URI derived from its entry URI, which only this entry can have published as a local Graph entry even if
	 * it is no longer one, and its current resource URI only if it is a local Graph entry, the only kind addEntry
	 * publishes one for: the resource URI of other entries can name any graph.
	 */
	private Resource[] entryGraphs(Entry e) {
		ValueFactory vf = repository.getValueFactory();
		List<Resource> graphs = new ArrayList<>();
		graphs.add(vf.createIRI(e.getEntryURI().toString()));
		if (e.getLocalMetadataURI() != null) {
			graphs.add(vf.createIRI(e.getLocalMetadataURI().toString()));
		}
		if (e.getExternalMetadataURI() != null) {
			graphs.add(vf.createIRI(e.getCachedExternalMetadataURI().toString()));
		}
		URISplit.of(e.getEntryURI(), rm.getRepositoryURL())
				.ifPresent(split -> graphs.add(vf.createIRI(split.getResourceURI().toString())));
		IRI resourceGraph = vf.createIRI(e.getResourceURI().toString());
		boolean localGraph = GraphType.Graph.equals(e.getGraphType()) && EntryType.Local.equals(e.getEntryType());
		if (localGraph && !graphs.contains(resourceGraph)) {
			graphs.add(resourceGraph);
		}
		return graphs.toArray(Resource[]::new);
	}

	/**
	 * A local context entry, whose resource graph is the graph of that context. A system context is not one: its
	 * entries, contexts and principals, are never published.
	 */
	private static boolean isContextEntry(Entry e) {
		return GraphType.Context.equals(e.getGraphType()) && EntryType.Local.equals(e.getEntryType());
	}

	/**
	 * Clears the graph of a context and the graphs of all its entries, without per-statement lookups: the whole
	 * context goes. The entry graphs are found in one pass over the graph names.
	 */
	private void clearContext(IRI contextGraph, RepositoryConnection rc) {
		List<Resource> graphs = new ArrayList<>();
		graphs.add(contextGraph);
		try (RepositoryResult<Resource> ids = rc.getContextIDs()) {
			for (Resource id : ids) {
				if (isGraphOfContext(id, contextGraph)) {
					graphs.add(id);
				}
			}
		}
		rc.clear(graphs.toArray(Resource[]::new));
	}

	/**
	 * The statements, without their graph, that no remaining entry graph of the context holds. The context graph
	 * holds the union of the context's entry graphs, so a statement that another entry of the context also states
	 * stays in it. Called after the statements are removed from the entry's own graphs, in the same transaction, so
	 * that entry and entries removed earlier in the batch no longer count.
	 */
	private List<Statement> notHeldByAnotherEntryGraph(List<Statement> statements, IRI contextURI,
			RepositoryConnection rc) {
		ValueFactory vf = repository.getValueFactory();
		Set<Statement> distinct = new LinkedHashSet<>();
		for (Statement statement : statements) {
			distinct.add(vf.createStatement(statement.getSubject(), statement.getPredicate(), statement.getObject()));
		}
		List<Statement> notHeld = new ArrayList<>();
		for (Statement statement : distinct) {
			if (!heldByAnotherEntryGraph(statement, contextURI, rc)) {
				notHeld.add(statement);
			}
		}
		return notHeld;
	}

	private boolean heldByAnotherEntryGraph(Statement statement, IRI contextURI, RepositoryConnection rc) {
		try (RepositoryResult<Statement> copies = rc.getStatements(statement.getSubject(), statement.getPredicate(),
				statement.getObject(), false)) {
			for (Statement copy : copies) {
				if (copy.getContext() != null && !copy.getContext().equals(contextURI)
						&& isGraphOfContext(copy.getContext(), contextURI)) {
					return true;
				}
			}
		}
		return false;
	}

	/**
	 * Whether {@code graph} is the graph of an entry in the context, as core splits the URIs of its entries. An entry
	 * graph is {@code {context URI}/{path}/{id}}; checked first, as most copies of a statement are in other contexts,
	 * and so that the graph of another context, which splits as a resource of the context entry, never counts.
	 */
	private boolean isGraphOfContext(Resource graph, IRI contextURI) {
		if (!graph.stringValue().startsWith(contextURI.stringValue() + "/")) {
			return false;
		}
		try {
			return URISplit.of(URI.create(graph.stringValue()), rm.getRepositoryURL())
					.map(URISplit::getContextURI)
					.filter(context -> context.toString().equals(contextURI.stringValue()))
					.isPresent();
		} catch (IllegalArgumentException e) {
			return false;
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
			URI currentUser = pm.getAuthenticatedUserURI();
			try {
				pm.setAuthenticatedUserURI(pm.getGuestUser().getURI());
				rc = repository.getConnection();
				Date before = new Date();
				rc.begin();
				rc.clear();
				log.info("Clearing public repository took {} ms", new Date().getTime() - before.getTime());

				ContextManager cm = rm.getContextManager();
				Set<URI> contexts = cm.getEntries();

				for (URI contextURI : contexts) {
					String id = URISplit.getLastSegment(contextURI.toString());
					Context context = cm.getContext(id);
					if (context != null) {
						log.info("Adding context {} to public repository", contextURI);
						before = new Date();
						// Enumerated from the store, not from getEntries(). rc.clear() above wiped the whole
						// public repository and this loop is the only thing that re-adds data, so a listing
						// short by one unindexable statement would drop those entries from the public
						// repository for good (ENTRYSTORE-1095). Refusing to rebuild such a context instead —
						// which an earlier revision did — is strictly worse: the destruction has already
						// happened by the time the check could run, so it drops every public entry of the
						// context rather than the handful the listing misses, and rebuildRepository is only
						// called at construction, so nothing brings them back before a restart. Enumerating
						// from the store removes the trade-off rather than choosing a side of it, and is what
						// importContext and context removal already do.
						Set<URI> entries = (context instanceof ContextImpl ci)
								? ci.getChildEntryURIsFromStore()
								: context.getEntries();
						log.info("Fetching entries took {} ms", new Date().getTime() - before.getTime());
						before = new Date();
						Date timeTracker = new Date();
						long publicEntryCount = 0;
						long processedCount = 0;
						for (URI entryURI : entries) {
							if (entryURI != null) {
								processedCount++;
								if ((new Date().getTime() - timeTracker.getTime()) > 60000) {
									if (processedCount > 0) {
										log.debug("Average time per entry after {} ms: {} ms",
												new Date().getTime() - before.getTime(),
												(new Date().getTime() - before.getTime()) / processedCount);
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
						log.info("Added {} entries to public repository", publicEntryCount);
						log.info("Total time for context: {} ms", new Date().getTime() - before.getTime());
						if (!entries.isEmpty()) {
							log.debug("Total average time per entry: {} ms", (new Date().getTime() - before.getTime()) / entries.size());
						}
						log.info("Done processing context {}", contextURI);
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
				pm.setAuthenticatedUserURI(currentUser);
				try {
					rc.close();
				} catch (RepositoryException re) {
					log.error(re.getMessage());
				}
				log.info("Rebuild of public repository complete");
				log.info("Number of triples in public repository: {}", getTripleCount());
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
			log.error("Error when shutting down public repository: {}", e.getMessage(), e);
		}
	}

}
