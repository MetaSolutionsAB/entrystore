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
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.vocabulary.RDF;
import org.eclipse.rdf4j.query.BindingSet;
import org.eclipse.rdf4j.query.TupleQueryResult;
import org.eclipse.rdf4j.repository.Repository;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.RepositoryResult;
import org.entrystore.AuthorizationException;
import org.entrystore.Context;
import org.entrystore.ContextManager;
import org.entrystore.Entry;
import org.entrystore.EntryType;
import org.entrystore.GraphType;
import org.entrystore.PrincipalManager;
import org.entrystore.repository.security.TreeRemovalPendingException;
import org.entrystore.repository.util.URISplit;
import org.entrystore.repository.util.URIType;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.eclipse.rdf4j.model.util.Values.iri;

/**
 * Removes the tree of a list, as {@link org.entrystore.List#removeTree()} asks for: in the background
 * ({@link #schedule}) or on the calling thread ({@link #removeNow}), chunk by chunk, so that other writers wait for
 * one chunk at most.
 *
 * <p>Both first write {@code <root list entry> es:treeRemovalPending <user>} into the context's index graph. The
 * marker survives a restart, after which {@link #resumePending()} continues the removal as that user. Each pass walks
 * the tree from the root with reads a triple index answers, members before their list, and removes up to
 * {@link #chunkSize} entries per chunk through {@link EntryPurge}: read without the monitor, removed by exact quad
 * under it. The tree shrinks from its leaves and the root goes last, with the marker. A list of the tree is always
 * removed; any other member only when no list outside the tree holds it, else it only leaves the tree.
 *
 * <p>Each chunk checks, under the monitor, that the marker is still there and the context not removed: an import into
 * the context removes the marker and a context removal flags the context deleted, either of which ends the job. It
 * also checks that its entries are still where the walk found them, else another pass walks the tree again: a member
 * added to a list of the tree meanwhile is removed too, and an entry that is no list stays once taken out of the tree
 * or added to a list outside it; a list still in the tree is removed even when a list outside it also holds it. A list
 * of the tree that a move to another context has claimed, or whose own removal is pending, is left to that move or
 * removal, and is given a marker when the job unlinks it, so that a move that fails leaves it to the background.
 *
 * <p>A run that finds the tree still changing, having removed some of it, is retried every 10 minutes until the tree
 * is gone. A run that fails otherwise, or removes nothing, is retried twice, after 1 and 10 minutes; then its marker
 * is dropped and the rest of the tree stays. The list stays pending while it waits for a retry, which an import into
 * the context cancels. A removal on the calling thread is not retried: its failure drops the marker and is thrown. A
 * run stopped by a shutdown keeps the marker, so the next start resumes it. Each run checks again that the user its
 * marker names may administer the context.
 */
@Slf4j
final class ListTreeRemover {

	/** Passes in a row that remove nothing before a run leaves a tree that keeps changing to a later one. */
	private static final int MAX_PASSES = 3;

	/** Passes one run makes at most, so that a tree a writer keeps adding to does not hold the thread for good. */
	private static final int MAX_PASSES_PER_RUN = 10;

	/** Every tree removal marker, with the graph that holds it. */
	private static final String MARKERS = "SELECT ?root ?graph WHERE { GRAPH ?graph { ?root <"
			+ RepositoryProperties.treeRemovalPending + "> ?user } }";

	private final RepositoryManagerImpl repositoryManager;
	private final Repository repository;
	private final ExactRemoval exactRemoval;
	private final Set<IRI> pending = ConcurrentHashMap.newKeySet();
	/**
	 * The pending trees whose removal waits for a retry, each with the ticket of that retry, which runs only while its
	 * ticket is still here.
	 */
	private final Map<IRI, Object> waitingRetry = new ConcurrentHashMap<>();
	private final ScheduledThreadPoolExecutor executor =
			new ScheduledThreadPoolExecutor(1, Thread.ofPlatform().name("list-tree-removal").daemon().factory());

	/** The delay before each retry of a failed run. Package-private and non-final so tests can shorten them. */
	volatile List<Duration> retryDelays = List.of(Duration.ofMinutes(1), Duration.ofMinutes(10));

	/** The delay before each retry of a run that found the tree still changing. Non-final for tests. */
	volatile Duration changingRetryDelay = Duration.ofMinutes(10);

	/** Set by {@link #shutdown()}. Package-private so a test can tell when the job will stop. */
	volatile boolean stopping;

	/** Entries removed per transaction. Package-private and non-final so tests can force several chunks. */
	volatile int chunkSize = 500;

