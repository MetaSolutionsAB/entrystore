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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntConsumer;
import org.entrystore.Entry;
import org.entrystore.GraphType;
import org.entrystore.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

public class ContextImplTest extends AbstractCoreTest {

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
		runConcurrently(threadCount, _ -> {
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
		runConcurrently(entryCount, i -> {
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
							Thread.onSpinWait();
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
	 * it, and the two use no common monitor — the readers synchronize on nothing, the write path on
	 * {@code entry.repository}. With a plain {@code HashMap} index this throws
	 * {@code ConcurrentModificationException}.
	 */
	@Test
	public void getEntries_concurrentWithEntryCreation_doesNotThrow() throws Exception {
		// Seed entries so there is something to iterate; an empty index cannot fail.
		for (int i = 0; i < 20; i++) {
			context.createLink(null, URI.create("http://example.com/seed/" + i), null);
		}

		int readers = 8;
		int writers = 4;
		runConcurrently(readers + writers, index -> {
			if (index < readers) {
				for (int i = 0; i < 300; i++) {
					context.getEntries();
				}
			} else {
				for (int i = 0; i < 30; i++) {
					context.createLink(null, URI.create("http://example.com/w" + index + "/" + i), null);
				}
			}
		});
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
		runConcurrently(readers + writers, index -> {
			if (index < readers) {
				for (int i = 0; i < 300; i++) {
					context.getByResourceURI(shared);
				}
			} else {
				for (int i = 0; i < 15; i++) {
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
	 * ENTRYSTORE-1095. {@code reIndex} nulls the index fields, so a reader must never observe a
	 * truncated listing while it republishes.
	 *
	 * <p>Asserts the observed size rather than only the absence of an exception, so a reader that
	 * answered "empty" instead of throwing would be caught. This one is a guard, not a reproduction:
	 * unlike the other three it does not fail against the unfixed code, because a reader that observes
	 * the null calls loadIndex, which contends for the same {@code this.entry} monitor reIndex holds and
	 * so always reloads a complete index. Verified by reverting the production change and re-running.
	 */
	@Test
	public void getEntries_concurrentWithReIndex_neverObservesATruncatedListing() throws Exception {
		int seeded = 10;
		for (int i = 0; i < seeded; i++) {
			context.createLink(null, URI.create("http://example.com/reindex/" + i), null);
		}
		int expected = context.getEntries().size();

		AtomicInteger smallestSeen = new AtomicInteger(Integer.MAX_VALUE);
		int readers = 8;
		runConcurrently(readers + 1, index -> {
			if (index < readers) {
				for (int i = 0; i < 200; i++) {
					smallestSeen.accumulateAndGet(context.getEntries().size(), Math::min);
				}
			} else {
				for (int i = 0; i < 5; i++) {
					context.reIndex();
				}
			}
		});

		assertEquals(expected, smallestSeen.get(),
			"getEntries must never observe a partial or empty index while reIndex republishes it");
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

		runConcurrently(count, i -> {
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
	 * {@code getByResourceURI} but had no coverage, so neither the promote-to-set path for the
	 * external-metadata index nor the iteration of that set was exercised anywhere.
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
		runConcurrently(readers + writers, index -> {
			if (index < readers) {
				for (int i = 0; i < 300; i++) {
					context.getByExternalMdURI(sharedMd);
				}
			} else {
				for (int i = 0; i < 15; i++) {
					context.createReference(null,
						URI.create("http://example.com/ref/w" + index + "/" + i), sharedMd, null);
				}
			}
		});
	}

	private void evictFromSoftCache(Entry e) {
		context.softCache.remove(e);
	}

	/**
	 * Submits {@code taskCount} copies of {@code task} to a virtual-thread executor, releases them simultaneously via
	 * a start latch, then asserts that all tasks completed without exceptions within 30 seconds.
	 */
	private static void runConcurrently(int taskCount, IntConsumer task) throws InterruptedException {
		CountDownLatch start = new CountDownLatch(1);
		CountDownLatch done = new CountDownLatch(taskCount);
		java.util.List<Throwable> failures = Collections.synchronizedList(new ArrayList<>());

		try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
			for (int i = 0; i < taskCount; i++) {
				int index = i;
				executor.submit(() -> {
					try {
						start.await();
						task.accept(index);
					} catch (Exception e) {
						failures.add(e);
					} finally {
						done.countDown();
					}
				});
			}

			start.countDown();
			assertTrue(done.await(30, TimeUnit.SECONDS),
				() -> "Concurrent tasks did not complete within timeout: " + done.getCount()
					+ " still pending; " + failures.size() + " already failed: " + failures);
		}

		assertTrue(failures.isEmpty(), () -> "Task failures: " + failures);
	}

}
