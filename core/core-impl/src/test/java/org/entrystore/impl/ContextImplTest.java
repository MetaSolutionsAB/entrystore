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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.entrystore.Entry;
import org.entrystore.GraphType;
import org.entrystore.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

public class ContextImplTest extends AbstractCoreTest {

	/** A valid RDF4J IRI that {@code java.net.URI.create} rejects, so {@code loadIndex} cannot index it. */
	private static final String UNPARSEABLE_IRI = "http://example.com/not a uri";

	/** Wall-clock budget for one {@link #runConcurrently} run, after which its tasks are told to stop. */
	private static final long TASK_BUDGET_SECONDS = 30;

	private ContextImpl context;

	@BeforeEach
	public void setUp() {
		super.setUp();
		rm.setCheckForAuthorization(false);

		// A new Context
		Entry entry = cm.createResource(null, GraphType.Context, null, null);
		context = (ContextImpl) entry.getResource();
	}

	@Test
	public void createAndRemoveEntries() {
		//Some Entries
		int oldSize = context.getResources().size();
		Entry listEntry = context.createResource(null, GraphType.List, null, null);
		Entry linkEntry = context.createLink(null, URI.create("http://slashdot.org/"), null);
		Entry refEntry = context.createReference(null, URI.create("http://reddit.com/"), URI.create("http://example.com/md1"), null);
		Set<URI> resources = context.getResources();
		assertEquals(oldSize + 3, resources.size());
		assertTrue(resources.contains(listEntry.getResourceURI()));
		assertTrue(resources.contains(linkEntry.getResourceURI()));
		assertTrue(resources.contains(refEntry.getResourceURI()));

		//Lets remove them all!
		context.remove(listEntry.getEntryURI());
		context.remove(linkEntry.getEntryURI());
		context.remove(refEntry.getEntryURI());
		resources = context.getResources();
		assertEquals(oldSize, resources.size());
	}


	@Test
	public void accessToEntries() {
		Entry listEntry = context.createResource(null, GraphType.List, null, null);
		assertEquals(listEntry, context.getByResourceURI(listEntry.getResourceURI()).iterator().next());
		assertEquals(listEntry, context.getByEntryURI(listEntry.getEntryURI()));

		Entry linkEntry = context.createLink(null, URI.create("http://slashdot.org/"), null);
		assertEquals(linkEntry, context.getByResourceURI(linkEntry.getResourceURI()).iterator().next());
		assertEquals(linkEntry, context.getByEntryURI(linkEntry.getEntryURI()));

		Entry refEntry = context.createReference(null, URI.create("http://reddit.com/"), URI.create("http://example.com/md1"), null);
		assertEquals(refEntry, context.getByResourceURI(refEntry.getResourceURI()).iterator().next());
		assertEquals(refEntry, context.getByExternalMdURI(refEntry.getExternalMetadataURI()).iterator().next());
		assertEquals(refEntry, context.getByEntryURI(refEntry.getEntryURI()));
	}

	@Disabled("not ready yet")
	@Test
	public void quota() {
		context.getQuota();
		context.setQuota(5);
		assertEquals(5, context.getQuota());
	}

	@Test
	public void lists() {
		Entry listEntry = context.createResource(null, GraphType.List, null, null);
		List list = (List) listEntry.getResource();
		Entry sublistEntry1 = context.createResource(null, GraphType.List, null, listEntry.getResource().getURI());
		java.util.List<URI> children = list.getChildren();
		//Assert that something can be added to a list.
		assertEquals(1, children.size());
		assertTrue(children.contains(sublistEntry1.getEntryURI()));

		//Assert that several things can be added to a list.
		Entry sublistEntry2 = context.createResource(null, GraphType.List, null, listEntry.getResource().getURI());
		children = list.getChildren();
		assertEquals(2, children.size());
		assertTrue(children.contains(sublistEntry2.getEntryURI()));

		//Assert that the order of added things are the correct one.
		assertEquals(0, children.indexOf(sublistEntry1.getEntryURI()));
		assertEquals(1, children.indexOf(sublistEntry2.getEntryURI()));

		//Assert that the order can be changed.
		list.moveChildBefore(sublistEntry2.getEntryURI(), sublistEntry1.getEntryURI());
		children = list.getChildren();
		assertEquals(1, children.indexOf(sublistEntry1.getEntryURI()));
		assertEquals(0, children.indexOf(sublistEntry2.getEntryURI()));

		//Assert that we can set the lists children directly
		children = new ArrayList<>();
		children.add(0, sublistEntry1.getEntryURI());
		children.add(1, sublistEntry2.getEntryURI());
		list.setChildren(children);
		children = list.getChildren();
		assertEquals(0, children.indexOf(sublistEntry1.getEntryURI()));
		assertEquals(1, children.indexOf(sublistEntry2.getEntryURI()));

		//Assert that an entry can be removed from a list without being removed totally.
		list.removeChild(sublistEntry2.getEntryURI());
		children = list.getChildren();
		assertEquals(1, children.size());
		assertTrue(children.contains(sublistEntry1.getEntryURI()));
		assertNotNull(context.getByEntryURI(sublistEntry2.getEntryURI()));

		//Assert that when an entry is removed in itself, it is removed from all lists it appears in.
		URI sl1mmdURI = sublistEntry1.getEntryURI();
		context.remove(sublistEntry1.getEntryURI());
		assertNull(context.getByEntryURI(sl1mmdURI));
		children = list.getChildren();
		assertTrue(children.isEmpty());

		//Assert that when a list is removed, the children are not removed.
		list.addChild(sublistEntry2.getEntryURI());
		URI lmmdURI = listEntry.getEntryURI();
		context.remove(lmmdURI);
		assertNull(context.getByEntryURI(lmmdURI));
		assertNotNull(context.getByEntryURI(sublistEntry2.getEntryURI()));
	}

	@Test
	public void getByEntryURI_unknownEntry_returnsNull() {
		String baseUrl = rm.getRepositoryURL().toString();
		URI unknown = URI.create(baseUrl + context.getEntry().getId() + "/entry/does-not-exist");
		assertNull(context.getByEntryURI(unknown));
	}

	@Test
	public void getByEntryURI_concurrentLookupsOnSameURI_returnEntry() throws Exception {
		int threadCount = 32;
		Entry created = context.createLink(null, URI.create("http://example.com/concurrent-same"), null);
		URI entryURI = created.getEntryURI();

		// Force the cache-miss path: subsequent lookups must reload from the repo.
		evictFromSoftCache(created);

		AtomicInteger nonNullCount = new AtomicInteger();
		runConcurrently(threadCount, (_, _) -> {
			Entry e = context.getByEntryURI(entryURI);
			if (e != null && entryURI.equals(e.getEntryURI())) {
				nonNullCount.incrementAndGet();
			}
		});

		assertEquals(threadCount, nonNullCount.get());

		// Reverse-index converges to a single coherent Entry mapping for the resource URI.
		Set<Entry> byResource = context.softCache.getByURI(created.getResourceURI());
		assertNotNull(byResource);
		assertEquals(1, byResource.size());
		assertEquals(entryURI, byResource.iterator().next().getEntryURI());
	}

