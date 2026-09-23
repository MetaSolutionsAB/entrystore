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

import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.request.UpdateRequest;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.entrystore.Context;
import org.entrystore.ContextManager;
import org.entrystore.Entry;
import org.entrystore.PrincipalManager;
import org.entrystore.SearchIndex.ReindexResult;
import org.entrystore.User;
import org.entrystore.config.Config;
import org.entrystore.repository.RepositoryManager;
import org.entrystore.repository.config.PropertiesConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import javax.xml.datatype.DatatypeFactory;
import javax.xml.datatype.XMLGregorianCalendar;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URI;
import java.time.Instant;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class SolrSearchIndexTest {

	private static final URI CONTEXT_1 = URI.create("http://localhost:8181/_contexts/entry/1");
	private static final URI CONTEXT_2 = URI.create("http://localhost:8181/_contexts/entry/2");
	private static final URI ENTRY_1_1 = URI.create("http://localhost:8181/1/entry/1");
	private static final URI ENTRY_1_2 = URI.create("http://localhost:8181/1/entry/2");
	private static final URI ENTRY_2_1 = URI.create("http://localhost:8181/2/entry/1");

	private RepositoryManager rm;
	private SolrClient solrServer;
	private SolrSearchIndex index;
	private Map<URI, Future> reindexingMap;

	@BeforeEach
	@SuppressWarnings("unchecked")
	public void setUp() throws Exception {
		rm = mock(RepositoryManager.class);
		Config config = new PropertiesConfiguration("EntryStore Test Configuration");
		when(rm.getConfiguration()).thenReturn(config);
		when(rm.getValueFactory()).thenReturn(SimpleValueFactory.getInstance());
		solrServer = mock(SolrClient.class);

		index = new SolrSearchIndex(rm, solrServer);

		Field f = SolrSearchIndex.class.getDeclaredField("reindexing");
		f.setAccessible(true);
		reindexingMap = (Map<URI, Future>) f.get(index);
	}

	@AfterEach
	public void tearDown() {
		if (index != null) {
			index.shutdown();
		}
	}

	@Test
	public void isIndexingReturnsTrueWhenAnyContextIsBeingIndexed() {
		URI ctx = URI.create("http://localhost:8181/_contexts/entry/1");
		reindexingMap.put(ctx, CompletableFuture.completedFuture(null));

		assertTrue(index.isIndexing(), "isIndexing() must be true while any per-context reindex is in progress");
	}

	@Test
	public void isIndexingReturnsFalseWhenNoContextIsBeingIndexed() {
		assertFalse(index.isIndexing(), "isIndexing() must be false when reindexing map is empty");
	}

	@Test
	public void isIndexingWithUriReturnsTrueOnlyForMatchingContext() {
		URI ctx1 = URI.create("http://localhost:8181/_contexts/entry/1");
		URI ctx2 = URI.create("http://localhost:8181/_contexts/entry/2");
		reindexingMap.put(ctx1, CompletableFuture.completedFuture(null));

		assertTrue(index.isIndexing(ctx1));
		assertFalse(index.isIndexing(ctx2));
	}

	@Test
	public void isIndexingWithNullReturnsFalseWhenOnlyPerContextEntriesPresent() {
		// Regression-protect: the no-arg method must NOT silently probe the `null` key,
		// and the URI overload must keep its literal containsKey() semantics.
		URI ctx = URI.create("http://localhost:8181/_contexts/entry/1");
		reindexingMap.put(ctx, CompletableFuture.completedFuture(null));

		assertFalse(index.isIndexing(null));
	}

	@Test
	public void reindexSyncContinuesWithRemainingContextsWhenOneContextFails() {
		ContextManager cm = contextManagerListing(CONTEXT_1, CONTEXT_2);
		when(cm.getByEntryURI(CONTEXT_1)).thenThrow(new org.entrystore.repository.RepositoryException("Unable to load entry " + CONTEXT_1));
		resolvableContext(cm, "2", deletedEntry(cm, ENTRY_2_1));

		ReindexResult result = index.reindexSync(false);

		assertEquals(new ReindexResult(1, 0, false), result);
		verify(cm).getByEntryURI(CONTEXT_2);
	}

	@Test
	public void reindexSyncContinuesWithRemainingContextsWhenOneContextOverflowsTheStack() {
		ContextManager cm = contextManagerListing(CONTEXT_1, CONTEXT_2);
		when(cm.getByEntryURI(CONTEXT_1)).thenThrow(new StackOverflowError());
		resolvableContext(cm, "2", deletedEntry(cm, ENTRY_2_1));

		ReindexResult result = index.reindexSync(false);

		assertEquals(new ReindexResult(1, 0, false), result);
		verify(cm).getByEntryURI(CONTEXT_2);
	}

	@Test
	public void reindexSyncContinuesWithRemainingEntriesWhenLoadingOneEntryOverflowsTheStack() {
		ContextManager cm = contextManagerListing(CONTEXT_1);
		resolvableContext(cm, "1", ENTRY_1_1, deletedEntry(cm, ENTRY_1_2));
		when(cm.getEntry(ENTRY_1_1)).thenThrow(new StackOverflowError());

		ReindexResult result = index.reindexSync(false);

		assertEquals(new ReindexResult(0, 1, false), result);
		verify(cm).getEntry(ENTRY_1_2);
	}

	@Test
	public void reindexSyncCountsEntriesThatCannotBeLoaded() {
		ContextManager cm = contextManagerListing(CONTEXT_1);
		resolvableContext(cm, "1", ENTRY_1_1, deletedEntry(cm, ENTRY_1_2));
		// cm.getEntry(ENTRY_1_1) is not stubbed and returns null, as for an entry whose graph is missing

		ReindexResult result = index.reindexSync(false);

		assertEquals(new ReindexResult(0, 1, false), result);
	}

	@Test
	public void reindexSyncCountsContextsThatCannotBeResolvedAsFailed() {
		ContextManager cm = contextManagerListing(CONTEXT_1, CONTEXT_2);
		// cm.getContext(...) is not stubbed and returns null for both contexts

		ReindexResult result = index.reindexSync(false);

		assertEquals(new ReindexResult(2, 0, false), result);
	}

	@Test
	public void reindexSyncReportsNoFailuresWhenAllContextsAreIndexed() {
		ContextManager cm = contextManagerListing(CONTEXT_1, CONTEXT_2);
		resolvableContext(cm, "1", deletedEntry(cm, ENTRY_1_1));
		resolvableContext(cm, "2", deletedEntry(cm, ENTRY_2_1));

		ReindexResult result = index.reindexSync(false);

		assertEquals(new ReindexResult(0, 0, false), result);
		assertFalse(result.hasFailures());
	}

	@Test
	public void reindexSyncStopsAndReportsInterruptionWhenThreadIsInterrupted() {
		ContextManager cm = contextManagerListing(CONTEXT_1, CONTEXT_2);
		resolvableContext(cm, "1", deletedEntry(cm, ENTRY_1_1));

		ReindexResult result;
		try {
			Thread.currentThread().interrupt();
			result = index.reindexSync(false);
		} finally {
			Thread.interrupted();
		}

		assertTrue(result.interrupted());
		verify(cm, never()).getByEntryURI(CONTEXT_2);
	}

	@Test
	public void reindexOfContextPurgesExpiredDocumentsWhenAllEntriesWereProcessed() throws Exception {
		ContextManager cm = contextManagerListing(CONTEXT_1);
		resolvableContext(cm, "1", deletedEntry(cm, ENTRY_1_1));
		stubContextEntry(cm, CONTEXT_1, "http://localhost:8181/1");

		index.reindexSync(CONTEXT_1, false);
		index.shutdown(); // waits for the delayed purge

		ArgumentCaptor<UpdateRequest> captor = ArgumentCaptor.forClass(UpdateRequest.class);
		verify(solrServer).request(captor.capture(), any());
		String deleteQuery = captor.getValue().getDeleteQuery().getFirst();
		assertTrue(deleteQuery.startsWith("indexedAt:[* TO "), deleteQuery);
		assertTrue(deleteQuery.endsWith(" AND context:http\\:\\/\\/localhost\\:8181\\/1"), deleteQuery);
	}

	@Test
	public void reindexOfContextKeepsIndexedDocumentsWhenAnEntryFailed() throws Exception {
		ContextManager cm = contextManagerListing(CONTEXT_1);
		resolvableContext(cm, "1", ENTRY_1_1, deletedEntry(cm, ENTRY_1_2));
		when(cm.getEntry(ENTRY_1_1)).thenThrow(new org.entrystore.repository.RepositoryException("Unable to load entry " + ENTRY_1_1));
		stubContextEntry(cm, CONTEXT_1, "http://localhost:8181/1");

		index.reindexSync(CONTEXT_1, false);
		index.shutdown(); // waits for a delayed purge, if one was scheduled

		verify(solrServer, never()).request(any(), any());
	}

	@Test
	public void fastAsyncReindexClearsIndexingStateWhenCleanupStartsBeforeSubmitReturns() throws Exception {
		contextManagerListing(CONTEXT_1);
		Field executorField = SolrSearchIndex.class.getDeclaredField("reindexExecutor");
		executorField.setAccessible(true);
		((ExecutorService) executorField.get(index)).shutdown();
		AtomicReference<Thread> worker = new AtomicReference<>();
		ThreadPoolExecutor fastExecutor = new ThreadPoolExecutor(
				1, 1, 0, TimeUnit.SECONDS, new LinkedBlockingQueue<>(), task -> {
			Thread thread = new Thread(task, "fast-reindex-test");
			worker.set(thread);
			return thread;
		}) {
			@Override
			public void execute(Runnable task) {
				super.execute(task);
				// Hold submit() open until cleanup waits on the map monitor held by the submitting thread.
				// This exercises a fast worker without relying on scheduling luck or arbitrary sleeps.
				long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
				var threadMXBean = ManagementFactory.getThreadMXBean();
				while (System.nanoTime() < deadline) {
					ThreadInfo info = threadMXBean.getThreadInfo(worker.get().threadId());
					if (info != null && info.getThreadState() == Thread.State.BLOCKED
							&& info.getLockOwnerId() == Thread.currentThread().threadId()
							&& info.getLockInfo().getIdentityHashCode() == System.identityHashCode(reindexingMap)) {
						return;
					}
					Thread.yield();
				}
				throw new AssertionError("Worker did not reach reindex cleanup");
			}
		};
		executorField.set(index, fastExecutor);

		index.reindex(CONTEXT_1, false);
		reindexingFutureOrCompleted(CONTEXT_1).get(10, TimeUnit.SECONDS);

		assertFalse(index.isIndexing(CONTEXT_1), "A completed reindex must clear its mapping even if cleanup starts before submit returns");
	}

	@Test
	public void asyncReindexClearsIndexingStateWhenContextFails() throws Exception {
		ContextManager cm = contextManagerListing(CONTEXT_1);
		when(cm.getByEntryURI(CONTEXT_1)).thenThrow(new org.entrystore.repository.RepositoryException("Unable to load entry " + CONTEXT_1));

		index.reindex(CONTEXT_1, false);
		reindexingFutureOrCompleted(CONTEXT_1).get(10, TimeUnit.SECONDS);

		verify(cm).getByEntryURI(CONTEXT_1);
		assertFalse(index.isIndexing(CONTEXT_1), "A failed reindex must not leave the context marked as indexing");
	}

	@Test
	public void asyncReindexClearsIndexingStateWhenContextFailsWithError() throws Exception {
		ContextManager cm = contextManagerListing(CONTEXT_1);
		when(cm.getByEntryURI(CONTEXT_1)).thenThrow(new OutOfMemoryError("simulated"));

		index.reindex(CONTEXT_1, false);
		reindexingFutureOrCompleted(CONTEXT_1).get(10, TimeUnit.SECONDS);

		verify(cm).getByEntryURI(CONTEXT_1);
		assertFalse(index.isIndexing(CONTEXT_1), "An Error must not leave the context marked as indexing");
	}

	@Test
	public void cancelledAsyncReindexKeepsIndexingStateOfItsReplacement() throws Exception {
		ContextManager cm = contextManagerListing(CONTEXT_1);
		CountDownLatch firstStarted = new CountDownLatch(1);
		CountDownLatch replacementStarted = new CountDownLatch(1);
		CountDownLatch releaseReplacement = new CountDownLatch(1);
		when(cm.getByEntryURI(CONTEXT_1))
				.thenAnswer(invocation -> {
					firstStarted.countDown();
					awaitOrThrow(new CountDownLatch(1)); // blocks until cancelled
					return null;
				})
				.thenAnswer(invocation -> {
					replacementStarted.countDown();
					awaitOrThrow(releaseReplacement);
					return null;
				});

		index.reindex(CONTEXT_1, false);
		assertTrue(firstStarted.await(10, TimeUnit.SECONDS));
		index.reindex(CONTEXT_1, false); // cancels the first task and queues its replacement
		// The single reindex thread starts the replacement only after the cancelled task has unwound
		assertTrue(replacementStarted.await(10, TimeUnit.SECONDS));

		assertTrue(index.isIndexing(CONTEXT_1), "The cancelled task must not remove the mapping of its running replacement");
		releaseReplacement.countDown();
	}

	/**
	 * The indexer removes itself from the map when it finishes, so a missing future means it is already done.
	 */
	private Future<?> reindexingFutureOrCompleted(URI contextURI) {
		Future<?> indexer = reindexingMap.get(contextURI);
		return indexer != null ? indexer : CompletableFuture.completedFuture(null);
	}

	/**
	 * Waits for the latch; an interrupt (from cancelling the reindex task) ends the wait with an exception.
	 */
	private static void awaitOrThrow(CountDownLatch latch) {
		try {
			if (!latch.await(10, TimeUnit.SECONDS)) {
				throw new IllegalStateException("Latch was not released");
			}
		} catch (InterruptedException e) {
			throw new IllegalStateException("Interrupted", e);
		}
	}

	/**
	 * Stubs a context manager listing the given contexts. Unless stubbed further, none of them resolves to a
	 * context, so that reindexing each of them completes without posting documents.
	 */
	private ContextManager contextManagerListing(URI... contextURIs) {
		PrincipalManager pm = mock(PrincipalManager.class);
		User admin = mock(User.class);
		when(admin.getURI()).thenReturn(URI.create("http://localhost:8181/_principals/resource/_admin"));
		when(pm.getAdminUser()).thenReturn(admin);
		when(rm.getPrincipalManager()).thenReturn(pm);

		ContextManager cm = mock(ContextManager.class);
		when(cm.getEntries()).thenReturn(new LinkedHashSet<>(List.of(contextURIs)));
		when(rm.getContextManager()).thenReturn(cm);
		return cm;
	}

	/**
	 * Stubs a context with the given ID that lists the given entries.
	 */
	private static void resolvableContext(ContextManager cm, String contextId, URI... entryURIs) {
		Context context = mock(Context.class);
		when(context.getEntries()).thenReturn(new LinkedHashSet<>(List.of(entryURIs)));
		when(cm.getContext(contextId)).thenReturn(context);
	}

	/**
	 * Stubs an entry that loads but is not posted because it is deleted, which keeps Solr documents out of
	 * the tests that only exercise the reindex bookkeeping.
	 */
	private static URI deletedEntry(ContextManager cm, URI entryURI) {
		Entry entry = mock(Entry.class);
		when(entry.isDeleted()).thenReturn(true);
		when(cm.getEntry(entryURI)).thenReturn(entry);
		return entryURI;
	}

	private static void stubContextEntry(ContextManager cm, URI contextURI, String contextResourceURI) {
		Entry contextEntry = mock(Entry.class);
		when(contextEntry.getResourceURI()).thenReturn(URI.create(contextResourceURI));
		when(cm.getByEntryURI(contextURI)).thenReturn(contextEntry);
	}

	@Disabled("To be implemented")
	@Test
	public void testShutdown() throws Exception {
		// TODO
	}

	@Test
	public void clearSolrIndexBuildsUtcMillisecondDateRangeDeleteQuery() throws Exception {
		SolrClient client = mock(SolrClient.class);
		Date expiration = Date.from(Instant.parse("2024-01-15T10:30:00.123Z"));

		assertTrue(index.clearSolrIndex(client, expiration, null));

		ArgumentCaptor<UpdateRequest> captor = ArgumentCaptor.forClass(UpdateRequest.class);
		verify(client).request(captor.capture(), any());
		assertEquals(
			"indexedAt:[* TO 2024\\-01\\-15T10\\:30\\:00.123Z}",
			captor.getValue().getDeleteQuery().getFirst());
	}

	@Test
	public void clearSolrIndexBuildsContextOnlyDeleteQuery() throws Exception {
		SolrClient client = mock(SolrClient.class);
		Entry contextEntry = mock(Entry.class);
		when(contextEntry.getResourceURI()).thenReturn(URI.create("http://localhost:8181/1"));

		assertTrue(index.clearSolrIndex(client, null, contextEntry));

		ArgumentCaptor<UpdateRequest> captor = ArgumentCaptor.forClass(UpdateRequest.class);
		verify(client).request(captor.capture(), any());
		assertEquals(
			"context:http\\:\\/\\/localhost\\:8181\\/1",
			captor.getValue().getDeleteQuery().getFirst());
	}

	@Test
	public void clearSolrIndexCombinesDateAndContextClausesWithAnd() throws Exception {
		SolrClient client = mock(SolrClient.class);
		Entry contextEntry = mock(Entry.class);
		when(contextEntry.getResourceURI()).thenReturn(URI.create("http://localhost:8181/1"));
		Date expiration = Date.from(Instant.parse("2024-01-15T10:30:00.123Z"));

		assertTrue(index.clearSolrIndex(client, expiration, contextEntry));

		ArgumentCaptor<UpdateRequest> captor = ArgumentCaptor.forClass(UpdateRequest.class);
		verify(client).request(captor.capture(), any());
		assertEquals(
			"indexedAt:[* TO 2024\\-01\\-15T10\\:30\\:00.123Z} AND context:http\\:\\/\\/localhost\\:8181\\/1",
			captor.getValue().getDeleteQuery().getFirst());
	}

	@Test
	public void clearSolrIndexReturnsFalseWhenSolrRequestFails() throws Exception {
		SolrClient client = mock(SolrClient.class);
		when(client.request(any(), any())).thenThrow(new SolrServerException("solr down"));

		assertFalse(index.clearSolrIndex(client, new Date(), null));
	}

	@Test
	public void dateToSolrDateStringNormalizesOffsetAndUndefinedTimezoneToUtc() throws Exception {
		Method dateToSolrDateString = SolrSearchIndex.class.getDeclaredMethod("dateToSolrDateString", XMLGregorianCalendar.class);
		dateToSolrDateString.setAccessible(true);
		DatatypeFactory factory = DatatypeFactory.newInstance();

		// +02:00 offset must be shifted to the same instant in UTC
		XMLGregorianCalendar withOffset = factory.newXMLGregorianCalendar("2024-01-15T12:30:00.123+02:00");
		assertEquals("2024-01-15T10:30:00.123Z", dateToSolrDateString.invoke(index, withOffset));

		// undefined timezone must be interpreted as UTC, not the JVM default zone
		XMLGregorianCalendar undefinedTimezone = factory.newXMLGregorianCalendar("2024-01-15T10:30:00.123");
		assertEquals("2024-01-15T10:30:00.123Z", dateToSolrDateString.invoke(index, undefinedTimezone));
	}

	@Disabled("To be implemented")
	@Test
	public void testReindexLiterals() throws Exception {
		// TODO
	}

	@Disabled("To be implemented")
	@Test
	public void testConstructSolrInputDocument() throws Exception {
		// TODO
	}

	@Disabled("To be implemented")
	@Test
	public void testPostEntry() throws Exception {
		// TODO
	}

	@Disabled("To be implemented")
	@Test
	public void testRemoveEntry() throws Exception {
		// TODO
	}

	@Disabled("To be implemented")
	@Test
	public void testSendQuery() throws Exception {
		// TODO
	}

	@Disabled("To be implemented")
	@Test
	public void testExtractFulltext() throws Exception {
		// TODO
	}
}