	/** Run before each chunk is read; for tests. */
	volatile Runnable beforeChunk = () -> {};

	/** Run after each chunk has been committed and the monitor released; for tests. */
	volatile Runnable afterChunk = () -> {};

	ListTreeRemover(RepositoryManagerImpl repositoryManager) {
		this.repositoryManager = repositoryManager;
		this.repository = repositoryManager.getRepository();
		this.exactRemoval = new ExactRemoval(repositoryManager);
		// a retry dropped at shutdown keeps its marker, so the next start resumes it
		executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
	}

	/**
	 * Marks the tree of {@code list} for removal by the current user and removes it in the background. Does nothing
	 * more if its removal is pending already or the tree is claimed, see {@link #claim}. The caller has checked that
	 * the user may administer the context.
	 */
	void schedule(ListImpl list) {
		IRI root = mark(list);
		if (pending.add(root)) {
			submit(root);
		}
	}

	/**
	 * Claims the tree of {@code list} for {@link #removeNow} before the caller copies it, so that no other removal of
	 * it runs meanwhile: a recursive delete of it then only writes its marker, and the removal of a tree holding it
	 * leaves it out.
	 *
	 * @return the entry URI of the list
	 * @throws TreeRemovalPendingException if the tree {@link #isBeingRemoved} already
	 */
	IRI claim(ListImpl list) {
		IRI root = list.entry.getSesameEntryURI();
		// under the monitor, so that a claim cannot fall between the check of a chunk and its commit
		synchronized (repository) {
			if (hasMarker(root) || !pending.add(root)) {
				throw new TreeRemovalPendingException("The tree of list " + list.entry.getEntryURI()
						+ " is being removed or moved");
			}
		}
		return root;
	}

	/**
	 * Gives up a claim without {@link #removeNow} running. A recursive delete asked for meanwhile, which found the tree
	 * claimed and only wrote its marker, then runs in the background, as does the removal of a tree holding it, which
	 * wrote its marker when it unlinked it. Also how a run ends, so that a recursive delete asked for as it ended is
	 * not lost. Does not throw: if the marker cannot be read, such a delete resumes on the next start.
	 */
	void release(IRI root) {
		boolean marked;
		synchronized (repository) {
			pending.remove(root);
			try {
				marked = liveContext(contextIdOf(root)) != null && hasMarker(root);
			} catch (RuntimeException e) {
				log.error("Could not check whether a removal of the tree of list {} was asked for meanwhile; it "
						+ "resumes on the next start: {}", root, e.getMessage(), e);
				return;
			}
		}
		if (marked && pending.add(root)) {
			submit(root);
		}
	}

	/**
	 * Marks the tree of {@code list}, which the caller claimed, for removal by the current user, removes it on the
	 * calling thread and gives up the claim. A failure drops the marker, leaving the rest of the tree in place, and is
	 * thrown, so that the caller reports it rather than a removal that may never finish.
	 *
	 * @throws org.entrystore.repository.RepositoryException if the marker could not be written; if the removal failed,
	 *                                                       the rest of the tree then left in place, or resumed on the
	 *                                                       next start if the marker could not be dropped; if it
	 *                                                       stopped at shutdown; or if it was cancelled
	 */
	void removeNow(ListImpl list) {
		IRI root = list.entry.getSesameEntryURI();
		try {
			mark(list);
		} catch (RuntimeException e) {
			release(root);
			throw new org.entrystore.repository.RepositoryException("Could not mark the tree of list " + root
					+ " for removal", e);
		}
		boolean removed;
		Retry retry = null;
		boolean keepMarker = false;
		try {
			removed = new Removal(root).run();
		} catch (StoppedException e) {
			keepMarker = true;
			throw new org.entrystore.repository.RepositoryException("Removing the tree of list " + root
					+ " stopped at shutdown, it resumes on the next start", e);
		} catch (RuntimeException e) {
			// not retried: a later removal would delete the source after the caller reported the move as failed
			try {
				dropMarker(root);
				retry = Retry.GIVEN_UP;
			} catch (RuntimeException dropFailure) {
				e.addSuppressed(dropFailure);
				retry = Retry.DEFERRED;
			}
			throw new org.entrystore.repository.RepositoryException("Removing the tree of list " + root
					+ " failed, " + describe(retry, 0, false), e);
		} catch (Error e) {
			keepMarker = true;
			throw e;
		} finally {
			end(root, retry, keepMarker);
		}
		if (!removed && liveContext(contextIdOf(root)) != null) {
			throw new org.entrystore.repository.RepositoryException("Removing the tree of list " + root
					+ " was cancelled: its marker is gone or its requester may no longer administer the context");
		}
	}

