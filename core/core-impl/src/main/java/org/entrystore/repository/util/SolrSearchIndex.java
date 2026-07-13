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

package org.entrystore.repository.util;

import com.google.common.collect.Queues;
import org.apache.solr.client.solrj.RemoteSolrException;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.request.SolrQuery;
import org.apache.solr.client.solrj.request.UpdateRequest;
import org.apache.solr.client.solrj.response.FacetField;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.client.solrj.response.SolrPingResponse;
import org.apache.solr.client.solrj.util.ClientUtils;
import org.apache.solr.common.SolrDocument;
import org.apache.solr.common.SolrDocumentList;
import org.apache.solr.common.SolrException;
import org.apache.solr.common.SolrInputDocument;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Literal;
import org.eclipse.rdf4j.model.Model;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.LinkedHashModel;
import org.eclipse.rdf4j.model.vocabulary.RDF;
import org.entrystore.AuthorizationException;
import org.entrystore.Context;
import org.entrystore.ContextManager;
import org.entrystore.Entry;
import org.entrystore.EntryType;
import org.entrystore.GraphType;
import org.entrystore.PrincipalManager;
import org.entrystore.PrincipalManager.AccessProperty;
import org.entrystore.SearchIndex;
import org.entrystore.User;
import org.entrystore.impl.LocalMetadataWrapper;
import org.entrystore.impl.RegularContext;
import org.entrystore.impl.RepositoryProperties;
import org.entrystore.repository.RepositoryManager;
import org.entrystore.repository.config.Settings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.xml.datatype.DatatypeConstants;
import javax.xml.datatype.XMLGregorianCalendar;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static java.lang.Thread.interrupted;


/**
 * @author Hannes Ebner
 */
public class SolrSearchIndex implements SearchIndex {

	private static final Logger log = LoggerFactory.getLogger(SolrSearchIndex.class);

	private static final int BATCH_SIZE_ADD = 100;

	private static final int BATCH_SIZE_DELETE = 100;

	// A12: previously a hard-coded 1000 ms, which under steady write load produced ~1 commit/s and
	// tiny segments. Raised default and made configurable via entrystore.solr.commit-within[-max].
	private static final int SOLR_COMMIT_WITHIN_DEFAULT = 5000;

	private static final int SOLR_COMMIT_WITHIN_MAX_DEFAULT = 15000;

	// A13: heartbeat / spurious-wakeup guard for the submitter's wait(); in steady state the
	// submitter is woken by signalSubmitter() within microseconds of an enqueue.
	private static final long IDLE_TIMEOUT_MS = 10_000;

	private static final long EXECUTOR_SHUTDOWN_TIMEOUT_SECONDS = 10;

	private static final long MAX_PURGE_WAIT_NANOS = TimeUnit.MINUTES.toNanos(5);

	private static final DateTimeFormatter SOLR_DATE_FORMATTER =
			DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

	// Predicates repeat heavily across indexed statements while the vocabulary itself stays
	// small, so the map is effectively bounded by the number of distinct predicates in use.
	private static final ConcurrentMap<String, String> PREDICATE_MD5_CACHE = new ConcurrentHashMap<>();

	private final String defaultSortLang;

	private final boolean extractFulltext;

	private boolean related;

	private Map<IRI, Boolean> relatedProperties = null;

	private boolean relatedContainsGlobal = false;

	// A8: cached set of all RegularContexts for the global "related" index; only populated when
	// entrystore.solr.related is configured with a ",global" property. Invalidated on context add/remove.
	private volatile Set<Context> cachedGlobalRegularContexts;

	private final RepositoryManager rm;

	private final SolrClient solrServer;

	private final Thread documentSubmitter;

	private final Thread delayedContextIndexer;

	// A1/A9: we queue only entry URIs and build the SolrInputDocument in the submitter thread
	// (see drainAndBuildPostBatch), so the expensive construction - a metadata-graph read plus
	// ACL evaluation per entry - runs off the repository, listener and postQueue locks that
	// postEntry is invoked under via MetadataImpl.setGraph -> fireRepositoryEvent. LinkedHashSet
	// gives O(1) dedup by URI and FIFO-ish draining; guard every access with synchronized(postQueue).
	private final Set<URI> postQueue = new LinkedHashSet<>();

	private final Queue<URI> deleteQueue = Queues.newConcurrentLinkedQueue();

	// A13: wakes the submitter when work is enqueued so it does not have to wait out its idle
	// timeout. Lost-notify is prevented by the recheck-under-lock in the submitter run loop.
	private final Object queueSignal = new Object();

	// A12: effective commit-within windows, read from config in the constructor.
	private final int commitWithin;

	private final int commitWithinMax;

	// A1 startup gate: building documents loads entries via the ContextManager, and doing that
	// while RepositoryManagerImpl is still initializing races against init state that is not
	// thread-safe yet (observed as a ConcurrentModificationException in ContextImpl.getEntries
	// during PublicRepository's startup rebuild). The submitter processes nothing until
	// markRepositoryInitialized() opens the gate; work enqueued before that simply waits.
	private volatile boolean repositoryInitialized = false;

	private final Map<URI, Future> reindexing = Collections.synchronizedMap(new HashMap<>());

	private final ExecutorService reindexExecutor = Executors.newSingleThreadExecutor();

	private final ExecutorService purgeExecutor = Executors.newVirtualThreadPerTaskExecutor();

	// True while the submitter is between drain and process/requeue — observers awaiting a quiescent
	// queue must wait for this to clear too, otherwise an in-flight batch can be missed.
	private final AtomicBoolean submitterInFlight = new AtomicBoolean();

	private final Map<URI, DelayedContextIndexerInfo> delayedReindex = Collections.synchronizedMap(new HashMap<>());

	private final ValueFactory valueFactory;

	public class SolrInputDocumentSubmitter extends Thread {

		private static final int MAX_RETRIES = 3;
		private static final long RETRY_DELAY_MS = 2000;
		private static final long FAILURE_COOLDOWN_MS = 10000;

		@Override
		public void run() {
			while (!interrupted()) {
				try {
					boolean batchFailed = false;

					if (repositoryInitialized && (!isPostQueueEmpty() || !deleteQueue.isEmpty())) {

						if (!deleteQueue.isEmpty()) {
							batchFailed = processDeleteBatch();
						}

						if (!isPostQueueEmpty() && !Thread.currentThread().isInterrupted()) {
							batchFailed = processAddBatch() || batchFailed;
						}

						if (batchFailed) {
							sleepOrShutdown(FAILURE_COOLDOWN_MS, "cooldown");
						}

					} else {
						// A13: block until signalSubmitter() wakes us or the idle timeout fires
						// (heartbeat + spurious-wakeup guard). The recheck under the lock closes the
						// lost-notify race: a signal arriving between the outer emptiness check and
						// entering this block is observed here via a now-non-empty queue instead of
						// being delivered to a wait that has not started.
						synchronized (queueSignal) {
							if (!repositoryInitialized || (isPostQueueEmpty() && deleteQueue.isEmpty())) {
								try {
									queueSignal.wait(IDLE_TIMEOUT_MS);
								} catch (InterruptedException ie) {
									log.info("Solr document submitter got interrupted during idle wait, shutting down");
									throw new ShutdownRequestedException();
								}
							}
						}
					}
				} catch (ShutdownRequestedException e) {
					return;
				} catch (Exception e) {
					log.error("Unexpected error in Solr document submitter, will retry on next iteration", e);
					try {
						sleepOrShutdown(FAILURE_COOLDOWN_MS, "post-error cooldown");
					} catch (ShutdownRequestedException sre) {
						return;
					}
				}
			}
		}