	@Test
	public void getByEntryURI_concurrentLookupsOnDifferentURIs_returnEntries() throws Exception {
		int entryCount = 32;
		java.util.List<URI> uris = new ArrayList<>();
		for (int i = 0; i < entryCount; i++) {
			Entry e = context.createLink(null, URI.create("http://example.com/concurrent-diff-" + i), null);
			uris.add(e.getEntryURI());
			// Force the cache-miss path for every URI to ensure repo lookups run in parallel.
			evictFromSoftCache(e);
		}

		ConcurrentHashMap<URI, URI> results = new ConcurrentHashMap<>();
		runConcurrently(entryCount, (i, _) -> {
			URI uri = uris.get(i);
			Entry e = context.getByEntryURI(uri);
			if (e != null) {
				results.put(uri, e.getEntryURI());
			}
		});

		assertEquals(entryCount, results.size());
		for (URI uri : uris) {
			assertEquals(uri, results.get(uri));
		}
	}

	@Test
	public void getByEntryURI_concurrentLookupsAndRemove_leaveNoZombieInCache() throws Exception {
		int readerCount = 8;
		Entry created = context.createLink(null, URI.create("http://example.com/concurrent-remove"), null);
		URI entryURI = created.getEntryURI();
		URI resourceURI = created.getResourceURI();
		URI localMetadataURI = created.getLocalMetadataURI();
		URI externalMetadataURI = created.getExternalMetadataURI();
		URI relationURI = created.getRelationURI();
		evictFromSoftCache(created);

		AtomicBoolean stop = new AtomicBoolean(false);
		CountDownLatch readersReady = new CountDownLatch(readerCount);
		java.util.List<Throwable> readerFailures = Collections.synchronizedList(new ArrayList<>());

		// Writer runs in the test (platform) thread to guarantee scheduling against the tight reader loops.
		// Readers run in a virtual-thread executor; try-with-resources waits for them after stop is set.
		try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
			for (int i = 0; i < readerCount; i++) {
				executor.submit(() -> {
					boolean first = true;
					try {
						while (!stop.get()) {
							context.getByEntryURI(entryURI);
							if (first) {
								readersReady.countDown();
								first = false;
							}
							// yield, not onSpinWait: the latter is a no-op hint on a virtual thread, and
							// getByEntryURI takes an uncontended monitor on its fast path, so a reader had no
							// point at which it unmounted. The readers then held every carrier thread and only
							// as many of them as there are carriers ever reached the countDown above, which
							// made the rendezvous below require a host with at least readerCount of them.
							Thread.yield();
						}
					} catch (Exception e) {
						readerFailures.add(e);
					}
				});
			}

			// Wait until every reader has completed at least one read so the writer's remove
			// is guaranteed to race against an active reader loop rather than a cold pool.
			// stop.set(true) runs even if remove() throws — otherwise readers would loop forever
			// and executor.close() would hang the CI build.
			try {
				assertTrue(readersReady.await(10, TimeUnit.SECONDS),
					() -> "Readers did not all reach the reload path; remaining: " + readersReady.getCount()
						+ "; reader failures so far: " + readerFailures);
				context.remove(entryURI);
			} finally {
				stop.set(true);
			}
		}

		assertTrue(readerFailures.isEmpty(), () -> "Reader thread(s) failed: " + readerFailures);

		// Writer's remove() must have actually deleted the entry from the context's resource index;
		// catches a future regression where remove() silently no-ops.
		assertFalse(context.getResources().contains(resourceURI),
			"Writer's remove did not delete the entry from the context");