	/**
	 * Whether the tree of the list with this entry URI is being removed, waits to be, waits for a retry or is claimed,
	 * since this start.
	 */
	boolean isPending(IRI root) {
		return pending.contains(root);
	}

	/**
	 * Whether a list holding the entry, directly or through other lists of its context, {@link #isPending}, which
	 * removes or moves the entry with its tree.
	 */
	boolean isHeldByAPendingTree(EntryImpl entry) {
		if (pending.isEmpty()) {
			return false;
		}
		Context c = entry.getContext();
		Deque<URI> listResources = new ArrayDeque<>(entry.getReferringListsInSameContext());
		Set<URI> seen = new HashSet<>();
		while (!listResources.isEmpty()) {
			URI listResource = listResources.pop();
			if (!seen.add(listResource)) {
				continue;
			}
			for (Entry holder : c.getByResourceURI(listResource)) {
				// a Link may share the list's resource URI; only the local list holds the entry
				if (!(holder instanceof EntryImpl holderEntry) || holderEntry.getGraphType() != GraphType.List
						|| holderEntry.getEntryType() != EntryType.Local) {
					continue;
				}
				if (pending.contains(holderEntry.getSesameEntryURI())) {
					return true;
				}
				listResources.addAll(holderEntry.getReferringListsInSameContext());
			}
		}
		return false;
	}

	/**
	 * Ends the removals of trees in the context that wait for a retry, as an import into it does, which has removed
	 * their markers. The caller holds the monitor.
	 */
	void cancelRetries(String contextId) {
		for (IRI root : waitingRetry.keySet()) {
			if (contextId.equals(contextIdOf(root)) && waitingRetry.remove(root) != null) {
				pending.remove(root);
			}
		}
	}

	/** Whether the removal of the tree waits for a retry; for tests. */
	boolean isWaitingForRetry(IRI root) {
		return waitingRetry.containsKey(root);
	}

	/**
	 * Whether the tree of the list with this entry URI {@link #isPending} or is marked for removal. Exact under the
	 * monitor, under which the marker is written.
	 */
	boolean isBeingRemoved(IRI root) {
		return pending.contains(root) || hasMarker(root);
	}

	/**
	 * Resumes the removals whose marker a previous run left in the index graph of the list's context. Skips a tree
	 * pending already and one in a context being removed, which that removal deletes, so it runs after
	 * {@link ContextRemover#resumePending}. Finds each marker's graph by query, since a SPARQL store outside quad mode
	 * returns statements without theirs.
	 */
	void resumePending() {
		Set<IRI> roots = new LinkedHashSet<>();
		int ignored = 0;
		try (RepositoryConnection rc = repository.getConnection();
			 TupleQueryResult markers = rc.prepareTupleQuery(MARKERS).evaluate()) {
			for (BindingSet marker : markers) {
				if (marker.getValue("root") instanceof IRI root && marker.getValue("graph") instanceof IRI graph
						&& indexGraphOf(root).filter(graph::equals).isPresent()) {
					roots.add(root);
				} else {
					ignored++;
				}
			}
		}
		if (ignored > 0) {
			log.warn("Ignored {} tree removal markers outside the index graph of the context of the list they name",
					ignored);
		}
		ContextManager contextManager = repositoryManager.getContextManager();
		for (IRI root : roots) {
			String contextId = contextIdOf(root);
			if (contextManager.isRemovalPending(contextId)) {
				continue;
			}
			if (pending.add(root)) {
				log.info("Resuming the removal of the tree of list {}", root);
				submit(root);
			}
		}
	}