		/**
		 * Sleeps for the given duration; on interruption, logs and throws
		 * {@link ShutdownRequestedException} to unwind the run loop cleanly.
		 */
		private void sleepOrShutdown(long millis, String phase) {
			try {
				Thread.sleep(millis);
			} catch (InterruptedException ie) {
				log.info("Solr document submitter got interrupted during {}, shutting down", phase);
				throw new ShutdownRequestedException();
			}
		}

		/**
		 * @return true if the batch failed permanently and a cooldown is needed
		 */
		private boolean processDeleteBatch() {
			submitterInFlight.set(true);
			try {
				List<URI> deleteBatch = drainDeleteQueue();
				if (deleteBatch.isEmpty()) {
					return false;
				}

				// uri is the schema uniqueKey, so deleteById applies directly to the version
				// buckets instead of a deleteByQuery, which blocks concurrent adds and merges.
				UpdateRequest delReq = new UpdateRequest();
				delReq.deleteById(deleteBatch.stream().map(URI::toString).toList());
				delReq.setCommitWithin(commitWithin);

				for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
					try {
						log.info("Sending request to delete {} entries from Solr (attempt {}/{}), {} entries remaining in delete queue",
								deleteBatch.size(), attempt, MAX_RETRIES, deleteQueue.size());
						delReq.process(solrServer);
						return false;
					} catch (RuntimeException e) {
						requeueDeletes(deleteBatch);
						if (e.getCause() instanceof InterruptedException) {
							log.info("Solr document submitter got interrupted during delete, re-queuing and shutting down");
							Thread.currentThread().interrupt();
							return true;
						}
						throw e;
					} catch (SolrServerException | IOException e) {
						log.warn("Failed to delete {} entries from Solr (attempt {}/{}): {}",
								deleteBatch.size(), attempt, MAX_RETRIES, e.getMessage());
						if (attempt < MAX_RETRIES && sleepForRetry(attempt)) {
							requeueDeletes(deleteBatch);
							return true;
						}
					}
				}

				log.error("Permanently failed to delete {} entries from Solr after {} attempts, re-queuing", deleteBatch.size(), MAX_RETRIES);
				requeueDeletes(deleteBatch);
				return true;
			} finally {
				submitterInFlight.set(false);
			}
		}