		// After both operations complete, the cache must not retain a zombie Entry for the removed URI
		// under any of the URI kinds SoftCache indexes (entry, resource, local/external metadata, relation).
		// Other entries in the cache (referring lists, default container) are unrelated to this invariant.
		SoftCache cache = context.softCache;
		assertNull(cache.getByEntryURI(entryURI), "Zombie entry remained in cache after remove");
		assertNull(cache.getByURI(resourceURI), "Zombie in resource-URI reverse index after remove");
		if (localMetadataURI != null) {
			assertNull(cache.getByURI(localMetadataURI), "Zombie in local-metadata reverse index after remove");
		}
		if (externalMetadataURI != null) {
			assertNull(cache.getByURI(externalMetadataURI), "Zombie in external-metadata reverse index after remove");
		}
		if (relationURI != null) {
			assertNull(cache.getByURI(relationURI), "Zombie in relation reverse index after remove");
		}
	}

	/**
	 * ENTRYSTORE-1095. {@code getEntries} iterates the resource index while entry creation pushes into
	 * it, and the two share no monitor — the readers synchronize on nothing. With a plain {@code HashMap}
	 * index this throws {@code ConcurrentModificationException}.
	 *
	 * <p>It also asserts the outcome, which is what makes it a regression guard for the load protocol
	 * rather than only for the map types. The seeding loop leaves the indexes unpublished
	 * ({@code createNewMinimalItem} never loads them), so the writers here genuinely race a cold load —
	 * and a writer that reached {@code addToIndex} before publication has its {@code resHasEntry} triple
	 * still uncommitted, invisible to the loading scan. If such a write were skipped instead of recorded
	 * in the pending buffer, the entry would be absent from every later listing for the life of this
	 * object, and the failure would be a missing entry rather than an exception. Nothing else in the
	 * suite would catch it.
	 */
	@Test
	public void getEntries_concurrentWithEntryCreation_losesNoEntry() throws Exception {
		// Seed entries so there is something to iterate; an empty index cannot fail.
		for (int i = 0; i < 20; i++) {
			context.createLink(null, URI.create("http://example.com/seed/" + i), null);
		}

		int readers = 8;
		int writers = 4;
		Set<URI> createdURIs = ConcurrentHashMap.newKeySet();
		runConcurrently(readers + writers, (index, keepRunning) -> {
			if (index < readers) {
				for (int i = 0; i < 300 && keepRunning.getAsBoolean(); i++) {
					context.getEntries();
				}
			} else {
				for (int i = 0; i < 30 && keepRunning.getAsBoolean(); i++) {
					createdURIs.add(context
						.createLink(null, URI.create("http://example.com/w" + index + "/" + i), null)
						.getEntryURI());
				}
			}
		});

		assertEquals(writers * 30, createdURIs.size(), "every writer must have created its entries");
		assertTrue(context.getEntries().containsAll(createdURIs),
			"an entry created while the index was loading must still appear in the listing");
	}

	/**
	 * ENTRYSTORE-1095. The same race one level down: several entries sharing a resource URI make the
	 * index value a {@code Set}, which {@code getByResourceURI} iterates. A non-concurrent inner set
	 * throws while another thread adds to it.
	 */
	@Test
	public void getByResourceURI_concurrentWithEntryCreationOnTheSameResource_doesNotThrow() throws Exception {
		URI shared = URI.create("http://example.com/shared-resource");
		for (int i = 0; i < 5; i++) {
			context.createLink(null, shared, null);
		}

		int readers = 8;
		int writers = 4;
		runConcurrently(readers + writers, (index, keepRunning) -> {
			if (index < readers) {
				for (int i = 0; i < 300 && keepRunning.getAsBoolean(); i++) {
					context.getByResourceURI(shared);
				}
			} else {
				for (int i = 0; i < 15 && keepRunning.getAsBoolean(); i++) {
					context.createLink(null, shared, null);
				}
			}
		});
	}

	/**
	 * ENTRYSTORE-1095. {@code getResources} used to return {@code res2entry.keySet()} — a live view of
	 * the index. A caller could observe later changes through it and, worse, remove index entries by
	 * mutating it.
	 */
	@Test
	public void getResources_returnsASnapshotNotALiveViewOfTheIndex() {
		Entry first = context.createLink(null, URI.create("http://example.com/live/1"), null);
		Set<URI> snapshot = context.getResources();
		int sizeBefore = snapshot.size();
		assertTrue(snapshot.contains(first.getResourceURI()));

		context.createLink(null, URI.create("http://example.com/live/2"), null);

		assertEquals(sizeBefore, snapshot.size(),
			"getResources must return a snapshot; a live keySet would grow with the new entry");

		snapshot.remove(first.getResourceURI());

		assertTrue(context.getResources().contains(first.getResourceURI()),
			"mutating the returned set must not remove the entry from the index");
	}

	/**
	 * ENTRYSTORE-1095. {@code reIndex} nulls the index fields, so a reader must neither observe a
	 * truncated listing nor fail while it republishes.
	 *
	 * <p>Readers run until the writer is done rather than for a fixed count, and count the reads that
	 * straddle a {@code reIndex} call. Asserting that overlap actually happened is what stops the test
	 * passing vacuously: with a fixed iteration count, a run in which no read landed inside a
	 * {@code reIndex} window satisfied the size assertion while proving nothing.
	 *
	 * <p>The readers yield, and stop on the run's budget as well as on the writer, because they outnumber
	 * the carrier threads on a small CI host. {@code getEntries} takes an uncontended monitor on its fast
	 * path, so a reader has no point at which it unmounts on its own: the first one to be scheduled kept
	 * its carrier, the writer that sets {@code writerDone} never ran, and the loop never ended
	 * (ENTRYSTORE-1095).
	 */
	@Test
	public void getEntries_concurrentWithReIndex_neverObservesATruncatedListing() throws Exception {
		int seeded = 10;
		for (int i = 0; i < seeded; i++) {
			context.createLink(null, URI.create("http://example.com/reindex/" + i), null);
		}
		int expected = context.getEntries().size();

		AtomicInteger smallestSeen = new AtomicInteger(Integer.MAX_VALUE);
		AtomicInteger overlappingReads = new AtomicInteger();
		AtomicInteger reIndexCount = new AtomicInteger();
		AtomicBoolean writerDone = new AtomicBoolean();
		int readers = 8;

		runConcurrently(readers + 1, (index, keepRunning) -> {
			if (index < readers) {
				while (!writerDone.get() && keepRunning.getAsBoolean()) {
					// Counting completed re-indexes rather than a flag set around the whole reIndex() call.
					// The fields are nulled only at its very end, so a flag spanning the call is true for
					// almost entirely harmless time — both indexes still published, the race impossible —
					// and "overlapped" would be satisfied by reads that never came near the window.
					int before = reIndexCount.get();
					smallestSeen.accumulateAndGet(context.getEntries().size(), Math::min);
					if (reIndexCount.get() != before) {
						overlappingReads.incrementAndGet();
					}
					Thread.yield();
				}
			} else {
				try {
					for (int i = 0; i < 5 && keepRunning.getAsBoolean(); i++) {
						context.reIndex();
						reIndexCount.incrementAndGet();
					}
				} finally {
					writerDone.set(true);
				}
			}
		});

		assertTrue(writerDone.get(),
			"the reIndex writer did not finish, so the readers observed no republication");
		assertEquals(expected, smallestSeen.get(),
			"getEntries must never observe a partial or empty index while reIndex republishes it");
		// Only the overlap claim needs genuine parallelism, so only it is skipped on a single-CPU host: the
		// writer there runs reIndex start to finish without unmounting, since nothing in it blocks. The
		// truncation assertion above is the point of the test and must hold everywhere, including on a
		// cgroup-limited CI agent, where guarding the whole body left it green while asserting nothing.
		assumeTrue(Runtime.getRuntime().availableProcessors() >= 2,
			"needs more than one CPU to interleave a read with reIndex");
		assertTrue(overlappingReads.get() > 0,
			"no read overlapped a reIndex, so this run proved nothing about the race");
	}

	/**
	 * ENTRYSTORE-1095. Nothing else drives concurrent {@code push} calls on one key: every
	 * {@code createLink} write serialises on the repository monitor, so pushes never overlap there.
	 * {@code updateResource2EntryIndex} is the write path that does not hold that monitor, which makes
	 * it the one that can prove {@code push} is atomic per key. With the previous get-then-put, two
	 * writers racing on the same key lost one of the two mappings.
	 */
	@Test
	public void concurrentPushesOnOneKey_doNotLoseAMapping() throws Exception {
		int count = 16;
		java.util.List<Entry> created = new ArrayList<>();
		for (int i = 0; i < count; i++) {
			created.add(context.createLink(null, URI.create("http://example.com/premove/" + i), null));
		}
		URI shared = URI.create("http://example.com/push-atomicity");

		// Force the in-memory index to be loaded. updateResource2EntryIndex only mutates that index, so
		// without this the pushes no-op on a null map and the later read reloads the original URIs from
		// the repository instead — the test would pass vacuously against a broken push.
		assertTrue(context.getEntries().size() >= count);

		runConcurrently(count, (i, _) -> {
			Entry e = created.get(i);
			context.updateResource2EntryIndex(e.getResourceURI(), shared, e.getEntryURI());
		});

		assertEquals(count, context.getByResourceURI(shared).size(),
			"every concurrent push on the same key must survive");
		// pop's side of the same operation: the vacated keys must be gone, not left mapping to an
		// empty set, which getByResourceURI would return as a spurious hit-with-no-entries.
		for (Entry e : created) {
			assertTrue(context.getByResourceURI(e.getResourceURI()).isEmpty(),
				"the old key must be removed once its last entry moved away");
		}
	}

	/**
	 * ENTRYSTORE-1095. {@code getByExternalMdURI} reads the sibling index through the same machinery as
	 * {@code getByResourceURI}. {@code accessToEntries} already covers the single-value path; what was
	 * uncovered is the promote-to-set path for the external-metadata index and the iteration of that set
	 * while another thread adds to it.
	 */
	@Test
	public void getByExternalMdURI_concurrentWithEntryCreationOnTheSameMetadataURI_doesNotThrow() throws Exception {
		URI sharedMd = URI.create("http://example.com/shared-md");
		for (int i = 0; i < 5; i++) {
			context.createReference(null, URI.create("http://example.com/ref/" + i), sharedMd, null);
		}
		assertEquals(5, context.getByExternalMdURI(sharedMd).size());

		int readers = 8;
		int writers = 4;
		runConcurrently(readers + writers, (index, keepRunning) -> {
			if (index < readers) {
				for (int i = 0; i < 300 && keepRunning.getAsBoolean(); i++) {
					context.getByExternalMdURI(sharedMd);
				}
			} else {
				for (int i = 0; i < 15 && keepRunning.getAsBoolean(); i++) {
					context.createReference(null,
						URI.create("http://example.com/ref/w" + index + "/" + i), sharedMd, null);
				}
			}
		});
	}

	/**
	 * ENTRYSTORE-1095. {@code ConcurrentHashMap.get(null)} throws where the previous
	 * {@code HashMap.get(null)} returned null. A null resource URI reaches here on a normal
	 * authorization path — {@code PrincipalManagerImpl.getUser} passes an unset
	 * {@code authenticatedUserURI} ThreadLocal straight through — where an empty answer means "deny" and
	 * an NPE means a 500 out of an authorization check.
	 */
	@Test
	public void getByResourceURI_withNullURI_returnsEmptySet() {
		context.createLink(null, URI.create("http://example.com/null-arg"), null);

		assertTrue(context.getByResourceURI(null).isEmpty());
	}

	/** ENTRYSTORE-1095. See {@link #getByResourceURI_withNullURI_returnsEmptySet()}. */
	@Test
	public void getByExternalMdURI_withNullURI_returnsEmptySet() {
		context.createReference(null, URI.create("http://example.com/null-arg-ref"),
			URI.create("http://example.com/null-arg-md"), null);

		assertTrue(context.getByExternalMdURI(null).isEmpty());
	}

	/**
	 * ENTRYSTORE-1095. One statement that RDF4J accepts but {@code java.net.URI.create} rejects must not
	 * take the whole context down. Refusing to publish a partial index left both index fields null for
	 * the life of the object, so every later read rescanned the graph and threw again — and
	 * {@code ContextManagerImpl}'s constructor loads the index, so a bad triple in the {@code _contexts}
	 * graph prevented startup. ENTRYSTORE-839 established the log-and-continue tolerance deliberately.
	 *
	 * <p>The statement is planted before the first read, because the index is loaded lazily and
	 * {@code reIndex()} would rewrite the index graph and drop it again.
	 */
	@Test
	public void getEntries_withAnUnindexableStatement_stillListsTheGoodEntries() {
		Entry good = context.createLink(null, URI.create("http://example.com/indexable"), null);
		URI unreachable = URI.create("http://example.com/unreachable-entry");
		addUnindexableResHasEntryStatement(context, unreachable);

		Set<URI> entries = context.getEntries();

		assertTrue(entries.contains(good.getEntryURI()),
			"an unindexable statement must not hide the entries that did index");
		assertFalse(entries.contains(unreachable),
			"the unindexable statement itself must not appear in the listing");
	}

	/**
	 * ENTRYSTORE-1095. The deterministic half of the publish-at-end guarantee: an index write that arrives
	 * before anything has read the index must survive the load that follows.
	 *
	 * <p>{@code getEntries_concurrentWithEntryCreation_losesNoEntry} drives the real shape of the race but
	 * cannot force its interleaving — a reader publishes the index within microseconds of the run
	 * starting, after which writers push straight into it — so it asserts the outcome without reliably
	 * reproducing the failure. This test removes the timing: the write below is applied while the indexes
	 * are unpublished, and it touches only the in-memory index, so the scan that follows cannot recover it
	 * from the store. Skipping such a write, which is what the code did before the pending-write buffer,
	 * loses it for the life of the object.
	 */
	@Test
	public void indexWriteBeforeTheFirstRead_survivesTheLoadThatFollows() {
		Entry contextEntry = cm.createResource(null, GraphType.Context, null, null);
		ContextImpl fresh = (ContextImpl) contextEntry.getResource();
		Entry link = fresh.createLink(null, URI.create("http://example.com/before-any-read"), null);
		URI moved = URI.create("http://example.com/moved-before-any-read");

		fresh.updateResource2EntryIndex(link.getResourceURI(), moved, link.getEntryURI());

		// The precondition this test is named for. Both assertions below pass identically on the published
		// branch of applyIndexOp, so without this the test silently degrades into a warm-path test the
		// moment anything on the createLink path publishes the index — and would then stop failing if the
		// pending-write buffer were deleted outright.
		assertFalse(fresh.pendingIndexOps.isEmpty(),
			"the write must still be buffered, or this test is not exercising the unpublished branch");

		assertTrue(fresh.getByResourceURI(moved).stream()
				.anyMatch(e -> link.getEntryURI().equals(e.getEntryURI())),
			"an index write made before the first read must not be dropped by the load");
		assertTrue(fresh.getByResourceURI(link.getResourceURI()).isEmpty(),
			"and the removal half of that write must be replayed too");
	}

	/**
	 * ENTRYSTORE-1095. An index op recorded inside a transaction must not reach the index unless that
	 * transaction commits. {@code removeFromIndex} runs before {@code removeEntry.remove(rc)},
	 * {@code updateModifiedDateSynchronized} and {@code rc.commit()}, any of which can throw and roll
	 * back — restoring the {@code resHasEntry} triple in the store. Applying the recorded {@code pop}
	 * anyway left a live entry absent from every listing for the life of the object, with
	 * {@code isIndexComplete()} still answering true, so the Solr purge deleted its document and the
	 * persisted quota total omitted it.
	 */
	@Test
	public void indexOpFromARolledBackRemoval_isNotApplied() {
		Entry link = context.createLink(null, URI.create("http://example.com/survives-rollback"), null);
		// Publish the index first, so a bug here pops from a live map rather than merely buffering.
		assertTrue(context.getEntries().contains(link.getEntryURI()));

		java.util.List<ContextImpl.IndexOp> txOps = new ArrayList<>();
		try (RepositoryConnection rc = rm.getRepository().getConnection()) {
			rc.begin();
			context.removeFromIndex((EntryImpl) link, rc, txOps);
			rc.rollback();
		}

		assertFalse(txOps.isEmpty(), "the removal must have been recorded, just not applied");
		assertTrue(context.getEntries().contains(link.getEntryURI()),
			"a rolled-back removal must leave the entry listed");
		assertTrue(context.getByResourceURI(link.getResourceURI()).stream()
				.anyMatch(e -> link.getEntryURI().equals(e.getEntryURI())),
			"and still resolvable by its resource URI");
	}

	/**
	 * ENTRYSTORE-1095. The two tests around this one drive {@code removeFromIndex}/{@code addToIndex} on
	 * their own connection, so they only prove the helpers <em>collect</em> rather than apply. This one
	 * goes through the real {@code remove(URI)} transaction and forces it to fail after
	 * {@code removeFromIndex} has recorded its pop, so it pins the production path.
	 *
	 * <p>The failure is arranged by giving a list a member whose entry graph is gone: {@code ListImpl}
	 * dereferences {@code getByEntryURI} for each child while unlinking itself.
	 *
	 * <p>What it does <b>not</b> catch, stated rather than implied: moving {@code applyIndexOps} back
	 * above {@code rc.commit()}. The only failure this transaction can be made to throw lands at
	 * {@code removeEntry.remove(rc)}, which precedes both statements, so either ordering leaves this
	 * green. Covering that would need a failure between the apply and the commit — that is
	 * {@code updateModifiedDateSynchronized} or the commit itself, neither of which can be provoked
	 * against a memory store without a seam.
	 */
	@Test
	public void indexOpFromAFailedRemoveTransaction_isNotApplied() {
		Entry list = context.createResource(null, GraphType.List, null, null);
		Entry member = context.createLink(null, URI.create("http://example.com/doomed-member"), null);
		((List) list.getResource()).addChild(member.getEntryURI());
		assertTrue(context.getEntries().contains(list.getEntryURI()), "starting from a published index");

		try (RepositoryConnection rc = rm.getRepository().getConnection()) {
			rc.clear(((EntryImpl) member).getSesameEntryURI());
		}
		evictFromSoftCache(member);

		assertThrows(org.entrystore.repository.RepositoryException.class,
			() -> context.remove(list.getEntryURI()),
			"the removal must fail, or this test is not exercising a rollback");

		assertTrue(context.getEntries().contains(list.getEntryURI()),
			"an index op recorded by a removal that rolled back must not be applied: the resHasEntry "
				+ "triple is still in the store, so popping it hides a live entry from every listing");
		assertTrue(context.getByResourceURI(list.getResourceURI()).stream()
				.anyMatch(e -> list.getEntryURI().equals(e.getEntryURI())),
			"and it must still resolve by its resource URI");
	}

	/**
	 * ENTRYSTORE-1095. The mirror direction: a recorded {@code push} that outlives a rolled-back create
	 * names an entry that was never committed. {@code recalculateQuotaFillLevel} and
	 * {@code importContext} both dereference what a listing names, so a phantom mapping is an NPE rather
	 * than a merely wrong listing.
	 */
	@Test
	public void indexOpFromARolledBackCreate_isNotApplied() {
		assertTrue(context.getEntries().isEmpty(), "starting from a published, empty index");
		URI phantomResource = URI.create("http://example.com/never-committed");

		java.util.List<ContextImpl.IndexOp> txOps = new ArrayList<>();
		try (RepositoryConnection rc = rm.getRepository().getConnection()) {
			ValueFactory vf = rc.getValueFactory();
			rc.begin();
			context.addToIndex(vf.createIRI("http://example.com/phantom-entry"),
				vf.createIRI(phantomResource.toString()), null, rc, txOps);
			rc.rollback();
		}

		assertFalse(txOps.isEmpty(), "the create must have been recorded, just not applied");
		assertTrue(context.getByResourceURI(phantomResource).isEmpty(),
			"a rolled-back create must not leave a mapping to an entry that does not exist");
	}

	/**
	 * ENTRYSTORE-1095. The incompleteness has to outlive the load that discovered it. As a method-local
	 * count it left the truncation unobservable, so the Solr purge, the persisted quota fill level and
	 * {@code importContext}'s purge loop all acted destructively on a short listing.
	 */
	@Test
	public void isIndexComplete_isFalseAfterAnUnindexableStatementAndTrueAgainOnceRepaired() {
		URI unreachable = URI.create("http://example.com/unindexable");
		addUnindexableResHasEntryStatement(context, unreachable);

		assertFalse(context.isIndexComplete(),
			"a statement that could not be indexed must be visible after the load that skipped it");

		// reIndex() rewrites the index graph from the store, which drops the unparseable statement — the
		// repair path. The flag is assigned on every publish, so it clears rather than sticking.
		context.reIndex();

		assertTrue(context.isIndexComplete(), "a clean reload must clear the incomplete flag");
	}

	/**
	 * ENTRYSTORE-1095. Deleting a context must clear every child graph, including one the in-memory index
	 * never saw. Pins the two halves of that guarantee together: an unparseable statement no longer
	 * aborts the index load, and {@code remove(RepositoryConnection)} reads its child list from the
	 * transaction instead of from the index that skipped it. Deriving it from the index left the child's
	 * entry, metadata and resource graphs in the store while {@code rc.clear(this.resourceURI)} destroyed
	 * the graph that named them — permanently orphaned data.
	 *
	 * <p>The child's only {@code resHasEntry} triple gets a subject {@code java.net.URI.create} rejects,
	 * and the context is then re-resolved from a cold cache so its index is built from the store — which
	 * is the state a restart leaves behind, and the only state in which the index genuinely cannot see the
	 * child. Asserting on the instance that created it would instead observe the mapping its own
	 * {@code createLink} recorded in the pending-write buffer: correct behaviour, since that write must
	 * never be lost, but it makes that instance the wrong witness for this test.
	 */
	@Test
	public void removeContext_clearsAChildTheIndexCouldNotSee() throws Exception {
		Entry contextEntry = cm.createResource(null, GraphType.Context, null, null);
		ContextImpl doomed = (ContextImpl) contextEntry.getResource();
		EntryImpl child = (EntryImpl) doomed.createLink(null, URI.create("http://example.com/orphan-candidate"), null);
		IRI childEntryIRI = child.getSesameEntryURI();
		IRI childMetadataIRI = child.getSesameLocalMetadataURI();
		IRI childRelationIRI = child.relationURI;
		String doomedId = doomed.id;

		// Planted first: a freshly created link has an empty local-metadata graph, so "no statements"
		// below would hold whether or not the deletion cleared it.
		try (RepositoryConnection rc = rm.getRepository().getConnection()) {
			ValueFactory vf = rc.getValueFactory();
			rc.add(vf.createIRI(child.getResourceURI().toString()),
				vf.createIRI("http://purl.org/dc/terms/title"), vf.createLiteral("orphan candidate"),
				childMetadataIRI);
			rc.add(vf.createIRI(child.getResourceURI().toString()),
				vf.createIRI("http://purl.org/dc/terms/title"), vf.createLiteral("orphan candidate"),
				childRelationIRI);
		}

		hideFromIndex(doomed, child);

		((ContextManagerImpl) cm).softCache.remove(contextEntry);
		ContextImpl reloaded = (ContextImpl) cm.getContext(doomedId);
		assertFalse(reloaded.getEntries().contains(child.getEntryURI()),
			"the child must be invisible to the in-memory index for this test to mean anything");
		assertFalse(reloaded.isIndexComplete(),
			"and the context must know its index is short");

		cm.remove(contextEntry.getEntryURI());

		try (RepositoryConnection rc = rm.getRepository().getConnection()) {
			assertFalse(rc.hasStatement(null, null, null, false, childEntryIRI),
				"the child entry graph must be cleared even though the index did not list it");
			// The graphs this javadoc names are cleared by the separate removeEntry.remove(rc) call, not by
			// rc.clear(entryURI) — so asserting only the entry graph left a regression that dropped that
			// call passing, while leaving behind exactly the orphaned data this test claims to pin.
			assertFalse(rc.hasStatement(null, null, null, false, childMetadataIRI),
				"the child metadata graph must be cleared too");
			assertFalse(rc.hasStatement(null, null, null, false, childRelationIRI),
				"the child relation graph must be cleared too");
		}
	}

	/**
	 * ENTRYSTORE-1095. Branch coverage for the overflow path, which is the one branch of this design that
	 * deliberately drops writes and had none — {@code indexOpBufferLimit} was {@code private static
	 * final}, so nothing could reach it.
	 *
	 * <p>Explicitly <b>not</b> a guard for the mid-scan flag race; an earlier revision of this test
	 * claimed to be one and was not. Every write below commits before the single read, so no overflow
	 * ever happens <em>during</em> a scan and the rescan is never the thing being tested — the store
	 * simply holds the truth and the load must reconstruct it.
	 * {@link #writeDroppedByAnOverflowDuringTheScan_isNotLost} covers the race.
	 */
	@Test
	public void writesPastTheBufferLimit_areAllVisibleAfterTheFirstRead() {
		Entry contextEntry = cm.createResource(null, GraphType.Context, null, null);
		ContextImpl overflowing = (ContextImpl) contextEntry.getResource();
		overflowing.indexOpBufferLimit = 3;

		Set<URI> created = new HashSet<>();
		for (int i = 0; i < overflowing.indexOpBufferLimit * 3; i++) {
			created.add(overflowing.createLink(null,
				URI.create("http://example.com/overflow/" + i), null).getEntryURI());
		}

		Set<URI> listed = overflowing.getEntries();

		assertTrue(listed.containsAll(created),
			"no entry may go missing when the pending-write buffer overflows before the first read; "
				+ "missing: " + created.stream().filter(uri -> !listed.contains(uri)).toList());
		assertTrue(overflowing.isIndexComplete(),
			"and the index must not claim completeness while reporting a short listing");
	}

	/**
	 * ENTRYSTORE-1095. A write dropped by a buffer overflow that happens <em>while a scan is running</em>
	 * must not go missing: that scan may have passed the entry's part of the graph before the write
	 * committed, so neither the scan nor the discarded buffer holds it, and only a rescan recovers it.
	 *
	 * <p>The hook fires on the first two scans, so both the optimistic scan and the scan that follows it
	 * under {@code entry.repository} lose the race. Against the previous revision the second one still
	 * published, because it reset the overflow flag on the strength of holding that monitor — which does
	 * not in fact exclude every index writer, since {@code EntryImpl.setResourceURI} commits and then
	 * calls in after releasing it.
	 *
	 * <p>A seam is intrusive and this is the only one in the class. It is here because a sequential test
	 * cannot produce this interleaving — every write it makes commits before its read — and because the
	 * last attempt to guard this branch without one silently guarded nothing.
	 */
	@Test
	public void writeDroppedByAnOverflowDuringTheScan_isNotLost() {
		Entry contextEntry = cm.createResource(null, GraphType.Context, null, null);
		ContextImpl overflowing = (ContextImpl) contextEntry.getResource();
		// One op is enough to fill the buffer, so every create inside the hook trips the limit.
		overflowing.indexOpBufferLimit = 1;
		overflowing.createLink(null, URI.create("http://example.com/before-the-scan"), null);

		Set<URI> createdDuringAScan = new HashSet<>();
		AtomicInteger scans = new AtomicInteger();
		overflowing.indexScanHookForTests = () -> {
			int scan = scans.incrementAndGet();
			if (scan > 2) {
				// Let the third scan finish, or the retry loop never terminates.
				return;
			}
			for (String suffix : new String[]{"a", "b"}) {
				createdDuringAScan.add(overflowing.createLink(null,
					URI.create("http://example.com/during-scan-" + scan + "-" + suffix), null).getEntryURI());
			}
		};

		Set<URI> listed;
		try {
			listed = overflowing.getEntries();
		} finally {
			overflowing.indexScanHookForTests = null;
		}

		assertTrue(listed.containsAll(createdDuringAScan),
			"a write dropped by an overflow during a scan must be recovered by the rescan; missing: "
				+ createdDuringAScan.stream().filter(uri -> !listed.contains(uri)).toList());
		assertEquals(3, scans.get(),
			"and both scans that raced an overflow must have been discarded rather than published");
	}

	/**
	 * ENTRYSTORE-1095. Deleting a context must not clear a graph belonging to a different context.
	 * {@code resolveChild} answers null for an id belonging to another context as well as for an
	 * unparseable one, and the branch that handles null used to clear whatever the statement named — so a
	 * {@code resHasEntry} object pointing into a neighbouring context destroyed that entry's graph, with
	 * the {@code systemEntries} guard skipped. {@code importContext} copies index-graph objects verbatim,
	 * so restoring an export into the same repository is enough to plant one.
	 */
	@Test
	public void removeContext_leavesAChildNamedInAnotherContextAlone() throws Exception {
		Entry neighbourContextEntry = cm.createResource(null, GraphType.Context, null, null);
		ContextImpl neighbour = (ContextImpl) neighbourContextEntry.getResource();
		EntryImpl bystander = (EntryImpl) neighbour.createLink(null,
			URI.create("http://example.com/innocent-bystander"), null);
		IRI bystanderEntryIRI = bystander.getSesameEntryURI();
		IRI bystanderMetadataIRI = bystander.getSesameLocalMetadataURI();
		// A freshly created link has an empty metadata graph, so "no statements" would hold whether or not
		// the deletion cleared it. Put something there, or the assertion below proves nothing.
		try (RepositoryConnection rc = rm.getRepository().getConnection()) {
			ValueFactory vf = rc.getValueFactory();
			rc.add(vf.createIRI(bystander.getResourceURI().toString()),
				vf.createIRI("http://purl.org/dc/terms/title"), vf.createLiteral("bystander"),
				bystanderMetadataIRI);
		}

		Entry doomedContextEntry = cm.createResource(null, GraphType.Context, null, null);
		ContextImpl doomed = (ContextImpl) doomedContextEntry.getResource();
		// Plant a resHasEntry in the doomed context whose object names the neighbour's entry. resolveChild
		// does NOT answer null for it: the bystander was created moments ago, so the repository-wide
		// SoftCache still holds it and getByEntryURI returns it live — only getByMMdURIDirect, on the
		// cache-miss path, applies the same-context check. That is exactly why childSplitIfOwned has to
		// run before anything is resolved, and why this test is a real guard rather than a formality.
		try (RepositoryConnection rc = rm.getRepository().getConnection()) {
			rc.add(rc.getValueFactory().createIRI("http://example.com/foreign-resource"),
				RepositoryProperties.resHasEntry, bystanderEntryIRI, doomed.resourceURI);
		}

		cm.remove(doomedContextEntry.getEntryURI());

		try (RepositoryConnection rc = rm.getRepository().getConnection()) {
			assertTrue(rc.hasStatement(null, null, null, false, bystanderEntryIRI),
				"deleting a context must not clear an entry graph belonging to another context");
			assertTrue(rc.hasStatement(null, null, null, false, bystanderMetadataIRI),
				"nor its metadata graph");
		}
		assertNotNull(neighbour.getByEntryURI(bystander.getEntryURI()),
			"and the bystander must still be loadable from its own context");
	}

	/**
	 * ENTRYSTORE-1095. Deleting a context must clear <b>every</b> graph of a child it owns but cannot
	 * load, not just the ones that are easy to name. {@code clearUnresolvableChild} stands in for
	 * {@code removeEntry.remove(rc)}, which clears five: entry, metadata, cached external metadata,
	 * relations and — through {@code resource.remove(rc)} — the resource graph. Clearing four of them
	 * leaves the fifth behind while {@code rc.clear(this.resourceURI)} destroys the index graph that
	 * named it, which is the orphan this whole path exists to prevent.
	 *
	 * <p>The child is a {@code List}, deliberately. A {@code Link} has no resource graph, so the two
	 * sibling tests could not have caught the omission — and a {@code List}'s resource graph is where its
	 * member list lives, which is the data that would actually survive.
	 *
	 * <p>The graph URIs are spelled out here rather than derived through {@code URISplit}, so the test
	 * pins the naming convention independently of the helper the production code uses to rebuild it.
	 */
	@Test
	public void removeContext_clearsEveryGraphOfAnUnresolvableChild() throws Exception {
		Entry doomedContextEntry = cm.createResource(null, GraphType.Context, null, null);
		ContextImpl doomed = (ContextImpl) doomedContextEntry.getResource();
		EntryImpl child = (EntryImpl) doomed.createResource(null, GraphType.List, null, null);
		((List) child.getResource()).addChild(
			doomed.createLink(null, URI.create("http://example.com/list-member"), null).getEntryURI());

		String base = rm.getRepositoryURL().toString() + doomed.id + "/";
		String childId = child.getId();
		IRI childEntryIRI = child.getSesameEntryURI();

		try (RepositoryConnection rc = rm.getRepository().getConnection()) {
			ValueFactory vf = rc.getValueFactory();
			IRI subject = vf.createIRI(base + "resource/" + childId);
			IRI predicate = vf.createIRI("http://purl.org/dc/terms/title");
			// Planted rather than assumed: a freshly created entry leaves most of these graphs empty, so
			// "no statements afterwards" would hold whether or not the deletion cleared them.
			for (String path : new String[]{"metadata", "cached-external-metadata", "relations"}) {
				rc.add(subject, predicate, vf.createLiteral(path), vf.createIRI(base + path + "/" + childId));
			}
			// And make the entry unloadable, which is the branch under test. The resHasEntry triple naming
			// it lives in the context graph, so the deletion still finds the child.
			rc.clear(childEntryIRI);
		}
		// Force the load path; a cached entry would resolve and take the normal removal branch instead.
		doomed.softCache.remove(child);

		cm.remove(doomedContextEntry.getEntryURI());

		try (RepositoryConnection rc = rm.getRepository().getConnection()) {
			ValueFactory vf = rc.getValueFactory();
			for (String path : new String[]{"metadata", "cached-external-metadata", "relations", "resource"}) {
				assertFalse(rc.hasStatement(null, null, null, false, vf.createIRI(base + path + "/" + childId)),
					"the " + path + " graph of an unresolvable child must be cleared, or it outlives the "
						+ "index graph that named it");
			}
			assertFalse(rc.hasStatement(null, null, null, false, childEntryIRI),
				"and its entry graph too");
		}
	}

	/**
	 * ENTRYSTORE-1095. {@code importContext} deletes what it enumerates before importing, so it must not
	 * derive that list from an index a bad statement made short. Enumerating from the store is what
	 * replaced the previous refusal, which had no repair path: {@code reIndex()} regenerates the
	 * offending statement from the entry graph, and its only production call site sits downstream of the
	 * refusal in the same method — so one unparseable triple locked the context out of imports for good.
	 */
	@Test
	public void getChildEntryURIsFromStore_seesEntriesTheIndexCouldNotParse() {
		Entry contextEntry = cm.createResource(null, GraphType.Context, null, null);
		ContextImpl owner = (ContextImpl) contextEntry.getResource();
		EntryImpl hidden = (EntryImpl) owner.createLink(null,
			URI.create("http://example.com/hidden-from-the-index"), null);
		hideFromIndex(owner, hidden);

		((ContextManagerImpl) cm).softCache.remove(contextEntry);
		ContextImpl reloaded = (ContextImpl) cm.getContext(owner.id);

		assertFalse(reloaded.getEntries().contains(hidden.getEntryURI()),
			"the index must be short for this test to mean anything");
		assertFalse(reloaded.isIndexComplete(), "and must know that it is");
		assertTrue(reloaded.getChildEntryURIsFromStore().contains(hidden.getEntryURI()),
			"the store-backed enumeration must still see the entry the index skipped");
	}

	/**
	 * ENTRYSTORE-1095. The store-backed enumeration bypasses the index, so it also bypasses whatever the
	 * index would have refused to parse — including a {@code resHasEntry} object naming another context's
	 * entry. Both consumers act destructively on what it returns: {@code importContext} removes it before
	 * importing, and {@code PublicRepository.rebuildRepository} republishes it. Neither can detect a
	 * foreign child on its own, because {@code SoftCache} is repository-wide and hands back a cached one
	 * live rather than null, so the ownership filter belongs here where both inherit it.
	 */
	@Test
	public void getChildEntryURIsFromStore_skipsAChildNamedInAnotherContext() {
		Entry neighbourContextEntry = cm.createResource(null, GraphType.Context, null, null);
		ContextImpl neighbour = (ContextImpl) neighbourContextEntry.getResource();
		EntryImpl bystander = (EntryImpl) neighbour.createLink(null,
			URI.create("http://example.com/enumerated-bystander"), null);

		Entry contextEntry = cm.createResource(null, GraphType.Context, null, null);
		ContextImpl owner = (ContextImpl) contextEntry.getResource();
		EntryImpl own = (EntryImpl) owner.createLink(null, URI.create("http://example.com/own-child"), null);
		try (RepositoryConnection rc = rm.getRepository().getConnection()) {
			rc.add(rc.getValueFactory().createIRI("http://example.com/foreign-resource"),
				RepositoryProperties.resHasEntry, bystander.getSesameEntryURI(), owner.resourceURI);
		}

		Set<URI> enumerated = owner.getChildEntryURIsFromStore();

		assertTrue(enumerated.contains(own.getEntryURI()),
			"the context's own child must still be enumerated");
		assertFalse(enumerated.contains(bystander.getEntryURI()),
			"an entry belonging to another context must not be handed to callers that delete or "
				+ "republish what this returns");
	}

	/**
	 * Adds a {@code resHasEntry} statement to {@code owner}'s graph whose subject is a valid IRI that
	 * {@code java.net.URI.create} rejects, so {@code loadIndex} cannot index it.
	 */
	private void addUnindexableResHasEntryStatement(ContextImpl owner, URI entryURI) {
		try (RepositoryConnection rc = rm.getRepository().getConnection()) {
			ValueFactory vf = rc.getValueFactory();
			rc.add(vf.createIRI(UNPARSEABLE_IRI), RepositoryProperties.resHasEntry,
				vf.createIRI(entryURI.toString()), owner.resourceURI);
		}
	}

	/**
	 * Replaces an entry's {@code resHasEntry} subject with one {@code java.net.URI.create} rejects, so the
	 * entry is unreachable through the in-memory index but still named in the context graph.
	 */
	private void hideFromIndex(ContextImpl owner, EntryImpl child) {
		try (RepositoryConnection rc = rm.getRepository().getConnection()) {
			ValueFactory vf = rc.getValueFactory();
			rc.remove(child.getSesameResourceURI(), RepositoryProperties.resHasEntry,
				child.getSesameEntryURI(), owner.resourceURI);
			rc.add(vf.createIRI(UNPARSEABLE_IRI), RepositoryProperties.resHasEntry,
				child.getSesameEntryURI(), owner.resourceURI);
		}
	}

	private void evictFromSoftCache(Entry e) {
		context.softCache.remove(e);
	}

	/**
	 * Runs {@code taskCount} tasks on virtual threads, released together, and fails if they have not all
	 * returned within {@link #TASK_BUDGET_SECONDS}.
	 *
	 * <p>A task that loops on another task's progress must also stop when the {@code keepRunning}
	 * supplier it is handed goes false, which happens as soon as the budget expires. Nothing else can
	 * stop such a task: {@code shutdownNow} interrupts, and neither a spin nor a blocked
	 * {@code synchronized} acquisition responds to an interrupt.
	 *
	 * <p>The executor is shut down in a {@code finally} rather than closed by try-with-resources, and
	 * the timeout is asserted after that block rather than inside it. {@code ExecutorService.close()}
	 * calls {@code shutdown()} — which does not interrupt running tasks — and then waits for
	 * termination without a bound, so closing while a task still runs turned the timeout below into an
	 * unbounded hang: the assertion failure propagated into {@code close()} and never came out. The
	 * build then went silent instead of reporting a failing test (ENTRYSTORE-1095).
	 *
	 * <p>Failures are collected as {@code Throwable}, not {@code Exception}. An {@code AssertionError}
	 * raised inside a task body is not an {@code Exception}: it escaped the catch, was swallowed by the
	 * {@code Future} nobody inspected, and {@code done.countDown()} still ran — so the run reported
	 * success. In a harness whose whole purpose is that a concurrency failure must be visible, that was
	 * the remaining hole.
	 */
	private static void runConcurrently(int taskCount, ConcurrentTask task) throws InterruptedException {
		CountDownLatch start = new CountDownLatch(1);
		CountDownLatch done = new CountDownLatch(taskCount);
		java.util.List<Throwable> failures = Collections.synchronizedList(new ArrayList<>());
		AtomicBoolean keepRunning = new AtomicBoolean(true);

		ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
		java.util.List<Future<?>> futures = new ArrayList<>(taskCount);
		boolean completed;
		try {
			for (int i = 0; i < taskCount; i++) {
				int index = i;
				futures.add(executor.submit(() -> {
					try {
						start.await();
						task.accept(index, keepRunning::get);
					} catch (Throwable t) {
						failures.add(t);
					} finally {
						done.countDown();
					}
				}));
			}

			start.countDown();
			completed = done.await(TASK_BUDGET_SECONDS, TimeUnit.SECONDS);
		} finally {
			keepRunning.set(false);
			executor.shutdownNow();
		}

		// Belt to the catch above: anything a task threw before reaching it, or that the executor itself
		// raised, is only visible through the Future.
		for (Future<?> future : futures) {
			if (future.isDone() && !future.isCancelled()) {
				try {
					future.get();
				} catch (ExecutionException e) {
					failures.add(e.getCause());
				}
			}
		}

		assertTrue(completed,
			() -> "Concurrent tasks did not complete within timeout: " + done.getCount()
				+ " still pending; " + failures.size() + " already failed: " + failures);
		assertTrue(failures.isEmpty(), () -> "Task failures: " + failures);
	}

	/**
	 * One task in a {@link #runConcurrently} run. {@code keepRunning} reads false once the run is out of
	 * budget; a task that does not loop on another task's progress can ignore it.
	 */
	@FunctionalInterface
	private interface ConcurrentTask {
		void accept(int index, BooleanSupplier keepRunning) throws Exception;
	}

}
