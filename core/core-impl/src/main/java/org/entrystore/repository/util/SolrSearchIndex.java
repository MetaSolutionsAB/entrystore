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

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.apache.solr.client.solrj.RemoteSolrException;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrRequest;
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
import org.entrystore.repository.CorruptEntryException;
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
import java.util.Set;
import java.util.StringJoiner;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static java.lang.Thread.interrupted;


/**
 * @author Hannes Ebner
 */
public class SolrSearchIndex implements SearchIndex {

	private static final Logger log = LoggerFactory.getLogger(SolrSearchIndex.class);

	private static final int BATCH_SIZE_ADD = 100;

	private static final int BATCH_SIZE_DELETE = 100;

	private static final int SOLR_COMMIT_WITHIN = 1000;

	private static final int SOLR_COMMIT_WITHIN_MAX = 10000;

	private static final long EXECUTOR_SHUTDOWN_TIMEOUT_SECONDS = 10;

	private static final long MAX_PURGE_WAIT_NANOS = TimeUnit.MINUTES.toNanos(5);

	/**
	 * Number of distinct entry failure causes per context reindex that are logged with a stack trace. Entries
	 * of one context tend to fail for a shared cause, and one trace per entry would flood the log; later
	 * failures with an already traced cause, and all failures beyond this limit, are logged as one line.
	 */
	private static final int MAX_ENTRY_FAILURE_TRACES = 5;

	/**
	 * Number of attempts to reindex a context whose reindex fails as a whole during a synchronous reindex.
	 */
	private static final int MAX_CONTEXT_REINDEX_ATTEMPTS = 2;

	private static final DateTimeFormatter SOLR_DATE_FORMATTER =
			DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

	private final String defaultSortLang;

	private final boolean extractFulltext;

	private boolean related;

	private Map<IRI, Boolean> relatedProperties = null;

	private boolean relatedContainsGlobal = false;

	private final RepositoryManager rm;

	private final SolrClient solrServer;

	private final Thread documentSubmitter;

	private final Thread delayedContextIndexer;

	private final Cache<URI, SolrInputDocument> postQueue = Caffeine.newBuilder().build();

	/**
	 * Entries whose documents are to be removed from the index, in the order in which they were queued. A set, so
	 * that dropping the pending deletion of an entry when its document is queued does not scan all pending
	 * deletions.
	 */
	private final Set<URI> deleteQueue = Collections.synchronizedSet(new LinkedHashSet<>());

	private final Map<URI, Future> reindexing = Collections.synchronizedMap(new HashMap<>());

	private final ExecutorService reindexExecutor = Executors.newSingleThreadExecutor();

	private final ExecutorService purgeExecutor = Executors.newVirtualThreadPerTaskExecutor();

	// True while the submitter is between drain and process/requeue — observers awaiting a quiescent
	// queue must wait for this to clear too, otherwise an in-flight batch can be missed.
	private final AtomicBoolean submitterInFlight = new AtomicBoolean();

	// Documents Solr rejected on an add batch. Startup compares it around the initial reindex so a schema
	// mismatch cannot end with an empty index whose version markers say it is current.
	private final AtomicLong rejectedDocuments = new AtomicLong();

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
					postQueue.cleanUp();
					boolean batchFailed = false;