		/**
		 * @return true if the batch failed permanently and a cooldown is needed
		 */
		private boolean processAddBatch() {
			submitterInFlight.set(true);
			try {
				Map<URI, SolrInputDocument> addBatch = drainAndBuildPostBatch();
				if (addBatch.isEmpty()) {
					return false;
				}

				UpdateRequest addReq = new UpdateRequest();
				addBatch.values().forEach(addReq::add);

				if (getPostQueueSize() > BATCH_SIZE_ADD * 5L) {
					addReq.setCommitWithin(commitWithinMax);
				} else {
					addReq.setCommitWithin(commitWithin);
				}

				for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
					try {
						log.info("Sending {} entries to Solr (attempt {}/{}), {} entries remaining in post queue",
								addBatch.size(), attempt, MAX_RETRIES, getPostQueueSize());
						addReq.process(solrServer);
						return false;
					} catch (RemoteSolrException e) {
						log.error("Solr rejected {} entries (HTTP {}, discarding batch). URIs: {}",
								addBatch.size(), e.code(), addBatch.keySet(), e);
						return false;
					} catch (RuntimeException e) {
						requeueAdds(addBatch.keySet());
						if (e.getCause() instanceof InterruptedException) {
							log.info("Solr document submitter got interrupted during send, re-queuing and shutting down");
							Thread.currentThread().interrupt();
							return true;
						}
						throw e;
					} catch (SolrServerException | IOException e) {
						log.warn("Failed to send {} entries to Solr (attempt {}/{}): {}",
								addBatch.size(), attempt, MAX_RETRIES, e.getMessage());
						if (attempt < MAX_RETRIES && sleepForRetry(attempt)) {
							requeueAdds(addBatch.keySet());
							return true;
						}
					}
				}

				log.error("Permanently failed to send {} entries to Solr after {} attempts, re-queuing", addBatch.size(), MAX_RETRIES);
				requeueAdds(addBatch.keySet());
				return true;
			} finally {
				submitterInFlight.set(false);
			}
		}

		/**
		 * Drains up to {@link SolrSearchIndex#BATCH_SIZE_DELETE} entries from the delete queue.
		 */
		private List<URI> drainDeleteQueue() {
			List<URI> batch = new ArrayList<>();
			synchronized (deleteQueue) {
				while (batch.size() < BATCH_SIZE_DELETE) {
					URI uri = deleteQueue.poll();
					if (uri == null) {
						break;
					}
					batch.add(uri);
				}
			}
			return batch;
		}

		/**
		 * A1: drains up to {@link SolrSearchIndex#BATCH_SIZE_ADD} entry URIs from the post queue and
		 * builds their {@link SolrInputDocument}s here, on the submitter thread, outside the
		 * repository/listener/postQueue locks that {@link #postEntry(Entry)} runs under. Entries that
		 * were deleted between enqueue and drain are skipped (they are handled via the delete queue).
		 */
		private Map<URI, SolrInputDocument> drainAndBuildPostBatch() {
			List<URI> uris = new ArrayList<>();
			synchronized (postQueue) {
				Iterator<URI> it = postQueue.iterator();
				while (uris.size() < BATCH_SIZE_ADD && it.hasNext()) {
					uris.add(it.next());
					it.remove();
				}
			}
			if (uris.isEmpty()) {
				return Map.of();
			}

			// A7: entries in one batch are heavily context-clustered (especially during reindex), so a
			// per-batch cache of context -> projectType collapses the repeated context-graph reads.
			Map<URI, String> projectTypeCache = new HashMap<>();
			Map<URI, SolrInputDocument> batch = new LinkedHashMap<>();
			PrincipalManager pm = rm.getPrincipalManager();
			URI currentUser = pm.getAuthenticatedUserURI();
			try {
				pm.setAuthenticatedUserURI(pm.getAdminUser().getURI());
				for (URI entryURI : uris) {
					try {
						Entry entry = rm.getContextManager().getEntry(entryURI);
						if (entry == null || entry.isDeleted() || entry.getContext().isDeleted()) {
							log.debug("Skipping {} during Solr batch build (missing or deleted)", entryURI);
							continue;
						}
						batch.put(entryURI, constructSolrInputDocument(entry, extractFulltext, projectTypeCache));
					} catch (Exception e) {
						log.error("Not indexing {} due to error: {}", entryURI, e.getMessage());
					}
				}
			} finally {
				pm.setAuthenticatedUserURI(currentUser);
			}
			return batch;
		}

		private void requeueDeletes(List<URI> batch) {
			deleteQueue.addAll(batch);
			signalSubmitter();
		}

		private void requeueAdds(Collection<URI> uris) {
			synchronized (postQueue) {
				postQueue.addAll(uris);
			}
			signalSubmitter();
		}

		/**
		 * Sleeps for a retry backoff delay.
		 *
		 * @return true if the sleep was interrupted (caller should re-queue and shut down)
		 */
		private boolean sleepForRetry(int attempt) {
			try {
				Thread.sleep(RETRY_DELAY_MS * attempt);
				return false;
			} catch (InterruptedException ie) {
				log.info("Solr document submitter got interrupted during retry backoff, shutting down");
				Thread.currentThread().interrupt();
				return true;
			}
		}

		/**
		 * Signals that the submitter thread should shut down (used to exit the run loop on interrupt).
		 */
		private static class ShutdownRequestedException extends RuntimeException {
		}

	}

	public static class DelayedContextIndexerInfo {

		LocalDateTime submitted;

		boolean guestReadable;

	}

	public static class FacetSettings {

		public String fields;

		public int minCount;

		public int limit;

		public String matches;

		public boolean missing;

	}

	public class DelayedContextIndexer extends Thread {

		@Override
		public void run() {
			while (!interrupted()) {
				synchronized (delayedReindex) {
					Iterator<URI> it = delayedReindex.keySet().iterator();
					while (it.hasNext()) {
						URI contextURI = it.next();
						DelayedContextIndexerInfo info = delayedReindex.get(contextURI);
						if (info.submitted.until(LocalDateTime.now(), ChronoUnit.SECONDS) >= 10) {
							log.info("Submitting context for reindexing after 10 seconds delay");
							reindex(contextURI, false);
							it.remove();
						}
					}
				}

				try {
					Thread.sleep(1000);
				} catch (InterruptedException ie) {
					log.info("Solr delayed context indexer got interrupted, shutting down thread");
					return;
				}
			}
		}

	}

	public SolrSearchIndex(RepositoryManager rm, SolrClient solrServer) {
		this.rm = rm;
		valueFactory = this.rm.getValueFactory();
		this.solrServer = solrServer;
		extractFulltext = "on".equalsIgnoreCase(rm.getConfiguration().getString(Settings.SOLR_EXTRACT_FULLTEXT, "off"));
		related = "on".equalsIgnoreCase(rm.getConfiguration().getString(Settings.SOLR_RELATED, "off"));
		defaultSortLang = rm.getConfiguration().getString(Settings.SOLR_DEFAULT_SORTING_LANG);
		commitWithin = rm.getConfiguration().getInt(Settings.SOLR_COMMIT_WITHIN, SOLR_COMMIT_WITHIN_DEFAULT);
		commitWithinMax = rm.getConfiguration().getInt(Settings.SOLR_COMMIT_WITHIN_MAX, SOLR_COMMIT_WITHIN_MAX_DEFAULT);
		projectTypePredicate = valueFactory.createIRI("http://entryscape.com/terms/projectType");
		if (related) {
			List<String> relPropsSetting = rm.getConfiguration().getStringList(Settings.SOLR_RELATED_PROPERTIES, new ArrayList<>());
			if (relPropsSetting.isEmpty()) {
				related = false;
			} else {
				relatedProperties = new HashMap<>();
				for (String relProp : rm.getConfiguration().getStringList(Settings.SOLR_RELATED_PROPERTIES, new ArrayList<>())) {
					if (relProp.endsWith(",global")) {
						relatedProperties.put(valueFactory.createIRI(relProp.substring(0, relProp.indexOf(","))), true);
					} else {
						relatedProperties.put(valueFactory.createIRI(relProp), false);
					}
				}
				relatedContainsGlobal = relatedProperties.containsValue(true);
			}
		}

		documentSubmitter = new SolrInputDocumentSubmitter();
		documentSubmitter.setDaemon(true);
		documentSubmitter.start();

		delayedContextIndexer = new DelayedContextIndexer();
		delayedContextIndexer.setDaemon(true);
		delayedContextIndexer.start();
	}

	public void shutdown() {
		if (documentSubmitter != null) {
			documentSubmitter.interrupt();
		}

		if (delayedContextIndexer != null) {
			delayedContextIndexer.interrupt();
		}

		reindexExecutor.shutdown();

		// `&=` (not `&&`) so we always join both threads even if the first didn't terminate cleanly.
		boolean threadsClean = true;
		if (documentSubmitter != null) {
			threadsClean &= joinOrWarn(documentSubmitter, "Solr document submitter");
		}
		if (delayedContextIndexer != null) {
			threadsClean &= joinOrWarn(delayedContextIndexer, "Delayed context indexer");
		}
		awaitOrForceShutdown(reindexExecutor, "Reindex executor");

		// Purge executor shuts down only after reindex tasks have fully drained, so any
		// purgeExecutor.submit(...) calls they make complete before shutdown begins.
		purgeExecutor.shutdown();
		awaitOrForceShutdown(purgeExecutor, "Purge executor");

		if (threadsClean) {
			try {
				log.debug("Sending commit to Solr");
				solrServer.commit(true, false);
			} catch (SolrServerException | IOException e) {
				log.error(e.getMessage());
			}
		} else {
			log.warn("Skipping final Solr commit because background threads did not terminate cleanly within {}s",
					EXECUTOR_SHUTDOWN_TIMEOUT_SECONDS);
		}
	}

	private boolean joinOrWarn(Thread thread, String name) {
		try {
			thread.join(TimeUnit.SECONDS.toMillis(EXECUTOR_SHUTDOWN_TIMEOUT_SECONDS));
			if (thread.isAlive()) {
				log.warn("{} did not terminate within {}s after interrupt; subsequent Solr operations may fail",
						name, EXECUTOR_SHUTDOWN_TIMEOUT_SECONDS);
				return false;
			}
			return true;
		} catch (InterruptedException e) {
			log.warn("Interrupted while joining {}", name);
			Thread.currentThread().interrupt();
			return false;
		}
	}

	private void awaitOrForceShutdown(ExecutorService executor, String name) {
		try {
			if (!executor.awaitTermination(EXECUTOR_SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
				forceShutdown(executor, name);
			}
		} catch (InterruptedException e) {
			forceShutdown(executor, name);
			Thread.currentThread().interrupt();
		}
	}

	private void forceShutdown(ExecutorService executor, String name) {
		List<Runnable> dropped = executor.shutdownNow();
		log.warn("{} did not terminate within {}s; forced shutdownNow (running tasks interrupted, {} queued tasks dropped)",
				name, EXECUTOR_SHUTDOWN_TIMEOUT_SECONDS, dropped.size());
		try {
			if (!executor.awaitTermination(EXECUTOR_SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
				log.warn("{} still has running tasks after shutdownNow; tasks may be ignoring interrupts or blocked in non-interruptible I/O",
						name);
			}
		} catch (InterruptedException e) {
			log.warn("Interrupted while awaiting forced termination of {}", name);
			Thread.currentThread().interrupt();
		}
	}

	public boolean clearSolrIndex(SolrClient solrServer) {
		UpdateRequest req = new UpdateRequest();
		req.deleteByQuery("*:*");
		req.setCommitWithin(commitWithin);
		try {
			req.process(solrServer);
			return true;
		} catch (SolrServerException | IOException e) {
			log.error("clearSolrIndex (full-wipe *:*) failed: {}", e.getMessage(), e);
			return false;
		}
	}

	public boolean clearSolrIndex(SolrClient solrServer, Date expirationDate, Entry contextEntry) {
		if (solrServer == null || (expirationDate == null && contextEntry == null)) {
			throw new IllegalArgumentException("Too many parameters are null");
		}
		UpdateRequest req = new UpdateRequest();
		String deleteQuery = "";
		if (expirationDate != null) {
			String solrExpirationDate = ClientUtils.escapeQueryChars(SOLR_DATE_FORMATTER.format(expirationDate.toInstant()));
			deleteQuery += "indexedAt:[* TO " + solrExpirationDate + "}";
		}
		if (contextEntry != null) {
			if (!deleteQuery.isEmpty()) {
				deleteQuery += " AND ";
			}
			deleteQuery += "context:" + ClientUtils.escapeQueryChars(contextEntry.getResourceURI().toString());
		}
		req.deleteByQuery(deleteQuery);
		req.setCommitWithin(commitWithin);
		try {
			req.process(solrServer);
			return true;
		} catch (SolrServerException | IOException e) {
			log.error("clearSolrIndex failed for query {}: {}", deleteQuery, e.getMessage(), e);
			return false;
		}
	}

	/**
	 * Re-indexes a context and all of its entries in Solr. Starts a new indexing thread and
	 * ends an eventually existing reindexing thread if there is one for the same scope.
	 *
	 * @param purgeAllBeforeReindex If true, the index will be emptied before re-indexation
	 *                                 starts. If false, expired entries will be removed after
	 *                                 re-indexation is finished.
	 */
	public void reindex(boolean purgeAllBeforeReindex) {
		reindex(purgeAllBeforeReindex, false);
	}

	/**
	 * Re-indexes a context and all of its entries in Solr. Starts a new indexing thread and
	 * ends an eventually existing reindexing thread if there is one for the same scope.
	 *
	 * @param contextURI The URI of the context to be re-indexed. Use "null" to reindex the whole repository.
	 * @param purgeAllBeforeReindex If true, the index will be emptied before re-indexation
	 *                                 starts. If false, expired entries will be removed after
	 *                                 re-indexation is finished.
	 */
	public void reindex(URI contextURI, boolean purgeAllBeforeReindex) {
		synchronized (reindexing) {
			if (reindexing.containsKey(contextURI)) {
				Future existingIndexer = reindexing.get(contextURI);
				if (!existingIndexer.isDone()) {
					log.info("Cancelling existing indexer thread for {}", contextURI);
					existingIndexer.cancel(true);
				}
				reindexing.remove(contextURI);
			}
			Future indexer = reindexExecutor.submit(() -> {
				reindexSync(contextURI, false);
				reindexing.remove(contextURI);
			});
			reindexing.put(contextURI, indexer);
		}
	}

	public void reindexSync(boolean purgeAllBeforeReindex) {
		reindex(purgeAllBeforeReindex, true);
	}

	private void reindex(boolean purgeAllBeforeReindex, boolean sync) {
		Set<URI> contexts;
		PrincipalManager pm = rm.getPrincipalManager();
		URI currentUser = pm.getAuthenticatedUserURI();
		try {
			pm.setAuthenticatedUserURI(pm.getAdminUser().getURI());
			contexts = rm.getContextManager().getEntries();
		} finally {
			pm.setAuthenticatedUserURI(currentUser);
		}

		for (URI contextURI : contexts) {
			if (sync) {
				reindexSync(contextURI, purgeAllBeforeReindex);
			} else {
				reindex(contextURI, purgeAllBeforeReindex);
			}
		}
	}

	public void reindexSync(URI contextURI, boolean purgeAllBeforeReindex) {
		if (solrServer == null) {
			log.warn("Ignoring request as Solr is not used by this instance");
			return;
		}
		if (contextURI == null) {
			throw new IllegalArgumentException("Context URI must not be null");
		}

		log.info("Starting Solr reindexing of context " + contextURI);

		Entry contextEntry = rm.getContextManager().getByEntryURI(contextURI);

		// Both purges below delete Solr documents for entries this reindex did not repost, and what it
		// reposts comes from context.getEntries(). An incomplete index makes that listing short, so
		// purging would delete the unlisted entries from the search index — data loss driven by a triple
		// nobody can parse. Reindex without purging instead: a stale document is recoverable, a deleted
		// one is not (ENTRYSTORE-1095).
		boolean purgeIsSafe = indexIsComplete(contextURI);

		if (purgeAllBeforeReindex && purgeIsSafe) {
			if (!clearSolrIndex(solrServer, null, contextEntry)) {
				log.warn("Pre-reindex purge of context {} failed; proceeding with reindex against potentially dirty index", contextURI);
			}
		}

		Date reindexStart = new Date();

		PrincipalManager pm = rm.getPrincipalManager();
		URI currentUser = pm.getAuthenticatedUserURI();
		try {
			pm.setAuthenticatedUserURI(pm.getAdminUser().getURI());
			URI lastIndexedEntryURI = postContextEntriesToQueue(contextURI);
			if (lastIndexedEntryURI != null) {
				if (!purgeAllBeforeReindex && purgeIsSafe) {
					purgeExecutor.submit(() -> {
						long deadline = System.nanoTime() + MAX_PURGE_WAIT_NANOS;
						try {
							// We need to wait until the last entry of the context is indexed, otherwise this would leave a gap of some time
							// (between milliseconds to seconds or even minutes) where the entries of that particular context are not in the index
							while (postQueueContains(lastIndexedEntryURI)
									&& System.nanoTime() < deadline
									&& !Thread.currentThread().isInterrupted()) {
								log.debug("Entries of context {} are still in submission queue, sleeping 5 seconds before attempting new purge of expired entries", contextURI);
								Thread.sleep(5000);
							}
							if (Thread.currentThread().isInterrupted()) {
								log.warn("Delayed purge of context {} interrupted (shutdown); expired entries were NOT removed",
										contextURI);
								return;
							}
							if (postQueueContains(lastIndexedEntryURI)) {
								log.warn("Delayed purge of context {} aborted after {} min wait; submission queue still contains {}",
										contextURI, TimeUnit.NANOSECONDS.toMinutes(MAX_PURGE_WAIT_NANOS), lastIndexedEntryURI);
								return;
							}
							if (clearSolrIndex(solrServer, reindexStart, contextEntry)) {
								log.info("Expired entries of context {} have been purged from the index", contextURI);
							} else {
								log.warn("Delayed purge of context {} did not clear expired entries; rerun reindex with purgeAllBeforeReindex=true if needed",
										contextURI);
							}
						} catch (InterruptedException e) {
							log.warn("Delayed purge of context {} interrupted before clearSolrIndex completed; expired entries were NOT removed",
									contextURI, e);
							Thread.currentThread().interrupt();
						} catch (RuntimeException e) {
							log.error("Unexpected {} during delayed purge of context {}; expired entries were NOT removed",
									e.getClass().getSimpleName(), contextURI, e);
						} catch (Error e) {
							// Not re-thrown: with submit() the Error is captured by FutureTask.run() and the
							// discarded Future means rethrow has no observable effect. Logging is the only signal.
							log.error("Fatal {} during delayed purge of context {}; expired entries were NOT removed",
									e.getClass().getSimpleName(), contextURI, e);
						}
					});
				}
				log.info("Finished Solr reindexing of context {}, took {} ms; the Solr submission queue may still contain yet to be processed documents", contextURI, new Date().getTime() - reindexStart.getTime());
			} else {
				log.debug("Solr reindexing of context {} could not be completed, either the context could not be loaded or (most likely) another process started reindexing the same context before the ongoing process was complete", contextURI);
			}
		} finally {
			pm.setAuthenticatedUserURI(currentUser);
		}
	}

	public boolean isIndexing() {
		return !reindexing.isEmpty();
	}

	public boolean isIndexing(URI contextURI) {
		return reindexing.containsKey(contextURI);
	}

	public Set<URI> getIndexingContexts() {
		// Snapshot under the map's monitor: Collections.synchronizedMap synchronizes
		// individual ops but iteration over a live keySet view is not thread-safe,
		// and the reindex executor mutates this map from another thread.
		synchronized (reindexing) {
			return Set.copyOf(reindexing.keySet());
		}
	}

	public long getPostQueueSize() {
		synchronized (postQueue) {
			return postQueue.size();
		}
	}

	private boolean isPostQueueEmpty() {
		synchronized (postQueue) {
			return postQueue.isEmpty();
		}
	}

	private boolean postQueueContains(URI entryURI) {
		synchronized (postQueue) {
			return postQueue.contains(entryURI);
		}
	}

	/**
	 * A13: wakes the submitter when work is enqueued. Must be called after releasing the
	 * postQueue/deleteQueue monitor to keep the queueSignal -> postQueue lock order the submitter's
	 * recheck-under-lock relies on.
	 */
	private void signalSubmitter() {
		synchronized (queueSignal) {
			queueSignal.notifyAll();
		}
	}

	/**
	 * Opens the submitter's work gate. Must be called by {@link org.entrystore.impl.RepositoryManagerImpl}
	 * once repository initialization has progressed far enough that entries may be loaded from a
	 * background thread: document building runs on the submitter and loads entries via the
	 * ContextManager, which must not happen concurrently with initialization. Idempotent.
	 */
	public void markRepositoryInitialized() {
		repositoryInitialized = true;
		signalSubmitter();
	}

	public long getDeleteQueueSize() {
		return deleteQueue.size();
	}

	/**
	 * Blocks until both queues are empty and no batch is in flight. Returns false only on thread
	 * interruption (typically JVM shutdown). Trade-off: a sustained Solr outage with a poison-pill
	 * batch re-queued by the {@code SolrServerException | IOException} retry path will block this
	 * call indefinitely; for EntryStore's single-instance ops model the operator's recourse is to
	 * kill the JVM and investigate.
	 *
	 * @return true if the submitter became quiescent, false if interrupted
	 */
	public boolean waitForQueueDrain() {
		while (getPostQueueSize() > 0 || !deleteQueue.isEmpty() || submitterInFlight.get()) {
			try {
				Thread.sleep(500);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return false;
			}
		}
		return true;
	}

	@Override
	public boolean ping() {
		try {
			SolrPingResponse pingResponse = this.solrServer.ping();
			if (pingResponse.getStatus() == 0) {
				return true;
			}
		} catch (SolrServerException | IOException e) {
			log.error(e.getMessage());
		}
		return false;
	}

	@Override
	public boolean isUp() {
		return ping() && documentSubmitter.isAlive() && delayedContextIndexer.isAlive() && !reindexExecutor.isShutdown();
	}

	/**
	 * This method was originally developed for ACL changes, but we also use it for project types now. If ACL changes
	 * are reverted within a short period of time then the context is removed from the reindexing queue again. This
	 * does not apply for other reindexing triggers such as a changed project type.
	 */
	public void submitContextForDelayedReindex(Entry contextEntry, Model entryGraph) {
		synchronized (delayedReindex) {
			IRI guestURI = valueFactory.createIRI(rm.getPrincipalManager().getGuestUser().getURI().toString());
			URI contextURI = contextEntry.getEntryURI();
			Model m = new LinkedHashModel(entryGraph);
			boolean newGuestReadable = m.contains(valueFactory.createIRI(contextEntry.getLocalMetadataURI().toString()), RepositoryProperties.Read, guestURI) ||
					m.contains(valueFactory.createIRI(contextEntry.getLocalMetadataURI().toString()), RepositoryProperties.Write, guestURI);
			if (delayedReindex.containsKey(contextURI) && (delayedReindex.get(contextURI).guestReadable != newGuestReadable)) {
				// the context has been switched back to the previous guest ACL and does not need to be reindexed anymore
				log.info("Removing context from delayed reindexing queue due to reverted ACL change within grace period");
				delayedReindex.remove(contextURI);
			} else {
				log.info("Enqueueing context for delayed reindexing due to ACL change");
				DelayedContextIndexerInfo info = new DelayedContextIndexerInfo();
				info.submitted = LocalDateTime.now();
				info.guestReadable = newGuestReadable;
				delayedReindex.put(contextURI, info);
			}
		}
	}

	/**
	 * Whether the context's URI index carries every entry, so a purge keyed on "not reposted by this
	 * reindex" is safe. Answers false rather than propagating if the context cannot be resolved at all,
	 * since a purge is not safe in that case either.
	 */
	private boolean indexIsComplete(URI contextURI) {
		String id = contextURI.toString().substring(contextURI.toString().lastIndexOf("/") + 1);
		Context context = rm.getContextManager().getContext(id);
		if (context == null) {
			log.warn("Context {} could not be resolved; skipping the reindex purge", contextURI);
			return false;
		}
		if (context.isIndexComplete()) {
			return true;
		}
		log.error("Context {} has an incomplete URI index, so entries missing from its listing would be "
				+ "deleted from the Solr index by the post-reindex purge. Reindexing without purging; "
				+ "expired documents may remain until the underlying data is repaired", contextURI);
		return false;
	}

	private URI postContextEntriesToQueue(URI contextURI) {
		String id = contextURI.toString().substring(contextURI.toString().lastIndexOf("/") + 1);
		ContextManager cm = rm.getContextManager();
		Context context = cm.getContext(id);
		if (context != null) {
			URI lastEntryURI = null;
			for (URI entryURI : context.getEntries()) {
				if (interrupted()) {
					log.info("Indexer thread received interrupt, stopping reindexing of " + contextURI);
					return null;
				}
				if (entryURI != null) {
					Entry entry;
					try {
						entry = cm.getEntry(entryURI);
					} catch (Exception e) {
						log.error("Unable to load entry with URI {} due to error: {}", entryURI, e.getMessage());
						continue;
					}
					if (entry == null) {
						log.warn("Unable to load entry with URI {}", entryURI);
						continue;
					}
					if (!entry.isDeleted() && !entry.getContext().isDeleted()) {
						log.info("Adding entry to Solr post queue: {}", entryURI);
						synchronized (postQueue) {
							postQueue.add(entryURI);
						}
						signalSubmitter();
					} else {
						log.debug("Not adding deleted entry to post queue: {}", entryURI);
					}
					lastEntryURI = entryURI;
				}
			}
			return lastEntryURI;
		}
		return null;
	}

	private void storeLiteralsWithLanguages(SolrInputDocument doc, Map<String, Set<String>> literals, String literalType) {
		final String missingLanguageString = "nolang";
		final String defaultString = "default";

		Set<String> alreadySetLanguages = new HashSet<>();

		for (String literal : literals.keySet()) {
			doc.addField(literalType, literal);

			// we also store title.{lang} as dynamic field to be able to
			// sort after titles in a specific language
			Set<String> literalLanguages = literals.get(literal);

			if (literalLanguages.isEmpty()) {
				if (!alreadySetLanguages.contains(missingLanguageString)) {
					doc.addField(String.format("%s.%s", literalType, missingLanguageString), literal);
					alreadySetLanguages.add(missingLanguageString);
				}
			} else {
				for (String language : literalLanguages) {
					if (language != null && language.equalsIgnoreCase(defaultSortLang) && !alreadySetLanguages.contains(defaultString)) {
						// if a default sorting language is configured, we create a field for that
						doc.addField(String.format("%s.%s", literalType, defaultString), literal);
						alreadySetLanguages.add(defaultString);
					}

					// we only want one title per language, otherwise sorting will not work
					if (!alreadySetLanguages.contains(language)) {
						doc.addField(String.format("%s.%s", literalType, language == null ? missingLanguageString : language), literal);
						alreadySetLanguages.add(language);
					}
				}
			}
		}
	}

	public SolrInputDocument constructSolrInputDocument(Entry entry, boolean extractFulltext) {
		return constructSolrInputDocument(entry, extractFulltext, null);
	}

	/**
	 * @param projectTypeCache optional per-batch cache of context-resource-URI to projectType (A7).
	 *                         Non-Context entries all read their context's projectType, so caching it
	 *                         avoids re-reading the context graph once per indexed entry. Pass null to
	 *                         disable (e.g. single-document indexing).
	 */
	private SolrInputDocument constructSolrInputDocument(Entry entry, boolean extractFulltext, Map<URI, String> projectTypeCache) {
		Model mdGraph = entry.getMetadataGraph();
		Model entryGraph = entry.getGraph();
		URI resourceURI = entry.getResourceURI();

		SolrInputDocument doc = new SolrInputDocument();

		// URI
		doc.setField("uri", entry.getEntryURI().toString());

		// resource URI
		doc.setField("resource", resourceURI.toString());

		// resource URI of the surrounding context
		doc.setField("context", entry.getContext().getEntry().getResourceURI().toString());

		// RDF type
		for (Statement value : entryGraph.filter(valueFactory.createIRI(resourceURI.toString()), RDF.TYPE, null)) {
			doc.addField("rdfType", value.getObject().stringValue());
		}
		for (Statement statement : mdGraph.filter(valueFactory.createIRI(resourceURI.toString()), RDF.TYPE, null)) {
			doc.addField("rdfType", statement.getObject().stringValue());
		}

		// creation date
		Date creationDate = entry.getCreationDate();
		if (creationDate != null) {
			doc.setField("created", creationDate);
		}

		// modification date
		Date modificationDate = entry.getModifiedDate();
		if (modificationDate != null) {
			doc.setField("modified", modificationDate);
		}

		// types
		doc.setField("graphType", entry.getGraphType().name());
		doc.setField("entryType", entry.getEntryType().name());
		doc.setField("resourceType", entry.getResourceType().name());

		// profile
		if (entry.getLocalMetadataURI() != null) {
			Set<IRI> profilePreds = new HashSet<>();
			profilePreds.add(valueFactory.createIRI("http://entryscape.com/terms/entityType"));
			profilePreds.add(valueFactory.createIRI("http://entrystore.org/terms/profile"));
			for (String profileURI : EntryUtil.getResourceValues(entryGraph, entry.getLocalMetadataURI(), profilePreds)) {
				doc.setField("profile", profileURI);
				break; // we only need the first match
			}
		} else {
			log.warn("Local metadata URI of entry is null: {}", entry.getEntryURI());
		}

		// project type (A7: cached per batch to avoid re-reading the context graph per entry)
		String projectType = resolveProjectType(entry, entryGraph, resourceURI, projectTypeCache);
		if (projectType != null) {
			doc.setField("projectType", projectType);
		}

		// creator
		URI creator = entry.getCreator();
		if (creator != null) {
			doc.setField("creator", creator.toString());
		}

		// contributors
		for (URI c : entry.getContributors()) {
			doc.addField("contributors", c.toString());
		}

		// lists
		for (URI l : entry.getReferringListsInSameContext()) {
			doc.addField("lists", l.toString());
		}

		// ACL: admin, metadata r/w, resource r/w
		try {
			for (URI p : entry.getAllowedPrincipalsFor(AccessProperty.Administer)) {
				doc.addField("acl.admin", p.toString());
			}
			for (URI p : entry.getAllowedPrincipalsFor(AccessProperty.ReadMetadata)) {
				doc.addField("acl.metadata.r", p.toString());
			}
			for (URI p : entry.getAllowedPrincipalsFor(AccessProperty.WriteMetadata)) {
				doc.addField("acl.metadata.rw", p.toString());
			}
			for (URI p : entry.getAllowedPrincipalsFor(AccessProperty.ReadResource)) {
				doc.addField("acl.resource.r", p.toString());
			}
			for (URI p : entry.getAllowedPrincipalsFor(AccessProperty.WriteResource)) {
				doc.addField("acl.resource.rw", p.toString());
			}
		} catch (IllegalArgumentException iae) {
			log.warn("Unable to index ACL for entry {}: {}", entry.getEntryURI().toString(), iae.getMessage());
		}

		// status
		URI status = entry.getStatus();
		if (status != null) {
			doc.setField("status", status.toString());
		}

		// titles
		Map<String, Set<String>> titles = EntryUtil.getTitles(mdGraph, resourceURI);
		if (titles != null && !titles.isEmpty()) {
			storeLiteralsWithLanguages(doc, titles, "title");
		}

		String firstName = EntryUtil.getFirstName(mdGraph, resourceURI);
		String lastName = EntryUtil.getLastName(mdGraph, resourceURI);
		String name = "";
		if (firstName != null) {
			name += firstName;
		}
		if (lastName != null) {
			name += " " + lastName;
		}
		if (!name.isEmpty()) {
			doc.addField("title", name);
		}

		// user name
		if (GraphType.User.equals(entry.getGraphType())) {
			User user = (org.entrystore.User) entry.getResource();
			if (user != null) {
				String username = user.getName();
				if (username != null) {
					doc.addField("username", username);
				}
			} else {
				log.warn("User resource of {} is null", entry.getEntryURI().toString());
			}
		}

		// context name
		if (GraphType.Context.equals(entry.getGraphType()) || GraphType.SystemContext.equals(entry.getGraphType())) {
			String contextName = rm.getContextManager().getName(entry.getResource().getURI());
			if (contextName != null) {
				doc.addField("contextname", contextName);
			}
		}

		// description
		Map<String, Set<String>> descriptions = EntryUtil.getDescriptions(mdGraph, resourceURI);
		if (descriptions != null && !descriptions.isEmpty()) {
			storeLiteralsWithLanguages(doc, descriptions, "description");
		}

		// tag.literal[.*]
		Map<String, Set<String>> tagLiterals = EntryUtil.getTagLiterals(mdGraph, resourceURI);
		if (tagLiterals != null) {
			storeLiteralsWithLanguages(doc, tagLiterals, "tag.literal");
		}

		// tag.uri
		for (String s : EntryUtil.getTagResources(mdGraph, resourceURI)) {
			doc.addField("tag.uri", s);
		}

		// language of the resource
		String dcLang = EntryUtil.getLabel(mdGraph, resourceURI, valueFactory.createIRI(NS.dc + "language"), null);
		if (dcLang != null) {
			doc.addField("lang", dcLang);
		}
		String dctLang = EntryUtil.getLabel(mdGraph, resourceURI, valueFactory.createIRI(NS.dcterms + "language"), null);
		if (dctLang != null) {
			doc.addField("lang", dctLang);
		}

		// email (foaf:mbox)
		String email = EntryUtil.getEmail(mdGraph, resourceURI);
		if (email != null) {
			doc.addField("email", email);
		}

		// publicly viewable metadata? A10: a non-throwing check with the same semantics as
		// checkAuthenticatedUserAuthorized (context-ACL inheritance included), avoiding an
		// AuthorizationException as control flow for every non-public entry during indexing.
		PrincipalManager pm = entry.getRepositoryManager().getPrincipalManager();
		boolean guestReadable = false;
		try {
			guestReadable = pm.isUserAuthorized(pm.getGuestUser().getURI(), entry, AccessProperty.ReadMetadata);
		} catch (IllegalArgumentException iae) {
			log.warn(iae.getMessage());
		}
		doc.setField("public", guestReadable);

		addGenericMetadataFields(doc, mdGraph, false);

		if (related) {
			addRelatedFields(doc, entry, mdGraph, resourceURI);
		}

		// Full text extraction using Apache Tika
        /* if (extractFulltext && EntryType.Local.equals(entry.getEntryType())
				&& ResourceType.InformationResource.equals(entry.getResourceType())
				&& entry.getResource() instanceof Data) {
			Data d = (Data) entry.getResource();
			File f = d.getDataFile();
			if (f != null && f.exists()) {
				String textContent = extractFulltext(f);
				if (textContent != null) {
					doc.addField("fulltext", textContent);
				}
			}
		} */

		return doc;
	}

	// A7 sentinel: distinguishes a cached "context has no projectType" from "not cached yet"
	// while remaining null-value-free so the cache can be a ConcurrentHashMap.
	private static final String NO_PROJECT_TYPE = "";

	private final IRI projectTypePredicate;

	private String resolveProjectType(Entry entry, Model entryGraph, URI resourceURI, Map<URI, String> cache) {
		boolean isContext = GraphType.Context.equals(entry.getGraphType());
		Model graphWithProjectType;
		URI resourceUriForProjectType;
		if (isContext) {
			// A context's projectType lives in its own graph and is not shared, so it is never cached.
			graphWithProjectType = entryGraph;
			resourceUriForProjectType = resourceURI;
		} else {
			Entry contextEntry = entry.getContext().getEntry();
			resourceUriForProjectType = contextEntry.getResourceURI();
			if (cache != null) {
				String cached = cache.get(resourceUriForProjectType);
				if (cached != null) {
					return NO_PROJECT_TYPE.equals(cached) ? null : cached;
				}
			}
			graphWithProjectType = contextEntry.getGraph();
		}

		String result = null;
		for (String projectTypeURI : EntryUtil.getResourceValues(graphWithProjectType, resourceUriForProjectType,
				Collections.singleton(projectTypePredicate))) {
			result = projectTypeURI;
			break; // we only need the first match
		}

		if (cache != null && !isContext) {
			cache.put(resourceUriForProjectType, result == null ? NO_PROJECT_TYPE : result);
		}
		return result;
	}

	private void addGenericMetadataFields(SolrInputDocument doc, Model metadata, boolean related) {
		if (doc == null || metadata == null) {
			throw new IllegalArgumentException("Neither SolrInputDocument nor Graph must be null");
		}

		String prefix = "";
		if (related) {
			prefix = "related.";
		}

		// All subject, predicates and objects in the metadata graph
		//
		// We also provide an index for all predicate-object tuples, stored in dynamic fields.
		for (Statement s : metadata) {
			// predicate
			String predString = s.getPredicate().stringValue();
			String predMD5Trunc8 = PREDICATE_MD5_CACHE.computeIfAbsent(predString,
					pred -> Hashing.hash(pred, HashType.MD5).substring(0, 8));

			// object
			if (s.getObject() instanceof IRI) {
				String objString = s.getObject().stringValue();
				if (!related) {
					addFieldValueOnce(doc,prefix + "metadata.object.uri", objString);
				}

				// predicate value is included in the parameter name, the object value is the field value
				addFieldValueOnce(doc,prefix + "metadata.predicate.uri." + predMD5Trunc8, objString);
			} else if (s.getObject() instanceof Literal l) {
				if (!related) {
					if (MetadataUtil.isStringLiteral(l)) { // we only index plain literals (human-readable text)
						addFieldValueOnce(doc,prefix + "metadata.object.literal", l.getLabel());
					}
				}

				// predicate value is included in the parameter name, the object value is the field value
				addFieldValueOnce(doc,prefix + "metadata.predicate.literal_s." + predMD5Trunc8, l.getLabel());

				// special handling of integer values, to be used for e.g., sorting
				if (MetadataUtil.isIntegerLiteral(l)) {
					try {
						// it's a single-value field, so we call setField instead of addField just in case there should be
						doc.setField(prefix + "metadata.predicate.integer." + predMD5Trunc8, l.longValue());
					} catch (NumberFormatException nfe) {
						log.warn("Unable to index integer literal: {}. (Subject: {}, Predicate: {}, Object: {})", nfe.getMessage(), s.getSubject(), predString, l.getLabel());
					}
				}

				if (MetadataUtil.isDateLiteral(l)) {
					try {
						doc.setField(prefix + "metadata.predicate.date." + predMD5Trunc8, dateToSolrDateString(l.calendarValue()));
					} catch (IllegalArgumentException iae) {
						log.warn("Unable to index date literal: {}. (Subject: {}, Predicate: {}, Object: {})", iae.getMessage(), s.getSubject(), predString, l.getLabel());
					}
				}

				// special handling of decimal/float/double values, to be used for e.g., numeric range queries and sorting
				if (MetadataUtil.isDecimalLiteral(l)) {
					try {
						doc.setField(prefix + "metadata.predicate.decimal." + predMD5Trunc8, l.doubleValue());
					} catch (NumberFormatException nfe) {
						log.warn("Unable to index decimal literal: {}. (Subject: {}, Predicate: {}, Object: {})", nfe.getMessage(), s.getSubject(), predString, l.getLabel());
					}
				}
			}
		}
	}

	private Set<Context> getGlobalRegularContexts() {
		Set<Context> cached = cachedGlobalRegularContexts;
		if (cached != null) {
			return cached;
		}
		Set<Context> contexts = new HashSet<>();
		ContextManager cm = rm.getContextManager();
		for (URI contextURI : cm.getEntries()) {
			Context c = cm.getContext(contextURI);
			if (c instanceof RegularContext) {
				contexts.add(c);
			}
		}
		cached = Set.copyOf(contexts);
		cachedGlobalRegularContexts = cached;
		return cached;
	}

	private void invalidateRelatedContextCacheIfNeeded(Entry entry) {
		if (relatedContainsGlobal && GraphType.Context.equals(entry.getGraphType())) {
			cachedGlobalRegularContexts = null;
		}
	}

	private void addRelatedFields(SolrInputDocument doc, Entry entry, Model mdGraph, URI resourceURI) {
		if (doc == null || entry == null) {
			throw new IllegalArgumentException("Neither SolrInputDocument nor Entry must be null");
		}

		// A8: the global set of RegularContexts is cached and invalidated when a Context entry is
		// added or removed (see invalidateRelatedContextCacheIfNeeded), instead of being enumerated
		// once per indexed entry.
		Set<Context> contexts = relatedContainsGlobal ? getGlobalRegularContexts() : Set.of();

		Set<Entry> relatedEntries = new HashSet<>();
		for (IRI relProp : relatedProperties.keySet()) {
			List<String> relatedURIs = EntryUtil.getResourceValues(mdGraph, resourceURI, Collections.singleton(relProp));
			if (relatedURIs.isEmpty()) {
				continue;
			}
			if (relatedContainsGlobal && relatedProperties.get(relProp)) {
				for (Context context : contexts) {
					for (String relEntURI : relatedURIs) {
						relatedEntries.addAll(context.getByResourceURI(URI.create(relEntURI)));
					}
				}
			} else {
				for (String relEntURI : relatedURIs) {
					relatedEntries.addAll(entry.getContext().getByResourceURI(URI.create(relEntURI)));
				}
			}
		}

		if (!relatedEntries.isEmpty()) {
			Set<URI> mainEntryACL = entry.getAllowedPrincipalsFor(AccessProperty.ReadMetadata);
			for (Entry relE : relatedEntries) {
				if (mainEntryACL.equals(relE.getAllowedPrincipalsFor(AccessProperty.ReadMetadata))) {
					log.debug("Adding " + relE.getEntryURI() + " to related property index of " + entry.getEntryURI());
					addGenericMetadataFields(doc, relE.getMetadataGraph(), true);
				} else {
					log.debug("ACLs of " + entry.getEntryURI() + " and " + relE.getEntryURI() + " do not match, not adding to related property index");
				}
			}
		}
	}

	private void addFieldValueOnce(SolrInputDocument doc, String name, Object value) {
		Collection<Object> fieldValues = doc.getFieldValues(name);
		if (fieldValues == null || !fieldValues.contains(value)) {
			doc.addField(name, value);
		}
	}

	private String dateToSolrDateString(XMLGregorianCalendar c) {
		if (c.getTimezone() == DatatypeConstants.FIELD_UNDEFINED) {
			c.setTimezone(0);
		}
		return SOLR_DATE_FORMATTER.format(c.toGregorianCalendar().toInstant());
	}

	public void postEntry(Entry entry) {
		// A1: enqueue the URI only. Building the document (metadata-graph read + ACL evaluation)
		// is deferred to the submitter thread in drainAndBuildPostBatch, because postEntry runs
		// under synchronized(repository) -> synchronized(repositoryListeners) via
		// fireRepositoryEvent. Deletion is re-checked at build time; removeEntry removes the URI
		// again if the entry is deleted before it drains, so a deleted entry is never re-indexed.
		URI entryURI = entry.getEntryURI();
		invalidateRelatedContextCacheIfNeeded(entry);
		synchronized (postQueue) {
			log.info("Adding entry to Solr post queue: {}", entryURI);
			postQueue.add(entryURI);
		}
		signalSubmitter();
	}

	public void removeEntry(Entry entry) {
		URI entryURI = entry.getEntryURI();
		invalidateRelatedContextCacheIfNeeded(entry);

		synchronized (postQueue) {
			// we make sure that the entry is not added again after deletion
			// if the queues are handled at different times
			postQueue.remove(entryURI);
		}

		synchronized (deleteQueue) {
			log.info("Adding entry to Solr delete queue: " + entryURI);
			deleteQueue.add(entryURI);
		}
		signalSubmitter();

		// if entry is a context, also remove all entries inside. A15: run the (potentially slow)
		// context-wide purge on the background purge executor instead of the caller's request thread.
		if (GraphType.Context.equals(entry.getGraphType())) {
			purgeExecutor.submit(() -> {
				if (!clearSolrIndex(solrServer, null, entry)) {
					log.warn("Context-removal purge for context {} failed; expired Solr documents may remain", entry.getEntryURI());
				}
			});
		}
	}

	private long sendQueryForEntryURIs(SolrQuery query, Set<URI> result, List<FacetField> facetFields, SolrClient solrServer, int offset) {
		if (query == null) {
			throw new IllegalArgumentException("Query object must not be null");
		}

		if (offset > -1) {
			query.setStart(offset);
		}

		// We only need the "uri" field in the response,
		// so we skip the rest (default is "*")
		query.setFields("uri");

		long hits = -1;
		QueryResponse r;
		try {
			r = solrServer.query(query);
			r.getElapsedTime();
			if (r.getFacetFields() != null) {
				facetFields.addAll(r.getFacetFields());
			}
			SolrDocumentList docs = r.getResults();
			hits = docs.getNumFound();
			for (SolrDocument solrDocument : docs) {
				if (solrDocument.containsKey("uri")) {
					String uri = (String) solrDocument.getFieldValue("uri");
					if (uri != null) {
						result.add(URI.create(uri));
					}
				}
			}
			log.debug("Query time: {} ms, elapsed time: {} ms", r.getQTime(), r.getElapsedTime());
		} catch (SolrServerException | IOException e) {
			if (e instanceof SolrServerException && ((SolrServerException) e).getRootCause() instanceof IllegalArgumentException) {
				log.info(e.getMessage());
			} else {
				log.error(e.getMessage());
			}
		}

		return hits;
	}

	public QueryResult sendQuery(SolrQuery query) throws SolrException {
		Set<Entry> result = new LinkedHashSet<>();
		long hits = -1;
		long inaccessibleHits = 0;
		int limit = query.getRows();
		int offset = query.getStart();
		List<FacetField> facetFields = new ArrayList<>();
		query.setIncludeScore(true);
		int resultFillIteration = 0;
		do {
			if (resultFillIteration > 0) {
				// We have a small limit and we don't get enough results with permissive ACL per iteration,
				// so we need to increase the result size window, but not the result limit itself
				// (i.e., we only change the rows towards Solr, but not the query limit of EntryStore).
				// We only need to do this once, on the first refill iteration.
				if (resultFillIteration == 1 && limit <= 10) {
					query.setRows(100);
				}
				if (resultFillIteration >= 10) {
					log.warn("Breaking after 10 result fill iterations to prevent too many loops");
					break;
				}
				offset += Math.min(limit, 50);
				log.warn("Increasing offset to " + offset + " in an attempt to fill the result limit");
			}
			resultFillIteration++;
			Set<URI> entryURIs = new LinkedHashSet<>();
			hits = sendQueryForEntryURIs(query, entryURIs, facetFields, solrServer, offset);
			Date before = new Date();
			for (URI uri : entryURIs) {
				try {
					Entry entry = rm.getContextManager().getEntry(uri);
					if (entry != null) {
						if (entry.isDeleted()) {
							log.warn("Deleted entry {} is still in Solr index, removing it now", uri);
							removeEntry(entry);
							throw new IllegalStateException("Cannot return deleted entry in search result: " + uri);
						}
						PrincipalManager pm = entry.getRepositoryManager().getPrincipalManager();
						// If linkReference or reference to an entry in the same repository
						// check that the referenced metadata is accessible.
						if ((entry.getEntryType() == EntryType.Reference || entry.getEntryType() == EntryType.LinkReference)
								&& entry.getCachedExternalMetadata() instanceof LocalMetadataWrapper) {
							Entry refEntry = entry.getRepositoryManager().getContextManager().getEntry(entry.getExternalMetadataURI());
							if (refEntry != null) {
								pm.checkAuthenticatedUserAuthorized(refEntry, AccessProperty.ReadMetadata);
							} else {
								log.error("Entry {} contains reference to non-existing resource.", entry.getEntryURI());
							}
						} else {
							// Check that the local metadata is accessible.
							pm.checkAuthenticatedUserAuthorized(entry, AccessProperty.ReadMetadata);
						}
						result.add(entry);
						if (result.size() == limit) {
							// we have enough results
							break;
						}
					}
				} catch (AuthorizationException | IllegalStateException e) {
					inaccessibleHits++;
				}
			}
			log.info("Entry fetching took " + (new Date().getTime() - before.getTime()) + " ms");
		} while ((limit > result.size()) && (hits > (offset + limit)));

		long adjustedHitCount = hits - inaccessibleHits;

		// We prevent possible information leakage (i.e., "Can we get to know whether a resource
		// with a certain name exists even though we are not allowed to access it?") by manually
		// setting the hit count to zero in certain conditions. Should protect against malicious
		// probing requests.
		//
		// Test if the condition covers too much and add "offset == 0 &&" if necessary
		if (result.isEmpty() && hits > 0) {
			adjustedHitCount = 0;
		}

		if (adjustedHitCount < 0) {
			log.warn("Adjusted hit count is negative, this should not happen");
			// TODO perhaps we should just set it to a high number in order not to break clients, e.g. Integer.MAX_VALUE
		}

		return new QueryResult(result, adjustedHitCount, facetFields);
	}

	public SolrDocument fetchDocument(String uri) {
		try {
			SolrQuery q = new SolrQuery("uri:" + ClientUtils.escapeQueryChars(uri));
			q.setStart(0);
			q.setRows(1);
			QueryResponse r = solrServer.query(q);
			SolrDocumentList docs = r.getResults();
			if (!docs.isEmpty()) {
				return docs.getFirst();
			}
		} catch (SolrServerException | IOException e) {
			log.error(e.getMessage());
		}
		return null;
	}

	public static String extractFulltext(File f) {
		return null;

		// FIXME this method works but is deactivated. the needed apache tika
		// framework has many dependencies so it is not activated in the pom.xml
		// of scam-core for now. please activate it there before enabling the
		// following code again.

		/*
		 * InputStream stream = null; String textContent = null; String mimeType
		 * = null; try { TikaConfig tc = TikaConfig.getDefaultConfig();
		 * InputStream mimeIS = null; try { mimeIS = Files.newInputStream(f.toPath());
		 * mimeType = tc.getMimeRepository().getMimeType(mimeIS).getName(); }
		 * finally { if (mimeIS != null) { mimeIS.close(); } }
		 *
		 * if (mimeType != null) { stream = new BufferedInputStream(
		 * Files.newInputStream(f.toPath())); Parser parser = tc.getParser(mimeType); if
		 * (parser != null) { ContentHandler handler = new BodyContentHandler();
		 * try { log.info("Parsing document with MIME type " + mimeType + ": " +
		 * f.toString()); parser.parse(stream, handler, new Metadata(), new
		 * ParseContext()); textContent = handler.toString(); } catch (Exception
		 * e) { log.error("Unable to parse document: " + e.getMessage()); } }
		 * else { log.warn("Unable to detect parser for MIME type " + mimeType);
		 * } } else { log.warn("Unable to detect the MIME type"); } } catch
		 * (IOException e) { log.error(e.getMessage()); } finally { try { if
		 * (stream != null) { stream.close(); } } catch (IOException e) {
		 * log.error(e.getMessage()); } } return textContent;
		 */
	}

}