	/**
	 * Stops the job between two chunks or during a walk, and drops the retries waiting; the markers stay, so the next
	 * start resumes them.
	 */
	void shutdown() {
		stopping = true;
		executor.shutdown();
		try {
			if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
				log.warn("List tree removal did not stop within 30 seconds");
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	/**
	 * Waits, at most 60 seconds, until no job runs or is queued, delayed retries included; for tests.
	 *
	 * @throws TimeoutException if that takes longer, as a retry delayed by minutes makes it
	 */
	void awaitIdle() throws InterruptedException, ExecutionException, TimeoutException {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
		do {
			executor.submit(() -> {}).get(deadline - System.nanoTime(), TimeUnit.NANOSECONDS);
		} while (!executor.getQueue().isEmpty() || executor.getActiveCount() > 0);
	}

	/** Writes the marker in a transaction of its own, naming the current user, or admin where none is set. */
	private IRI mark(ListImpl list) {
		EntryImpl listEntry = list.entry;
		IRI root = listEntry.getSesameEntryURI();
		ContextImpl context = (ContextImpl) listEntry.getContext();
		PrincipalManager pm = repositoryManager.getPrincipalManager();
		URI user = pm.getAuthenticatedUserURI() != null ? pm.getAuthenticatedUserURI() : pm.getAdminUser().getURI();
		synchronized (repository) {
			try (RepositoryConnection rc = repository.getConnection()) {
				ValueFactory vf = rc.getValueFactory();
				rc.begin();
				rc.remove(root, RepositoryProperties.treeRemovalPending, null, context.resourceURI);
				rc.add(root, RepositoryProperties.treeRemovalPending, vf.createIRI(user.toString()), context.resourceURI);
				rc.commit();
			}
		}
		return root;
	}

	private void submit(IRI root) {
		try {
			executor.execute(() -> runInBackground(root, 0));
		} catch (RejectedExecutionException e) {
			pending.remove(root);
			log.error("Could not start removing the tree of list {}, it resumes on the next start: {}", root,
					e.getMessage(), e);
		}
	}

	/**
	 * Runs the removal of a pending tree; {@code attempt} counts the runs before this one that failed or removed
	 * nothing, which use up the retries.
	 */
	private void runInBackground(IRI root, int attempt) {
		Retry retry = null;
		boolean keepMarker = false;
		try {
			new Removal(root).run();
		} catch (StoppedException e) {
			keepMarker = true;
			log.info("Stopped removing the tree of list {}, it resumes on the next start", root);
		} catch (TreeChangingException e) {
			retry = retryLater(root, attempt, e.progressed);
			log.warn("{}, {}", e.getMessage(), describe(retry, attempt, e.progressed));
		} catch (RuntimeException e) {
			retry = retryLater(root, attempt, false);
			log.error("Removing the tree of list {} failed, {}: {}", root, describe(retry, attempt, false),
					e.getMessage(), e);
		} catch (Error e) {
			keepMarker = true;
			// logged here: the executor keeps it in a future that nobody reads
			log.error("Removing the tree of list {} failed; it resumes on the next start: {}", root, e.getMessage(), e);
			throw e;
		} finally {
			end(root, retry, keepMarker);
		}
	}

	/**
	 * Ends a run: a tree waiting for a retry stays pending, and one whose marker is to stay for the next start is only
	 * dropped from {@link #pending}; any other is {@link #release released}, which takes up a marker written meanwhile.
	 */
	private void end(IRI root, Retry retry, boolean keepMarker) {
		if (retry == Retry.SCHEDULED) {
			return;
		}
		if (keepMarker || retry == Retry.DEFERRED) {
			pending.remove(root);
		} else {
			release(root);
		}
	}

	/**
	 * After failed run {@code attempt} of a removal: runs it again after a delay, keeping the tree pending, or, once
	 * the retries of a run that did not find the tree changing after removing some of it are used up, drops its marker,
	 * leaving the rest of the tree in place.
	 *
	 * @param changing whether the run found the tree still changing after removing some of it
	 */
	private Retry retryLater(IRI root, int attempt, boolean changing) {
		Optional<Duration> delay = retryDelay(attempt, changing);
		if (delay.isPresent()) {
			int next = changing ? attempt : attempt + 1;
			Object ticket = new Object();
			waitingRetry.put(root, ticket);
			try {
				executor.schedule(() -> {
					// a retry an import cancelled stays queued, and must not take the place of a later one
					if (waitingRetry.remove(root, ticket)) {
						runInBackground(root, next);
					}
				}, delay.get().toMillis(), TimeUnit.MILLISECONDS);
				return Retry.SCHEDULED;
			} catch (RejectedExecutionException e) {
				waitingRetry.remove(root, ticket);
				return Retry.DEFERRED;
			}
		}
		try {
			dropMarker(root);
			return Retry.GIVEN_UP;
		} catch (RuntimeException e) {
			log.error("Could not drop the marker of the removal of the tree of list {}: {}", root, e.getMessage(), e);
			return Retry.DEFERRED;
		}
	}

	/** The delay before the retry of failed run {@code attempt}; a tree that kept changing is retried until it ends. */
	private Optional<Duration> retryDelay(int attempt, boolean changing) {
		List<Duration> delays = retryDelays;
		if (changing) {
			return Optional.of(changingRetryDelay);
		}
		return attempt < delays.size() ? Optional.of(delays.get(attempt)) : Optional.empty();
	}

	/** How a message on failed run {@code attempt} goes on. */
	private String describe(Retry retry, int attempt, boolean changing) {
		return switch (retry) {
			case SCHEDULED -> "retrying in " + retryDelay(attempt, changing).map(Duration::toSeconds).orElse(0L) + " s";
			case GIVEN_UP -> "giving up; the rest of the tree is left in place";
			case DEFERRED -> "it resumes on the next start";
		};
	}

	/** Drops the marker of the tree from the index graph of its context, so that nothing resumes its removal. */
	private void dropMarker(IRI root) {
		Optional<IRI> indexGraph = indexGraphOf(root);
		if (indexGraph.isEmpty()) {
			return;
		}
		synchronized (repository) {
			try (RepositoryConnection rc = repository.getConnection()) {
				rc.remove(root, RepositoryProperties.treeRemovalPending, null, indexGraph.get());
			}
		}
	}

	/** Whether a marker of the tree naming a user is in the index graph of its context. */
	private boolean hasMarker(IRI root) {
		Optional<IRI> indexGraph = indexGraphOf(root);
		if (indexGraph.isEmpty()) {
			return false;
		}
		try (RepositoryConnection rc = repository.getConnection();
			 RepositoryResult<Statement> markers = rc.getStatements(root, RepositoryProperties.treeRemovalPending, null,
					 false, indexGraph.get())) {
			return markers.stream().anyMatch(marker -> marker.getObject() instanceof IRI);
		}
	}

	/** The context with this id, unless it is removed or being removed; null then. */
	private ContextImpl liveContext(String contextId) {
		Context context = contextId != null ? repositoryManager.getContextManager().getContext(contextId) : null;
		return context instanceof ContextImpl impl && !impl.isDeleted() ? impl : null;
	}

	/** The index graph of the context the entry belongs to, if it is an entry URI of this repository. */
	private Optional<IRI> indexGraphOf(IRI entry) {
		return split(entry).filter(split -> split.getUriType() == URIType.MetaMetadata)
				.map(split -> iri(split.getContextURI().toString()));
	}

	/** The id of the context an IRI of this repository belongs to, or null. */
	private String contextIdOf(IRI iri) {
		return split(iri).filter(split -> split.getUriType() != URIType.Unknown).map(URISplit::getContextId)
				.orElse(null);
	}

	/** The split of an IRI of this repository; empty for any other, also for one {@code java.net.URI} rejects. */
	private Optional<URISplit> split(IRI iri) {
		try {
			return URISplit.of(URI.create(iri.stringValue()), repositoryManager.getRepositoryURL());
		} catch (IllegalArgumentException e) {
			return Optional.empty();
		}
	}

	/** Thrown between two chunks, or during a walk, once {@link #shutdown()} has been called. */
	private static final class StoppedException extends Exception {
	}

	/**
	 * Thrown by a run that finds the tree still changing. A later run takes it up however often this happens to a run
	 * that removed some of the tree; a run that removed nothing counts as failed, so that a tree whose check never
	 * passes is given up.
	 */
	private static final class TreeChangingException extends RuntimeException {

		/** Whether the run removed some of the tree. */
		private final boolean progressed;

		private TreeChangingException(String message, boolean progressed) {
			super(message);
			this.progressed = progressed;
		}
	}

	/** What became of a run that failed. */
	private enum Retry {
		/** It runs again after a delay; the tree stays pending meanwhile. */
		SCHEDULED,
		/** The retries are used up: the marker is dropped and the rest of the tree stays. */
		GIVEN_UP,
		/**
		 * It runs again on the next start, as its marker is still there: the executor is shut down, or the marker could
		 * not be dropped.
		 */
		DEFERRED
	}

	/** The outcome of one chunk. */
	private enum Outcome {
		/** The chunk was removed. */
		REMOVED,
		/** The tree changed since the walk in a way removing the chunk would get wrong; nothing was removed. */
		CHANGED,
		/** The marker or the context is gone; nothing was removed. */
		CANCELLED
	}

	/**
	 * One walk of the tree, and where it found each entry, which each chunk checks again under the monitor.
	 *
	 * @param sequence      the entries to remove, members before their list and the root last
	 * @param walked        every entry the walk saw, also the members that stay, as they are held by a list outside
	 *                      the tree, belong to another context or cannot be read
	 * @param parents       the list each entry to remove was walked from, by entry URI; none for the root
	 * @param listResources the resource of each list of the tree, by entry URI
	 * @param lists         the resources of the lists of the tree
	 * @param skipped       the lists of the tree left, with their members, to the move that claimed them or to a
	 *                      removal of their own
	 */
	private record Walk(List<IRI> sequence, Set<IRI> walked, Map<IRI, IRI> parents, Map<IRI, IRI> listResources,
			Set<IRI> lists, Set<IRI> skipped) {
	}

	/** What the walk needs of an entry: its resource and whether it is a local list. */
	private record Node(IRI resource, boolean localList) {
	}

	/** One run over one tree, as the user named by its marker. */
	private final class Removal {

		private final IRI root;
		private final String contextId;
		/** The user the marker names, as whom the run removes the tree. */
		private IRI user;
		private long removed;
		private int pass;

		private Removal(IRI root) {
			this.root = root;
			this.contextId = contextIdOf(root);
		}

		/**
		 * @return whether the tree was removed; false if the marker or the context is gone, or the user the marker
		 * names may no longer administer the context, which drops the marker
		 * @throws TreeChangingException if the tree still changes after {@link #MAX_PASSES_PER_RUN} passes, or after
		 *                               {@link #MAX_PASSES} in a row that removed nothing
		 */
		private boolean run() throws StoppedException {
			PrincipalManager pm = repositoryManager.getPrincipalManager();
			URI previousUser = pm.getAuthenticatedUserURI();
			long start = System.currentTimeMillis();
			try {
				Optional<URI> requester = markerUser();
				ContextImpl context = context();
				if (requester.isEmpty() || context == null) {
					log.info("The removal of the tree of list {} was cancelled", root);
					return false;
				}
				user = iri(requester.get().toString());
				pm.setAuthenticatedUserURI(requester.get());
				try {
					pm.checkAuthenticatedUserAuthorized(context.getEntry(), PrincipalManager.AccessProperty.Administer);
				} catch (AuthorizationException e) {
					log.warn("Dropping the removal of the tree of list {}, as {} may no longer administer its context",
							root, requester.get());
					dropMarker(root);
					return false;
				}
				log.info("Removing the tree of list {} as {}", root, requester.get());
				for (int stalled = 0; stalled < MAX_PASSES; ) {
					if (pass == MAX_PASSES_PER_RUN) {
						throw new TreeChangingException("The tree of list " + root + " still changes after " + pass
								+ " passes", removed > 0);
					}
					pass++;
					long removedBefore = removed;
					Outcome outcome = removePass();
					if (outcome != Outcome.CHANGED) {
						log.info("{} the tree of list {} ({} entries) in {} ms",
								outcome == Outcome.REMOVED ? "Removed" : "Stopped removing", root, removed,
								System.currentTimeMillis() - start);
						return outcome == Outcome.REMOVED;
					}
					stalled = removed > removedBefore ? 0 : stalled + 1;
				}
				throw new TreeChangingException("The tree of list " + root + " still changes after " + MAX_PASSES
						+ " passes that removed nothing", removed > 0);
			} finally {
				pm.setAuthenticatedUserURI(previousUser);
			}
		}

		/** The user the marker names, or empty if the marker or its context is gone. */
		private Optional<URI> markerUser() {
			Optional<IRI> indexGraph = indexGraphOf(root);
			if (indexGraph.isEmpty() || context() == null) {
				return Optional.empty();
			}
			try (RepositoryConnection rc = repository.getConnection();
				 RepositoryResult<Statement> markers = rc.getStatements(root, RepositoryProperties.treeRemovalPending,
						 null, false, indexGraph.get())) {
				for (Statement marker : markers) {
					if (marker.getObject() instanceof IRI user) {
						return Optional.of(URI.create(user.stringValue()));
					}
				}
			}
			return Optional.empty();
		}

		private ContextImpl context() {
			return liveContext(contextId);
		}

		/** Walks the tree and removes it chunk by chunk, until done or a chunk finds it changed. */
		private Outcome removePass() throws StoppedException {
			ContextImpl context = context();
			if (context == null) {
				return Outcome.CANCELLED;
			}
			Walk walk;
			try (RepositoryConnection reader = repository.getConnection()) {
				walk = walk(reader);
			}
			if (walk.sequence().isEmpty()) {
				log.warn("The root list {} of a tree removal is gone; dropping its marker", root);
				dropMarker(root);
				return Outcome.REMOVED;
			}
			List<IRI> sequence = walk.sequence();
			for (int from = 0; from < sequence.size(); from += chunkSize) {
				if (stopping) {
					throw new StoppedException();
				}
				beforeChunk.run();
				List<IRI> chunk = sequence.subList(from, Math.min(from + chunkSize, sequence.size()));
				Outcome outcome = removeChunk(context, chunk, walk);
				if (outcome != Outcome.REMOVED) {
					return outcome;
				}
				removed += chunk.size();
				log.info("Removing the tree of list {}: {} entries removed (pass {})", root, removed, pass);
				afterChunk.run();
			}
			return Outcome.REMOVED;
		}

		/**
		 * Reads the tree with reads bound to a subject, or to a predicate and object, which a triple index answers:
		 * a depth-first walk from the root that lists each entry to remove after the members of its list. Members are
		 * walked last first, so pruning them from a large list mostly drops its last positions instead of renumbering
		 * all. A list that {@link #isPending} is not walked into.
		 */
		private Walk walk(RepositoryConnection reader) throws StoppedException {
			List<IRI> sequence = new ArrayList<>();
			Set<IRI> visited = new HashSet<>();
			Map<IRI, IRI> parents = new HashMap<>();
			Map<IRI, IRI> listResources = new HashMap<>();
			Set<IRI> kept = new HashSet<>();
			Set<IRI> candidates = new HashSet<>();
			Set<IRI> skipped = new HashSet<>();
			Node rootNode = node(reader, root);
			if (rootNode == null) {
				return new Walk(List.of(), visited, parents, listResources, Set.of(), Set.of());
			}
			visited.add(root);
			listResources.put(root, rootNode.resource());
			Deque<Frame> stack = new ArrayDeque<>();
			stack.push(new Frame(root, members(reader, rootNode.resource()).reversed().iterator()));
			while (!stack.isEmpty()) {
				if (stopping) {
					throw new StoppedException();
				}
				Frame frame = stack.peek();
				if (!frame.members().hasNext()) {
					stack.pop();
					sequence.add(frame.list());
					continue;
				}
				IRI member = frame.members().next();
				if (!visited.add(member)) {
					continue;
				}
				Node node = contextId.equals(contextIdOf(member)) ? node(reader, member) : null;
				if (node == null) {
					kept.add(member);
					continue;
				}
				if (node.localList() && pending.contains(member)) {
					skipped.add(member);
					continue;
				}
				parents.put(member, frame.list());
				if (node.localList()) {
					listResources.put(member, node.resource());
					stack.push(new Frame(member, members(reader, node.resource()).reversed().iterator()));
				} else {
					candidates.add(member);
					sequence.add(member);
				}
			}
			Set<IRI> lists = new HashSet<>(listResources.values());
			for (IRI candidate : candidates) {
				if (!lists.containsAll(listsHolding(reader, candidate))) {
					kept.add(candidate);
				}
			}
			sequence.removeIf(kept::contains);
			return new Walk(sequence, visited, parents, listResources, lists, skipped);
		}

		/**
		 * Removes one chunk in a transaction under the monitor, unless the marker or the context is gone, or the tree
		 * changed since the walk in a way removing the chunk would get wrong, which the next pass then walks again.
		 */
		private Outcome removeChunk(ContextImpl context, List<IRI> chunk, Walk walk) {
			EntryPurge purge = EntryPurge.plan(context, chunk);
			boolean last = chunk.contains(root);
			synchronized (repository) {
				if (context.isDeleted()) {
					return Outcome.CANCELLED;
				}
				try (RepositoryConnection rc = repository.getConnection()) {
					if (!rc.hasStatement(root, RepositoryProperties.treeRemovalPending, null, false,
							context.resourceURI)) {
						return Outcome.CANCELLED;
					}
					rc.begin();
					try {
						purge.refresh(rc);
						if (changedSinceWalk(rc, purge, chunk, walk)) {
							rc.rollback();
							return Outcome.CHANGED;
						}
						purge.apply(rc);
						markUnlinkedPendingLists(rc, purge, context);
						if (last) {
							rc.remove(root, RepositoryProperties.treeRemovalPending, null, context.resourceURI);
						}
						rc.commit();
					} catch (RuntimeException e) {
						purge.rollBack(rc);
						throw e;
					}
				}
				// committed, so a failure is only logged, and the events and file deletions below still run
				try {
					purge.publish(false);
				} catch (RuntimeException e) {
					context.unpublishIndexes();
					log.error("Removed {} entries of the tree of list {}, but releasing what memory holds of them "
							+ "failed: {}", chunk.size(), root, e.getMessage(), e);
				}
			}
			purge.afterPublish();
			return Outcome.REMOVED;
		}

		/**
		 * Whether, under the monitor, the tree changed since the walk in a way removing the chunk would get wrong: an
		 * entry of the chunk, or a list it was walked through, is no longer held by the list it was walked from, so it
		 * left the tree, or {@link #isPending}; an entry that is no list is held by a list outside the tree; a list of
		 * the chunk holds a member the walk did not see; or a list the walk left out no longer is pending. The lists
		 * holding an entry of the chunk are those the purge read last; for an entry whose relations graph names no
		 * list, as for one written without a relations graph, the list it was walked from is asked, as the walk read
		 * it.
		 */
		private boolean changedSinceWalk(RepositoryConnection rc, EntryPurge purge, List<IRI> chunk, Walk walk) {
			if (!walk.walked().containsAll(purge.unlinkedMembers()) || !pending.containsAll(walk.skipped())) {
				return true;
			}
			Set<IRI> inChunk = new HashSet<>(chunk);
			Set<IRI> inTree = new HashSet<>(Set.of(root));
			for (IRI entry : chunk) {
				if (!walk.listResources().containsKey(entry) && !walk.lists().containsAll(purge.holdingLists(entry))) {
					return true;
				}
				for (IRI current = entry; !inTree.contains(current); current = walk.parents().get(current)) {
					IRI parent = walk.parents().get(current);
					if (parent == null || pending.contains(current)) {
						return true;
					}
					IRI parentResource = walk.listResources().get(parent);
					Set<IRI> holding = inChunk.contains(current) ? purge.holdingLists(current)
							: listsHolding(rc, current);
					if (holding.isEmpty() ? !rc.hasStatement(parentResource, null, current, false, parentResource)
							: !holding.contains(parentResource)) {
						return true;
					}
					inTree.add(current);
				}
			}
			return false;
		}

		/**
		 * Marks for removal, as the user of this run, the lists the chunk unlinks that a move has claimed, so that
		 * {@link #release} hands them to the background if the move fails. A list with a removal of its own keeps
		 * its marker.
		 */
		private void markUnlinkedPendingLists(RepositoryConnection rc, EntryPurge purge, ContextImpl context) {
			for (IRI member : purge.unlinkedMembers()) {
				if (pending.contains(member) && !rc.hasStatement(member, RepositoryProperties.treeRemovalPending, null,
						false, context.resourceURI)) {
					rc.add(member, RepositoryProperties.treeRemovalPending, user, context.resourceURI);
				}
			}
		}

		/** The entry's resource and whether it is a local list, from its entry graph; null if it has none. */
		private Node node(RepositoryConnection reader, IRI entry) {
			IRI resource = null;
			boolean local = true;
			try (RepositoryResult<Statement> about = reader.getStatements(entry, null, null, false, entry)) {
				for (Statement statement : about) {
					if (statement.getPredicate().equals(RepositoryProperties.resource)
							&& statement.getObject() instanceof IRI resourceIRI) {
						resource = resourceIRI;
					} else if (statement.getPredicate().equals(RDF.TYPE)
							&& (statement.getObject().equals(RepositoryProperties.Link)
							|| statement.getObject().equals(RepositoryProperties.Reference)
							|| statement.getObject().equals(RepositoryProperties.LinkReference))) {
						local = false;
					}
				}
			}
			if (resource == null) {
				return null;
			}
			boolean list = local && reader.hasStatement(resource, RDF.TYPE, RepositoryProperties.List, false, entry);
			return new Node(resource, list);
		}

		/** The entry URIs a list holds, in order. */
		private List<IRI> members(RepositoryConnection reader, IRI listResource) {
			try (RepositoryResult<Statement> statements = reader.getStatements(listResource, null, null, false,
					listResource)) {
				return ListImpl.loadChildren(statements).stream().filter(member -> member != null)
						.map(member -> iri(member.toString())).toList();
			}
		}

		/** The resources of the lists that hold the entry, as its relations graph records them. */
		private Set<IRI> listsHolding(RepositoryConnection reader, IRI entry) {
			return exactRemoval.relationsGraphOf(entry)
					.map(relations -> ExactRemoval.listsHolding(reader, entry, relations)).orElse(Set.of());
		}
	}

	/** A list being walked: its entry and the members not visited yet. */
	private record Frame(IRI list, Iterator<IRI> members) {
	}
}