					if (postQueue.estimatedSize() > 0 || !deleteQueue.isEmpty()) {

						if (!deleteQueue.isEmpty()) {
							batchFailed = processDeleteBatch();
						}

						if (postQueue.estimatedSize() > 0 && !Thread.currentThread().isInterrupted()) {
							batchFailed = processAddBatch() || batchFailed;
						}

						if (batchFailed) {
							sleepOrShutdown(FAILURE_COOLDOWN_MS, "cooldown");
						}

					} else {
						sleepOrShutdown(500, "idle wait");
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

				StringJoiner joiner = new StringJoiner(" OR ", "uri:(", ")");
				deleteBatch.forEach(uri -> joiner.add(ClientUtils.escapeQueryChars(uri.toString())));

				UpdateRequest delReq = new UpdateRequest();
				delReq.deleteByQuery(joiner.toString());
				delReq.setCommitWithin(SOLR_COMMIT_WITHIN);

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
				Map<URI, SolrInputDocument> addBatch = drainPostQueue();
				if (addBatch.isEmpty()) {
					return false;
				}

				UpdateRequest addReq = new UpdateRequest();
				addBatch.values().forEach(addReq::add);

				postQueue.cleanUp();
				if (postQueue.estimatedSize() > BATCH_SIZE_ADD * 5) {
					addReq.setCommitWithin(SOLR_COMMIT_WITHIN_MAX);
				} else {
					addReq.setCommitWithin(SOLR_COMMIT_WITHIN);
				}

				for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
					try {
						log.info("Sending {} entries to Solr (attempt {}/{}), {} entries remaining in post queue",
								addBatch.size(), attempt, MAX_RETRIES, postQueue.estimatedSize());
						addReq.process(solrServer);
						return false;
					} catch (RemoteSolrException e) {
						log.error("Solr rejected {} entries (HTTP {}, discarding batch). URIs: {}",
								addBatch.size(), e.code(), addBatch.keySet(), e);
						rejectedDocuments.addAndGet(addBatch.size());
						return false;
					} catch (RuntimeException e) {
						requeueAdds(addBatch);
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
							requeueAdds(addBatch);
							return true;
						}
					}
				}

				log.error("Permanently failed to send {} entries to Solr after {} attempts, re-queuing", addBatch.size(), MAX_RETRIES);
				requeueAdds(addBatch);
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
				Iterator<URI> it = deleteQueue.iterator();
				while (batch.size() < BATCH_SIZE_DELETE && it.hasNext()) {
					batch.add(it.next());
					it.remove();
				}
			}
			return batch;
		}

		/**
		 * Drains up to {@link SolrSearchIndex#BATCH_SIZE_ADD} entries from the post queue.
		 */
		private Map<URI, SolrInputDocument> drainPostQueue() {
			Map<URI, SolrInputDocument> batch = new HashMap<>();
			synchronized (postQueue) {
				ConcurrentMap<URI, SolrInputDocument> postQueueMap = postQueue.asMap();
				Iterator<URI> it = postQueueMap.keySet().iterator();
				while (batch.size() < BATCH_SIZE_ADD && it.hasNext()) {
					URI key = it.next();
					SolrInputDocument doc = postQueueMap.get(key);
					postQueueMap.remove(key, doc);
					if (doc == null) {
						log.warn("Value for key {} is null in Solr submit queue", key);
						continue;
					}
					batch.put(key, doc);
				}
			}
			return batch;
		}

		/**
		 * Puts the deletions of a failed batch back into the delete queue, except those of entries with a queued
		 * document: that document was queued after the deletion, since queueing a deletion drops the entry's
		 * queued document, so the deletion must not remove it.
		 */
		private void requeueDeletes(List<URI> batch) {
			synchronized (postQueue) {
				synchronized (deleteQueue) {
					for (URI entryURI : batch) {
						if (postQueue.getIfPresent(entryURI) == null) {
							deleteQueue.add(entryURI);
						}
					}
				}
			}
		}

		private void requeueAdds(Map<URI, SolrInputDocument> batch) {
			batch.forEach((k, v) -> postQueue.asMap().putIfAbsent(k, v));
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

		/** Optional BCP 47 language range: literal facets keep only labels occurring in it, or untagged. */
		public String lang;

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
							reindex(contextURI);
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
		req.setCommitWithin(SOLR_COMMIT_WITHIN);
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
		req.setCommitWithin(SOLR_COMMIT_WITHIN);
		try {
			req.process(solrServer);
			return true;
		} catch (SolrServerException | IOException e) {
			log.error("clearSolrIndex failed for query {}: {}", deleteQuery, e.getMessage(), e);
			return false;
		}
	}

	/**
	 * Re-indexes every context in Solr, one indexing task per context, cancelling a task already
	 * running for the same context. Failures are logged by the tasks and not reported to the caller;
	 * see {@link #reindex(URI)}.
	 */
	public void reindex() {
		for (URI contextURI : listContextsAsAdmin()) {
			reindex(contextURI);
		}
	}

	/**
	 * Re-indexes a context and all of its entries in Solr. Starts a new indexing task and cancels an
	 * eventually existing one for the same context. Expired documents are purged after the context
	 * has been reposted; see {@link #reindexSync(URI)}.
	 *
	 * @param contextURI The URI of the context to be re-indexed, never null.
	 */
	public void reindex(URI contextURI) {
		if (contextURI == null) {
			throw new IllegalArgumentException("Context URI must not be null");
		}
		synchronized (reindexing) {
			if (reindexing.containsKey(contextURI)) {
				Future existingIndexer = reindexing.get(contextURI);
				if (!existingIndexer.isDone()) {
					log.info("Cancelling existing indexer thread for {}", contextURI);
					existingIndexer.cancel(true);
				}
				reindexing.remove(contextURI);
			}
			AtomicReference<Future<?>> self = new AtomicReference<>();
			Future<?> indexer = reindexExecutor.submit(() -> {
				try {
					reindexSync(contextURI);
				} catch (RuntimeException | Error e) {
					// The Future is never inspected, so without this the failure would vanish without a trace.
					log.error("Reindexing of context {} failed", contextURI, e);
				} finally {
					// Remove only this task's mapping: a cancelled task that is still unwinding must not remove
					// the mapping of its replacement. Acquire the monitor before reading self, so the submitter
					// has assigned the future and installed its mapping even if this task finishes immediately.
					synchronized (reindexing) {
						reindexing.remove(contextURI, self.get());
					}
				}
			});
			self.set(indexer);
			reindexing.put(contextURI, indexer);
		}
	}

	/**
	 * Re-indexes all contexts in the calling thread, so that a single corrupt context does not prevent the
	 * remaining contexts from being indexed:
	 * <ul>
	 *     <li>A context whose reindex fails as a whole is logged and retried once after all other contexts, since
	 *     the failure may be transient, e.g. an I/O error of the store. If it fails again, it is counted as failed.
	 *     Besides runtime exceptions this covers {@link StackOverflowError}, which corrupt data can cause through
	 *     unbounded recursion and after which the JVM is usable again; other errors are propagated.</li>
	 *     <li>A context that cannot be resolved and entries that cannot be loaded or indexed are logged and
	 *     counted.</li>
	 * </ul>
	 * An interrupted reindex stops, and the interrupt status of the calling thread is kept.
	 *
	 * @return the number of contexts and entries that could not be indexed, and whether the reindex was
	 * interrupted before all contexts were processed
	 */
	public ReindexResult reindexSync() {
		if (solrServer == null) {
			log.warn("Ignoring request as Solr is not used by this instance");
			return new ReindexResult(0, 0, false);
		}
		int failedContexts = 0;
		int failedEntries = 0;
		if (Thread.currentThread().isInterrupted()) {
			return new ReindexResult(0, 0, true);
		}
		Collection<URI> pendingContexts = listContextsAsAdmin();
		for (int attempt = 1; attempt <= MAX_CONTEXT_REINDEX_ATTEMPTS && !pendingContexts.isEmpty(); attempt++) {
			List<URI> abortedContexts = new ArrayList<>();
			for (URI contextURI : pendingContexts) {
				if (Thread.currentThread().isInterrupted()) {
					return new ReindexResult(failedContexts + abortedContexts.size(), failedEntries, true);
				}
				ContextPostResult posted;
				try {
					posted = reindexContext(contextURI);
				} catch (RuntimeException | StackOverflowError e) {
					abortedContexts.add(contextURI);
					if (Thread.currentThread().isInterrupted()) {
						log.info("Reindexing of context {} was interrupted; stopping without retry", contextURI);
						return new ReindexResult(failedContexts + abortedContexts.size(), failedEntries, true);
					}
					if (attempt < MAX_CONTEXT_REINDEX_ATTEMPTS) {
						log.error("Reindexing of context {} failed; it is retried after the remaining contexts",
								contextURI, e);
					} else {
						log.error("Reindexing of context {} failed again, giving up", contextURI, e);
					}
					continue;
				}
				failedEntries += posted.notIndexedEntries();
				boolean interrupted = switch (posted.outcome()) {
					case COMPLETED -> false;
					case UNRESOLVED -> {
						failedContexts++;
						yield false;
					}
					case INTERRUPTED -> true;
				};
				if (interrupted || Thread.currentThread().isInterrupted()) {
					return new ReindexResult(failedContexts + abortedContexts.size(), failedEntries, true);
				}
			}
			pendingContexts = abortedContexts;
		}
		return new ReindexResult(failedContexts + pendingContexts.size(), failedEntries,
				Thread.currentThread().isInterrupted());
	}

	private Set<URI> listContextsAsAdmin() {
		PrincipalManager pm = rm.getPrincipalManager();
		URI currentUser = pm.getAuthenticatedUserURI();
		try {
			pm.setAuthenticatedUserURI(pm.getAdminUser().getURI());
			return rm.getContextManager().getEntries();
		} finally {
			pm.setAuthenticatedUserURI(currentUser);
		}
	}

	/**
	 * Re-indexes a context: every entry is queued for reposting on the calling thread, and a purge of
	 * the documents this reindex did not repost is scheduled to run once the last queued entry has been
	 * submitted, so search never sees a gap. The method returns before the queue drains or the purge
	 * runs. The conditions under which no purge happens are listed at
	 * {@link #purgeExpiredDocumentsAfterReindex}; the purge is abandoned after MAX_PURGE_WAIT_NANOS or on
	 * shutdown.
	 *
	 * @param contextURI The URI of the context to be re-indexed, never null.
	 */
	public void reindexSync(URI contextURI) {
		if (solrServer == null) {
			log.warn("Ignoring request as Solr is not used by this instance");
			return;
		}
		reindexContext(contextURI);
	}

	private ContextPostResult reindexContext(URI contextURI) {
		if (contextURI == null) {
			throw new IllegalArgumentException("Context URI must not be null");
		}

		log.info("Starting Solr reindexing of context " + contextURI);

		Entry contextEntry = rm.getContextManager().getByEntryURI(contextURI);

		// no purge on an incomplete URI index: it would delete the unlisted entries (ENTRYSTORE-1095)
		boolean purgeIsSafe = indexIsComplete(contextURI);

		Date reindexStart = new Date();

		PrincipalManager pm = rm.getPrincipalManager();
		URI currentUser = pm.getAuthenticatedUserURI();
		try {
			pm.setAuthenticatedUserURI(pm.getAdminUser().getURI());
			ContextPostResult posted = postContextEntriesToQueue(contextURI);
			switch (posted.outcome()) {
				case COMPLETED -> log.info("Finished Solr reindexing of context {}, took {} ms; the Solr submission"
						+ " queue may still contain yet to be processed documents",
						contextURI, new Date().getTime() - reindexStart.getTime());
				case UNRESOLVED -> log.warn("Context {} could not be resolved; its entries were not reindexed and its"
						+ " documents in the index were left unchanged", contextURI);
				case INTERRUPTED -> log.info(
						"Reindexing of context {} was interrupted; expired documents are not purged", contextURI);
			}
			purgeExpiredDocumentsAfterReindex(contextURI, contextEntry, reindexStart, purgeIsSafe, posted);
			return posted;
		} finally {
			pm.setAuthenticatedUserURI(currentUser);
		}
	}

	/**
	 * Removes the documents of a context that were indexed before its reindex started, as soon as the entry that
	 * the reindex posted last has left the submission queue. Nothing is purged if
	 * <ul>
	 *     <li>the context's URI index is incomplete, because the purge would remove the documents of the entries
	 *     missing from its listing (ENTRYSTORE-1095),</li>
	 *     <li>the context could not be resolved or its reindex was interrupted,</li>
	 *     <li>entries of the context could not be loaded because of a failure that is not caused by their data,
	 *     e.g. of the store, or loaded but could not be indexed, because their previous documents would be removed
	 *     without being replaced although the entries may still be loadable,</li>
	 *     <li>the context lists no entries, which saves a Solr request for each empty context, or</li>
	 *     <li>the context entry could not be loaded, because the delete query would then not be restricted to the
	 *     context.</li>
	 * </ul>
	 * Entries that are corrupt or cannot be found do not prevent the purge: they cannot be returned by a search, and
	 * the purge removes the documents of entries that cannot be found, see {@link #postContextEntriesToQueue(URI)}.
	 */
	private void purgeExpiredDocumentsAfterReindex(URI contextURI, Entry contextEntry, Date reindexStart,
			boolean purgeIsSafe, ContextPostResult posted) {
		if (!purgeIsSafe || posted.outcome() != ContextPostOutcome.COMPLETED) {
			return;
		}
		if (posted.keptEntries() > 0) {
			log.warn("Not purging expired documents of context {} because {} of its entries could not be indexed;"
					+ " their previously indexed documents are kept", contextURI, posted.keptEntries());
			return;
		}
		if (posted.listedEntries() == 0) {
			return;
		}
		if (contextEntry == null) {
			log.warn("Not purging expired documents of context {} because its context entry could not be loaded",
					contextURI);
			return;
		}
		URI lastQueuedEntryURI = posted.lastQueuedEntryURI();
		purgeExecutor.submit(() -> {
			long deadline = System.nanoTime() + MAX_PURGE_WAIT_NANOS;
			try {
				// We need to wait until the last entry of the context is indexed, otherwise this would leave a gap of some time
				// (between milliseconds to seconds or even minutes) where the entries of that particular context are not in the index
				while (isQueued(lastQueuedEntryURI)
						&& System.nanoTime() < deadline
						&& !Thread.currentThread().isInterrupted()) {
					log.debug("Entries of context {} are still in submission queue, sleeping 5 seconds before attempting new purge of expired entries", contextURI);
					Thread.sleep(5000);
					postQueue.cleanUp();
				}
				if (Thread.currentThread().isInterrupted()) {
					log.warn("Delayed purge of context {} interrupted (shutdown); expired entries were NOT removed",
							contextURI);
					return;
				}
				if (isQueued(lastQueuedEntryURI)) {
					log.warn("Delayed purge of context {} aborted after {} min wait; submission queue still contains {}",
							contextURI, TimeUnit.NANOSECONDS.toMinutes(MAX_PURGE_WAIT_NANOS), lastQueuedEntryURI);
					return;
				}
				if (clearSolrIndex(solrServer, reindexStart, contextEntry)) {
					log.info("Expired entries of context {} have been purged from the index", contextURI);
				} else {
					log.warn("Delayed purge of context {} did not clear expired entries; rerun the reindex if needed",
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

	private boolean isQueued(URI entryURI) {
		return entryURI != null && postQueue.asMap().containsKey(entryURI);
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
		postQueue.cleanUp();
		return postQueue.estimatedSize();
	}

	/** Documents Solr has rejected since startup; a growing value means documents are silently missing from the index. */
	public long getRejectedDocumentCount() {
		return rejectedDocuments.get();
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
		String id = URISplit.getLastSegment(contextURI.toString());
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

	private enum ContextPostOutcome {
		/** All entries of the context were processed; some of them may have failed. */
		COMPLETED,
		/** The context could not be resolved, so none of its entries were processed. */
		UNRESOLVED,
		/** The reindex was interrupted before all entries of the context were processed. */
		INTERRUPTED
	}

	/**
	 * Result of posting the entries of a context to the submission queue. All entries that could not be posted
	 * count as not indexed. The documents of corrupt entries are removed from the index right away and those of
	 * unresolved entries by the purge of the context's expired documents, while unloadable and unindexable entries
	 * keep their previous documents and prevent that purge.
	 *
	 * @param outcome            whether all entries of the context were processed
	 * @param lastQueuedEntryURI the entry that was posted last, or null if none was posted
	 * @param loadedEntries      entries that were loaded, whether they were then posted, skipped as deleted or
	 *                           could not be indexed
	 * @param corruptEntries     entries whose loading failed because of their data, see
	 *                           {@link #isCausedByCorruptData(Throwable)}
	 * @param unresolvedEntries  entries that are listed in the context but were not found when loading them
	 * @param unloadableEntries  entries whose loading failed for another reason, e.g. an error of the store
	 * @param unindexableEntries entries that were loaded but whose Solr document could not be constructed
	 */
	private record ContextPostResult(ContextPostOutcome outcome, URI lastQueuedEntryURI, int loadedEntries,
			int corruptEntries, int unresolvedEntries, int unloadableEntries, int unindexableEntries) {

		static ContextPostResult unresolved() {
			return new ContextPostResult(ContextPostOutcome.UNRESOLVED, null, 0, 0, 0, 0, 0);
		}

		/**
		 * @return the entries that were processed, whether they could be loaded or not
		 */
		int listedEntries() {
			return loadedEntries + corruptEntries + unresolvedEntries + unloadableEntries;
		}

		/**
		 * @return the entries that could not be indexed and whose documents are removed from the index
		 */
		int removedEntries() {
			return corruptEntries + unresolvedEntries;
		}

		/**
		 * @return the entries that could not be indexed and keep their previous documents
		 */
		int keptEntries() {
			return unloadableEntries + unindexableEntries;
		}

		int notIndexedEntries() {
			return removedEntries() + keptEntries();
		}

	}

	/**
	 * Posts the entries of a context to the submission queue. The documents of entries that cannot be loaded
	 * because of their data are removed from the index: they would otherwise keep matching queries although their
	 * entries cannot be returned, and a reindex restores them once the data is repaired. Entries that cannot be
	 * found are left to the purge of expired documents, which only removes documents indexed before the reindex
	 * started: an entry that is listed but not yet committed while it is being created cannot be found either, and
	 * removing its document right away could remove the document that its creation posts. Entries whose loading
	 * fails for another reason, e.g. a transient error of the store, keep their documents.
	 * <p>
	 * Loading runs as admin, so a failure caused by corrupt data is attributed by its cause chain alone: no
	 * principals are loaded, and the context entry was already loaded to list the entries. Building a document
	 * checks guest access, which loads all principals, so a corrupt principal makes every entry unindexable until it
	 * is repaired.
	 */
	private ContextPostResult postContextEntriesToQueue(URI contextURI) {
		String id = URISplit.getLastSegment(contextURI.toString());
		ContextManager cm = rm.getContextManager();
		Context context = cm.getContext(id);
		if (context == null) {
			return ContextPostResult.unresolved();
		}
		URI lastQueuedEntryURI = null;
		int loadedEntries = 0;
		int corruptEntries = 0;
		int unresolvedEntries = 0;
		int unloadableEntries = 0;
		int unindexableEntries = 0;
		Set<String> tracedFailureCauses = new HashSet<>();
		for (URI entryURI : context.getEntries()) {
			if (Thread.currentThread().isInterrupted()) {
				return new ContextPostResult(ContextPostOutcome.INTERRUPTED, lastQueuedEntryURI, loadedEntries,
						corruptEntries, unresolvedEntries, unloadableEntries, unindexableEntries);
			}
			if (entryURI == null) {
				continue;
			}
			Entry entry;
			try {
				entry = cm.getEntry(entryURI);
			} catch (Exception | StackOverflowError e) {
				if (isCausedByCorruptData(e)) {
					corruptEntries++;
					logEntryFailure("Unable to load corrupt entry", entryURI, e, tracedFailureCauses);
					removeCorruptEntryDocument(entryURI);
				} else {
					unloadableEntries++;
					logEntryFailure("Unable to load entry", entryURI, e, tracedFailureCauses);
				}
				continue;
			}
			if (entry == null) {
				unresolvedEntries++;
				log.warn("Unable to load entry with URI {}", entryURI);
				continue;
			}
			loadedEntries++;
			synchronized (postQueue) {
				try {
					// Checked while holding the monitor that the removal of a deleted entry's document takes, so
					// that a concurrent deletion is either seen here or removes the document queued here
					if (entry.isDeleted() || entry.getContext().isDeleted()) {
						log.debug("Not adding deleted entry to post queue: {}", entryURI);
						continue;
					}
					log.info("Adding entry to Solr post queue: {}", entryURI);
					queueDocument(entryURI, constructSolrInputDocument(entry, extractFulltext));
					lastQueuedEntryURI = entryURI;
				} catch (Exception | StackOverflowError e) {
					unindexableEntries++;
					logEntryFailure("Not indexing entry", entryURI, e, tracedFailureCauses);
				}
			}
		}
		ContextPostResult posted = new ContextPostResult(ContextPostOutcome.COMPLETED, lastQueuedEntryURI,
				loadedEntries, corruptEntries, unresolvedEntries, unloadableEntries, unindexableEntries);
		if (posted.notIndexedEntries() > 0) {
			log.error("{} entries of context {} could not be indexed: {} are corrupt and {} could not be found, their"
					+ " documents are removed from the index unless the purge is prevented; {} could not be loaded"
					+ " and {} could not be indexed, they keep the documents they had in the index, if any",
					posted.notIndexedEntries(),
					contextURI, corruptEntries, unresolvedEntries, unloadableEntries, unindexableEntries);
		}
		return posted;
	}

	/**
	 * Removes the document of an entry that failed to load because of its data, unless a document of the entry is
	 * queued: then the entry loaded after it failed to load here, e.g. because its data was repaired in between,
	 * and the queued document must not be dropped. A queued document from before the data became corrupt is kept
	 * as well, which is harmless because a search skips hits whose entries are corrupt.
	 */
	private void removeCorruptEntryDocument(URI entryURI) {
		synchronized (postQueue) {
			if (postQueue.getIfPresent(entryURI) != null) {
				log.info("Not removing the document of corrupt entry {} because a document of it is queued", entryURI);
				return;
			}
			removeEntryDocument(entryURI);
		}
	}

	/**
	 * Tells whether loading an entry failed because of its data, so that it fails the same way until the data is
	 * repaired: the data is recognizably corrupt, or references in it form a cycle and cause unbounded recursion.
	 * Other failures, e.g. of the store, may be transient.
	 */
	private static boolean isCausedByCorruptData(Throwable e) {
		return ExceptionUtils.indexOfType(e, CorruptEntryException.class) >= 0
				|| ExceptionUtils.indexOfType(e, StackOverflowError.class) >= 0;
	}

	/**
	 * Logs an entry failure with its stack trace if it is the first failure of this kind (see
	 * {@link #failureKind(String, Throwable)}) in the current context reindex and the trace budget is not exhausted,
	 * otherwise as one line.
	 */
	private void logEntryFailure(String message, URI entryURI, Throwable e, Set<String> tracedFailureCauses) {
		if (tracedFailureCauses.size() < MAX_ENTRY_FAILURE_TRACES
				&& tracedFailureCauses.add(failureKind(message, e))) {
			log.error("{} {}", message, entryURI, e);
		} else {
			log.error("{} {}: {}", message, entryURI, ExceptionUtils.getRootCauseMessage(e));
		}
	}

	/**
	 * @return the kind of an entry failure: the log message and the classes of the exception and its causes. Not
	 * the exception messages, which contain the URI of the failing entry, so that the same failure of many entries
	 * is one kind.
	 */
	static String failureKind(String message, Throwable e) {
		return message + ": " + ExceptionUtils.getThrowableList(e).stream()
				.map(t -> t.getClass().getName())
				.collect(Collectors.joining(" <- "));
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

		// project type
		// FIXME this can be optimized by not checking the context for every single indexed entry, perhaps we can
		// remember the context status for a limited amount of time instead of fetching and querying the context graph
		// every time
		Model graphWithProjectType;
		URI resourceUriForProjectType;
		if (GraphType.Context.equals(entry.getGraphType())) {
			graphWithProjectType = entryGraph;
			resourceUriForProjectType = resourceURI;
		} else {
			Entry contextEntry = entry.getContext().getEntry();
			graphWithProjectType = contextEntry.getGraph();
			resourceUriForProjectType = contextEntry.getResourceURI();
		}

		for (String projectTypeURI : EntryUtil.getResourceValues(
				graphWithProjectType,
				resourceUriForProjectType,
				Collections.singleton(valueFactory.createIRI("http://entryscape.com/terms/projectType")))) {
			doc.setField("projectType", projectTypeURI);
			break; // we only need the first match
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
		Map<String, Set<String>> titles = EntryUtil.getTitles(entry);
		if (titles != null && !titles.isEmpty()) {
			storeLiteralsWithLanguages(doc, titles, "title");
		}

		String firstName = EntryUtil.getFirstName(entry);
		String lastName = EntryUtil.getLastName(entry);
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
		Map<String, Set<String>> descriptions = EntryUtil.getDescriptions(entry);
		if (descriptions != null && !descriptions.isEmpty()) {
			storeLiteralsWithLanguages(doc, descriptions, "description");
		}

		// tag.literal[.*]
		Map<String, Set<String>> tagLiterals = EntryUtil.getTagLiterals(entry);
		if (tagLiterals != null) {
			storeLiteralsWithLanguages(doc, tagLiterals, "tag.literal");
		}

		// tag.uri
		for (String s : EntryUtil.getTagResources(entry)) {
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
		String email = EntryUtil.getEmail(entry);
		if (email != null) {
			doc.addField("email", email);
		}

		// publicly viewable metadata?
		boolean guestReadable = false;
		PrincipalManager pm = entry.getRepositoryManager().getPrincipalManager();
		URI currentUser = pm.getAuthenticatedUserURI();
		try {
			pm.setAuthenticatedUserURI(pm.getGuestUser().getURI());
			try {
				pm.checkAuthenticatedUserAuthorized(entry, AccessProperty.ReadMetadata);
				guestReadable = true;
			} catch (AuthorizationException ignored) {
			} catch (IllegalArgumentException iae) {
				log.warn(iae.getMessage());
			}
		} finally {
			pm.setAuthenticatedUserURI(currentUser);
		}
		doc.setField("public", guestReadable);

		addGenericMetadataFields(doc, mdGraph, false);

		if (related) {
			addRelatedFields(doc, entry);
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

	/**
	 * Indexes the IRI and literal objects of the graph (blank-node objects are skipped) into the dynamic
	 * {@code metadata.predicate.*} fields, {@code related.}-prefixed for the related graph, plus the flat
	 * {@code metadata.object.*} fields for the primary graph. Package-private so the literal field layout can be
	 * tested without a Solr server.
	 */
	void addGenericMetadataFields(SolrInputDocument doc, Model metadata, boolean related) {
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
			String predMD5Trunc8 = Hashing.hash(predString, HashType.MD5).substring(0, 8);

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
				if (!LangFacetValue.exceedsLabelCap(l.getLabel())) {
					addFieldValueOnce(doc, prefix + LangFacetValue.FIELD_PREFIX + predMD5Trunc8, LangFacetValue.encode(l));
				}

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

	private void addRelatedFields(SolrInputDocument doc, Entry entry) {
		if (doc == null || entry == null) {
			throw new IllegalArgumentException("Neither SolrInputDocument nor Entry must be null");
		}

		Set<Context> contexts = new HashSet<>();
		if (relatedContainsGlobal) {
			// TODO there is room for optimization, the contexts are loaded now once per indexed entry.
			//  possible solution: provide getter method and load only if set is null; set to null when there are
			//  relevant changes, e.g. context removed or added (ContextManager needs to tell SolrSearchIndex that
			//  contexts have changed)
			ContextManager cm = entry.getRepositoryManager().getContextManager();
			for (URI contextURI : cm.getEntries()) {
				Context c = cm.getContext(contextURI);
				if (c instanceof RegularContext) {
					contexts.add(c);
				}
			}
		}

		Set<Entry> relatedEntries = new HashSet<>();
		for (IRI relProp : relatedProperties.keySet()) {
			List<String> relatedURIs = EntryUtil.getResourceValues(entry, Collections.singleton(relProp));
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
		PrincipalManager pm = entry.getRepositoryManager().getPrincipalManager();
		URI currentUser = pm.getAuthenticatedUserURI();
		try {
			pm.setAuthenticatedUserURI(pm.getAdminUser().getURI());
			URI entryURI = entry.getEntryURI();
			synchronized (postQueue) {
				if (postQueue.getIfPresent(entryURI) != null) {
					log.debug("Entry {} already exists in post queue, attempting replacement", entryURI);
				}
				if (!entry.isDeleted() && !entry.getContext().isDeleted()) {
					log.info("Adding document to Solr post queue: {}", entryURI);
					try {
						queueDocument(entryURI, constructSolrInputDocument(entry, extractFulltext));
					} catch (Exception e) {
						log.error("Not indexing {}", entryURI, e);
					}
				} else {
					log.debug("Not adding deleted entry to post queue: {}", entryURI);
				}
			}
		} finally {
			pm.setAuthenticatedUserURI(currentUser);
		}
	}

	public void removeEntry(Entry entry) {
		removeEntryDocument(entry.getEntryURI());

		// if entry is a context, also remove all entries inside
		if (GraphType.Context.equals(entry.getGraphType())) {
			if (!clearSolrIndex(solrServer, null, entry)) {
				log.warn("Context-removal purge for context {} failed; expired Solr documents may remain", entry.getEntryURI());
			}
		}
	}

	/**
	 * Queues the document of an entry for submission and drops a pending deletion of the entry, so that the
	 * deletion cannot remove the newer document: the submitter does not necessarily send all queued deletions
	 * before the queued documents. The caller must hold the submission queue's monitor, see
	 * {@link #removeEntryDocument(URI)}.
	 */
	private void queueDocument(URI entryURI, SolrInputDocument document) {
		synchronized (deleteQueue) {
			deleteQueue.remove(entryURI);
		}
		postQueue.put(entryURI, document);
	}

	/**
	 * Removes the document of an entry from the index and drops a document of the entry that is still waiting in
	 * the submission queue, so that it is not added again after the deletion. Both happen while holding the
	 * submission queue's monitor, as does {@link #queueDocument(URI, SolrInputDocument)}, so that of a deletion
	 * and a concurrently queued document of the same entry the later one wins.
	 */
	private void removeEntryDocument(URI entryURI) {
		synchronized (postQueue) {
			postQueue.invalidate(entryURI);
			synchronized (deleteQueue) {
				log.info("Adding entry to Solr delete queue: {}", entryURI);
				deleteQueue.add(entryURI);
			}
		}
	}

	/**
	 * Exact counts for the given labels of one facet field, as a second bounded request: {@code rows=0}, no ACL
	 * filtering and one {@code facet.query} per label. The field query parser takes the label literally, so a label
	 * containing commas, quotes or Solr syntax needs no escaping, and the request goes out as POST because labels
	 * can be long. Labels the field does not hold are absent from the result rather than present with zero.
	 *
	 * @param baseQuery the query whose result set the counts apply to; only its query and filters are reused
	 * @param field the client-facing facet field
	 * @param labels the labels to count; the caller is responsible for bounding how many
	 */
	public Map<String, Long> facetCountsForLabels(SolrQuery baseQuery, String field, Collection<String> labels) {
		Map<String, Long> counts = new LinkedHashMap<>();
		if (labels.isEmpty()) {
			return counts;
		}
		SolrQuery countQuery = new SolrQuery(baseQuery.getQuery());
		String[] filters = baseQuery.getFilterQueries();
		if (filters != null) {
			countQuery.setFilterQueries(filters);
		}
		countQuery.setRows(0);
		countQuery.setFacet(true);
		countQuery.setFacetMinCount(1);
		Map<String, String> labelByQuery = new LinkedHashMap<>();
		for (String label : labels) {
			String facetQuery = "{!field f=" + field + "}" + label;
			labelByQuery.put(facetQuery, label);
			countQuery.addFacetQuery(facetQuery);
		}
		try {
			// POST: one facet.query per label, and a label can be long
			QueryResponse response = solrServer.query(countQuery, SolrRequest.METHOD.POST);
			Map<String, Integer> facetQueryCounts = response.getFacetQuery();
			if (facetQueryCounts != null) {
				facetQueryCounts.forEach((facetQuery, count) -> {
					String label = labelByQuery.get(facetQuery);
					if (label != null && count != null && count > 0) {
						counts.put(label, count.longValue());
					}
				});
			}
		} catch (SolrServerException | IOException | SolrException e) {
			log.error("Failed to fetch exact facet counts for {} labels on field {}: {}", labels.size(), field, e.getMessage());
		}
		return counts;
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
			// Facets are identical on every result-fill pass of sendQuery; taking them once keeps each field single.
			if (r.getFacetFields() != null && facetFields.isEmpty()) {
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
			if (resultFillIteration++ > 0) {
				// We have a small limit and we don't get enough results with permissive ACL per iteration,
				// so we need to increase the result size windows, but not the result limit itself
				// (i.e., we only change the rows towards Solr, but not the query limit of EntryStore.
				// We only need to do this once when resultFillIteration equals 1.
				if (resultFillIteration == 1 && limit <= 10) {
					query.setRows(100);
				}
				if (resultFillIteration > 10) {
					log.warn("Breaking after 10 result fill interations to prevent too many loops");
					break;
				}
				offset += Math.min(limit, 50);
				log.warn("Increasing offset to " + offset + " in an attempt to fill the result limit");
			}
			Set<URI> entryURIs = new LinkedHashSet<>();
			hits = sendQueryForEntryURIs(query, entryURIs, facetFields, solrServer, offset);
			// Facets depend on the query, not on the page, so every further fill pass would recompute the same
			// buckets and throw them away. Switching faceting off makes the extra passes cheaper for Solr.
			query.setFacet(false);
			Date before = new Date();
			for (URI uri : entryURIs) {
				URI referencedEntryURI = null;
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
								&& entry.getCachedExternalMetadata() instanceof LocalMetadataWrapper wrapper) {
							referencedEntryURI = wrapper.getReferencedEntryURI();
							Entry refEntry;
							try {
								refEntry = entry.getRepositoryManager().getContextManager()
										.getEntry(entry.getExternalMetadataURI());
							} catch (IllegalArgumentException e) {
								// A URI in the repository that does not denote an entry, stored before such URIs
								// were rejected (ENTRYSTORE-1182); handled like a reference to a missing entry
								refEntry = null;
							}
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
				} catch (RuntimeException | StackOverflowError e) {
					// Only corrupt data of the hit, its referenced entry or their context entries skips the hit; other
					// failures, e.g. of the store or a corrupt principal in an access check, must fail the search
					if (ExceptionUtils.indexOfType(e, StackOverflowError.class) < 0
							&& !CorruptData.isCorruptDataOf(e, uri, rm.getRepositoryURL())
							&& !CorruptData.isCorruptDataOf(e, referencedEntryURI, rm.getRepositoryURL())) {
						throw e;
					}
					log.warn("Skipping search hit {} because its entry or its referenced entry is corrupt: {}", uri,
							CorruptData.describe(e));
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
