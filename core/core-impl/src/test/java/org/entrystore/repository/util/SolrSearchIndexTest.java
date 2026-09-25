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
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.request.SolrQuery;
import org.apache.solr.client.solrj.request.UpdateRequest;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.common.SolrDocument;
import org.apache.solr.common.SolrDocumentList;
import org.apache.solr.common.SolrInputDocument;
import org.apache.solr.common.params.SolrParams;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.entrystore.Context;
import org.entrystore.ContextManager;
import org.entrystore.Entry;
import org.entrystore.EntryType;
import org.entrystore.PrincipalManager;
import org.entrystore.PrincipalManager.AccessProperty;
import org.entrystore.SearchIndex.ReindexResult;
import org.entrystore.User;
import org.entrystore.config.Config;
import org.entrystore.impl.LocalMetadataWrapper;
import org.entrystore.repository.CorruptEntryException;
import org.entrystore.repository.RepositoryException;
import org.entrystore.repository.RepositoryManager;
import org.entrystore.repository.config.PropertiesConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import javax.xml.datatype.DatatypeFactory;
import javax.xml.datatype.XMLGregorianCalendar;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyBoolean;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class SolrSearchIndexTest {

	private static final URI CONTEXT_1 = URI.create("http://localhost:8181/_contexts/entry/1");
	private static final URI CONTEXT_2 = URI.create("http://localhost:8181/_contexts/entry/2");
	private static final URI ENTRY_1_1 = URI.create("http://localhost:8181/1/entry/1");
	private static final URI ENTRY_1_2 = URI.create("http://localhost:8181/1/entry/2");
	private static final URI ENTRY_1_3 = URI.create("http://localhost:8181/1/entry/3");
	private static final URI ENTRY_2_1 = URI.create("http://localhost:8181/2/entry/1");
	private static final URI METADATA_2_1 = URI.create("http://localhost:8181/2/metadata/1");
	private static final URI PRINCIPAL_ENTRY = URI.create("http://localhost:8181/_principals/entry/5");

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
		when(rm.getRepositoryURL()).thenReturn(URI.create("http://localhost:8181/").toURL());
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
		when(cm.getByEntryURI(CONTEXT_1)).thenThrow(new RepositoryException("Unable to load entry " + CONTEXT_1));
		resolvableContext(cm, "2", deletedEntry(cm, ENTRY_2_1));

		ReindexResult result = index.reindexSync(false);

		assertEquals(new ReindexResult(1, 0, false), result);
		verify(cm).getByEntryURI(CONTEXT_2);
	}

	@Test
	public void reindexSyncRetriesContextThatFailedOnceAfterTheRemainingContexts() {
		ContextManager cm = contextManagerListing(CONTEXT_1, CONTEXT_2);
		when(cm.getByEntryURI(CONTEXT_1))
				.thenThrow(new RepositoryException("Transient failure"))
				.thenReturn(null);
		resolvableContext(cm, "1", deletedEntry(cm, ENTRY_1_1));
		resolvableContext(cm, "2", deletedEntry(cm, ENTRY_2_1));

		ReindexResult result = index.reindexSync(false);

		assertEquals(new ReindexResult(0, 0, false), result);
		InOrder order = inOrder(cm);
		order.verify(cm).getByEntryURI(CONTEXT_1);
		order.verify(cm).getByEntryURI(CONTEXT_2);
		order.verify(cm).getByEntryURI(CONTEXT_1);
	}

	@Test
	public void reindexSyncCountsContextThatFailsAgainOnRetryAsFailed() {
		ContextManager cm = contextManagerListing(CONTEXT_1);
		when(cm.getByEntryURI(CONTEXT_1)).thenThrow(new RepositoryException("Permanent failure"));

		ReindexResult result = index.reindexSync(false);

		assertEquals(new ReindexResult(1, 0, false), result);
		verify(cm, times(2)).getByEntryURI(CONTEXT_1);
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
		boolean interruptStatusKept;
		try {
			Thread.currentThread().interrupt();
			result = index.reindexSync(false);
		} finally {
			interruptStatusKept = Thread.interrupted();
		}

		assertTrue(result.interrupted());
		assertTrue(interruptStatusKept,
				"The caller must still see the interrupt, e.g. to stop waiting for the queue to drain");
		verify(cm, never()).getByEntryURI(CONTEXT_1);
		verify(cm, never()).getByEntryURI(CONTEXT_2);
	}

	@Test
	public void reindexSyncOfContextKeepsTheInterruptFlag() throws Exception {
		ContextManager cm = contextManagerListing(CONTEXT_1);
		resolvableContext(cm, "1", ENTRY_1_1);

		boolean interruptStatusKept;
		try {
			Thread.currentThread().interrupt();
			index.reindexSync(CONTEXT_1, false);
		} finally {
			interruptStatusKept = Thread.interrupted();
		}

		assertTrue(interruptStatusKept, "The caller, e.g. a cancelled reindex task, must still see the interrupt");
		verify(cm, never()).getEntry(any());
	}

	@Test
	public void reindexSyncStopsWithoutRetryWhenContextLoadFailsDuringInterruption() {
		ContextManager cm = contextManagerListing(CONTEXT_1, CONTEXT_2);
		when(cm.getByEntryURI(CONTEXT_1)).thenAnswer(invocation -> {
			Thread.currentThread().interrupt();
			throw new RepositoryException("Interrupted context load");
		});

		try {
			assertEquals(new ReindexResult(1, 0, true), index.reindexSync(false));
			assertTrue(Thread.currentThread().isInterrupted());
		} finally {
			Thread.interrupted();
		}
		verify(cm, times(1)).getByEntryURI(CONTEXT_1);
		verify(cm, never()).getByEntryURI(CONTEXT_2);
	}

	@Test
	public void reindexSyncReportsInterruptionWhileLoadingTheLastEmptyContext() {
		ContextManager cm = contextManagerListing(CONTEXT_1);
		Context emptyContext = mock(Context.class);
		when(cm.getContext("1")).thenAnswer(invocation -> {
			Thread.currentThread().interrupt();
			return emptyContext;
		});

		try {
			assertEquals(new ReindexResult(0, 0, true), index.reindexSync(false));
			assertTrue(Thread.currentThread().isInterrupted());
		} finally {
			Thread.interrupted();
		}
	}

	@Test
	public void reindexSyncReportsInterruptionWhileListingNoContexts() {
		ContextManager cm = contextManagerListing();
		when(cm.getEntries()).thenAnswer(invocation -> {
			Thread.currentThread().interrupt();
			return new LinkedHashSet<URI>();
		});

		try {
			assertEquals(new ReindexResult(0, 0, true), index.reindexSync(false));
			assertTrue(Thread.currentThread().isInterrupted());
		} finally {
			Thread.interrupted();
		}
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
	public void reindexOfContextRemovesDocumentsOfCorruptEntriesRightAway() throws Exception {
		stopDocumentSubmitter(); // keeps removed documents in the delete queue
		ContextManager cm = contextManagerListing(CONTEXT_1);
		resolvableContext(cm, "1", ENTRY_1_1, ENTRY_1_2, ENTRY_1_3);
		when(cm.getEntry(ENTRY_1_1)).thenThrow(corruptEntryFailure(ENTRY_1_1));
		when(cm.getEntry(ENTRY_1_2)).thenThrow(new StackOverflowError());
		// cm.getEntry(ENTRY_1_3) is not stubbed and returns null, as for an entry that is still being created;
		// its document is left to the purge, which cannot remove the document that its creation posts

		index.reindexSync(CONTEXT_1, false);

		assertEquals(List.of(ENTRY_1_1, ENTRY_1_2), List.copyOf(deleteQueue()));
	}

	@Test
	public void reindexOfRepairedEntryCancelsThePendingRemovalOfItsDocument() throws Exception {
		stopDocumentSubmitter(); // keeps queued deletions and documents in their queues
		SolrSearchIndex indexWithoutDocuments = spy(index);
		doReturn(new SolrInputDocument()).when(indexWithoutDocuments).constructSolrInputDocument(any(), anyBoolean());
		ContextManager cm = contextManagerListing(CONTEXT_1);
		resolvableContext(cm, "1", ENTRY_1_1);
		Entry repairedEntry = mock(Entry.class);
		when(repairedEntry.getContext()).thenReturn(mock(Context.class));
		when(cm.getEntry(ENTRY_1_1))
				.thenThrow(corruptEntryFailure(ENTRY_1_1), corruptEntryFailure(ENTRY_1_1))
				.thenReturn(repairedEntry);

		indexWithoutDocuments.reindexSync(CONTEXT_1, false);
		indexWithoutDocuments.reindexSync(CONTEXT_1, false);
		assertEquals(List.of(ENTRY_1_1), List.copyOf(deleteQueue()));
		indexWithoutDocuments.reindexSync(CONTEXT_1, false);

		assertTrue(deleteQueue().isEmpty(),
				"A pending deletion must not remove the newer document, even if it is sent after the document");
		assertEquals(1, index.getPostQueueSize());
	}

	@Test
	public void reindexOfCorruptEntryKeepsItsQueuedDocument() throws Exception {
		stopDocumentSubmitter(); // keeps queued deletions and documents in their queues
		SolrSearchIndex indexWithoutDocuments = spy(index);
		doReturn(new SolrInputDocument()).when(indexWithoutDocuments).constructSolrInputDocument(any(), anyBoolean());
		ContextManager cm = contextManagerListing(CONTEXT_1);
		resolvableContext(cm, "1", ENTRY_1_1);
		Entry loadableEntry = mock(Entry.class);
		when(loadableEntry.getContext()).thenReturn(mock(Context.class));
		// The entry loads for one reindex while another one, which read it earlier, finds it corrupt
		when(cm.getEntry(ENTRY_1_1)).thenReturn(loadableEntry).thenThrow(corruptEntryFailure(ENTRY_1_1));

		indexWithoutDocuments.reindexSync(CONTEXT_1, false);
		indexWithoutDocuments.reindexSync(CONTEXT_1, false);

		assertTrue(deleteQueue().isEmpty(), "A queued document must not be dropped because of an earlier failure");
		assertEquals(1, index.getPostQueueSize());
	}

	@Test
	public void reindexDoesNotRestoreTheDocumentOfAnEntryDeletedConcurrently() throws Exception {
		stopDocumentSubmitter(); // keeps queued deletions and documents in their queues
		SolrSearchIndex indexWithoutDocuments = spy(index);
		doReturn(new SolrInputDocument()).when(indexWithoutDocuments).constructSolrInputDocument(any(), anyBoolean());
		ContextManager cm = contextManagerListing(CONTEXT_1);
		resolvableContext(cm, "1", ENTRY_1_1);
		AtomicBoolean deleted = new AtomicBoolean();
		Entry entry = mock(Entry.class);
		when(entry.getEntryURI()).thenReturn(ENTRY_1_1);
		when(entry.isDeleted()).thenAnswer(invocation -> deleted.get());
		AtomicReference<Thread> deletion = new AtomicReference<>();
		// Another thread deletes the entry after the reindex has checked the entry itself, and waits for the
		// monitor of the submission queue if the reindex holds it
		when(entry.getContext()).thenAnswer(invocation -> {
			deletion.set(Thread.ofPlatform().start(() -> {
				deleted.set(true);
				index.removeEntry(entry);
			}));
			awaitFinishedOrBlocked(deletion.get());
			return mock(Context.class);
		});
		when(cm.getEntry(ENTRY_1_1)).thenReturn(entry);

		indexWithoutDocuments.reindexSync(CONTEXT_1, false);
		deletion.get().join(TimeUnit.SECONDS.toMillis(10));

		assertEquals(List.of(ENTRY_1_1), List.copyOf(deleteQueue()),
				"The deletion must be queued after the document, or the document must not be queued");
		assertEquals(0, index.getPostQueueSize());
	}

	/**
	 * Waits until the thread has finished or is blocked on a monitor.
	 */
	private static void awaitFinishedOrBlocked(Thread thread) {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
		while (thread.isAlive() && thread.getState() != Thread.State.BLOCKED) {
			if (System.nanoTime() > deadline) {
				throw new AssertionError("Thread neither finished nor blocked");
			}
			Thread.yield();
		}
	}

	@Test
	public void requeuedFailedDeletionsAreDrainedAfterTheDeletionsQueuedBeforeThem() throws Exception {
		stopDocumentSubmitter(); // keeps queued deletions in their queue
		List<URI> queued = new ArrayList<>();
		for (int i = 1; i <= 150; i++) {
			queued.add(URI.create("http://localhost:8181/1/entry/" + i));
		}
		deleteQueue().addAll(queued);
		Method drainDeleteQueue = SolrSearchIndex.SolrInputDocumentSubmitter.class
				.getDeclaredMethod("drainDeleteQueue");
		drainDeleteQueue.setAccessible(true);
		Method requeueDeletes = SolrSearchIndex.SolrInputDocumentSubmitter.class
				.getDeclaredMethod("requeueDeletes", List.class);
		requeueDeletes.setAccessible(true);

		List<?> failedBatch = (List<?>) drainDeleteQueue.invoke(documentSubmitter());
		requeueDeletes.invoke(documentSubmitter(), failedBatch);
		List<?> nextBatch = (List<?>) drainDeleteQueue.invoke(documentSubmitter());

		assertEquals(queued.subList(0, 100), failedBatch);
		// A failed batch must not overtake the deletions that have not been sent yet
		assertEquals(queued.subList(100, 150), nextBatch.subList(0, 50));
		assertEquals(queued.subList(0, 50), nextBatch.subList(50, 100));
	}

	@Test
	public void failedDeletionIsNotRequeuedWhenADocumentOfTheEntryWasQueuedMeanwhile() throws Exception {
		stopDocumentSubmitter(); // keeps queued deletions and documents in their queues
		postQueue().put(ENTRY_1_1, new SolrInputDocument()); // queued while the deletion batch was being sent
		Method requeueDeletes = SolrSearchIndex.SolrInputDocumentSubmitter.class
				.getDeclaredMethod("requeueDeletes", List.class);
		requeueDeletes.setAccessible(true);

		requeueDeletes.invoke(documentSubmitter(), List.of(ENTRY_1_1, ENTRY_1_2));

		assertEquals(List.of(ENTRY_1_2), List.copyOf(deleteQueue()));
	}

	@Test
	public void reindexOfContextKeepsIndexedDocumentsWhenLoadingAnEntryFailsForAnotherReason() throws Exception {
		stopDocumentSubmitter(); // keeps removed documents in the delete queue
		ContextManager cm = contextManagerListing(CONTEXT_1);
		resolvableContext(cm, "1", ENTRY_1_1, deletedEntry(cm, ENTRY_1_2));
		// A failure of the store, which may be transient, is not a reason to remove the entry's document
		when(cm.getEntry(ENTRY_1_1)).thenThrow(new RepositoryException("Failed to connect to Repository"));
		stubContextEntry(cm, CONTEXT_1, "http://localhost:8181/1");

		ReindexResult result = index.reindexSync(false);
		index.shutdown(); // waits for a delayed purge, if one was scheduled

		assertEquals(new ReindexResult(0, 1, false), result);
		assertTrue(deleteQueue().isEmpty(), "The document of an entry that may still be loadable must be kept");
		verify(solrServer, never()).request(any(), any());
	}

	@Test
	public void reindexOfContextPurgesExpiredDocumentsWhenAnEntryIsCorrupt() throws Exception {
		stopDocumentSubmitter(); // only the purge reaches Solr
		ContextManager cm = contextManagerListing(CONTEXT_1);
		resolvableContext(cm, "1", ENTRY_1_1, deletedEntry(cm, ENTRY_1_2));
		when(cm.getEntry(ENTRY_1_1)).thenThrow(corruptEntryFailure(ENTRY_1_1));
		stubContextEntry(cm, CONTEXT_1, "http://localhost:8181/1");

		index.reindexSync(CONTEXT_1, false);
		index.shutdown(); // waits for the delayed purge

		assertTrue(sentDeleteQuery().startsWith("indexedAt:[* TO "));
	}

	@Test
	public void reindexOfContextPurgesExpiredDocumentsWhenAnEntryCannotBeFound() throws Exception {
		stopDocumentSubmitter(); // only the purge reaches Solr
		ContextManager cm = contextManagerListing(CONTEXT_1);
		resolvableContext(cm, "1", ENTRY_1_1, deletedEntry(cm, ENTRY_1_2));
		// cm.getEntry(ENTRY_1_1) is not stubbed and returns null, as for an entry whose graph is missing
		stubContextEntry(cm, CONTEXT_1, "http://localhost:8181/1");

		index.reindexSync(CONTEXT_1, false);
		index.shutdown(); // waits for the delayed purge

		assertTrue(deleteQueue().isEmpty(), "The document of an entry that cannot be found is left to the purge");
		assertTrue(sentDeleteQuery().startsWith("indexedAt:[* TO "));
	}

	@Test
	public void reindexOfContextKeepsIndexedDocumentsWhenAnEntryCannotBeIndexed() throws Exception {
		stopDocumentSubmitter();
		SolrSearchIndex indexFailingDocuments = spy(index);
		doThrow(new IllegalStateException("Unable to extract fulltext"))
				.when(indexFailingDocuments).constructSolrInputDocument(any(), anyBoolean());
		ContextManager cm = contextManagerListing(CONTEXT_1);
		resolvableContext(cm, "1", indexableEntry(cm, ENTRY_1_1), deletedEntry(cm, ENTRY_1_2));
		stubContextEntry(cm, CONTEXT_1, "http://localhost:8181/1");

		ReindexResult result = indexFailingDocuments.reindexSync(false);
		index.shutdown(); // waits for a delayed purge, if one was scheduled

		assertEquals(new ReindexResult(0, 1, false), result);
		assertTrue(deleteQueue().isEmpty(), "The previous document of an entry that loads must be kept");
		verify(solrServer, never()).request(any(), any());
	}

	@Test
	public void reindexOfContextKeepsIndexedDocumentsWhenContextEntryCannotBeLoaded() throws Exception {
		ContextManager cm = contextManagerListing(CONTEXT_1);
		resolvableContext(cm, "1", deletedEntry(cm, ENTRY_1_1));
		// cm.getByEntryURI(CONTEXT_1) is not stubbed and returns null, as for a corrupt context entry

		index.reindexSync(CONTEXT_1, false);
		index.shutdown(); // waits for a delayed purge, if one was scheduled

		verify(solrServer, never()).request(any(), any());
	}

	@Test
	public void reindexOfEmptyContextDoesNotPurge() throws Exception {
		ContextManager cm = contextManagerListing(CONTEXT_1);
		resolvableContext(cm, "1");
		stubContextEntry(cm, CONTEXT_1, "http://localhost:8181/1");

		index.reindexSync(CONTEXT_1, false);
		index.shutdown(); // waits for a delayed purge, if one was scheduled

		verify(solrServer, never()).request(any(), any());
	}

	@Test
	public void reindexOfContextPurgesOnlyAfterTheLastQueuedEntryHasLeftTheQueue() throws Exception {
		stopDocumentSubmitter(); // keeps posted documents in the submission queue
		SolrSearchIndex indexWithoutDocuments = spy(index);
		doReturn(new SolrInputDocument()).when(indexWithoutDocuments).constructSolrInputDocument(any(), anyBoolean());
		ContextManager cm = contextManagerListing(CONTEXT_1);
		// The deleted entry comes last, so the purge must wait for the entry before it, not for the last entry.
		// The corrupt entry does not prevent the purge, and its document is removed right away.
		resolvableContext(cm, "1", indexableEntry(cm, ENTRY_1_1), ENTRY_1_3, deletedEntry(cm, ENTRY_1_2));
		when(cm.getEntry(ENTRY_1_3)).thenThrow(corruptEntryFailure(ENTRY_1_3));
		stubContextEntry(cm, CONTEXT_1, "http://localhost:8181/1");

		indexWithoutDocuments.reindexSync(CONTEXT_1, false);

		assertEquals(1, index.getPostQueueSize());
		assertEquals(List.of(ENTRY_1_3), List.copyOf(deleteQueue()));
		verify(solrServer, after(1000).never()).request(any(), any());
		postQueue().invalidate(ENTRY_1_1); // as the document submitter does when it takes the document
		// The purge checks the queue every 5 seconds
		verify(solrServer, timeout(10_000)).request(any(), any());
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

		assertFalse(index.isIndexing(CONTEXT_1),
				"A completed reindex must clear its mapping even if cleanup starts before submit returns");
	}

	@Test
	public void asyncReindexClearsIndexingStateWhenContextFails() throws Exception {
		ContextManager cm = contextManagerListing(CONTEXT_1);
		when(cm.getByEntryURI(CONTEXT_1)).thenThrow(new RepositoryException("Unable to load entry " + CONTEXT_1));

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

		assertTrue(index.isIndexing(CONTEXT_1),
				"The cancelled task must not remove the mapping of its running replacement");
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

	/**
	 * Stubs an entry that loads and is posted to the submission queue.
	 */
	private static URI indexableEntry(ContextManager cm, URI entryURI) {
		Entry entry = mock(Entry.class);
		when(entry.getContext()).thenReturn(mock(Context.class));
		when(cm.getEntry(entryURI)).thenReturn(entry);
		return entryURI;
	}

	private SolrSearchIndex.SolrInputDocumentSubmitter documentSubmitter() throws Exception {
		Field f = SolrSearchIndex.class.getDeclaredField("documentSubmitter");
		f.setAccessible(true);
		return (SolrSearchIndex.SolrInputDocumentSubmitter) f.get(index);
	}

	private void stopDocumentSubmitter() throws Exception {
		Thread documentSubmitter = documentSubmitter();
		documentSubmitter.interrupt();
		documentSubmitter.join(TimeUnit.SECONDS.toMillis(10));
		assertFalse(documentSubmitter.isAlive());
	}

	@SuppressWarnings("unchecked")
	private Cache<URI, SolrInputDocument> postQueue() throws Exception {
		Field f = SolrSearchIndex.class.getDeclaredField("postQueue");
		f.setAccessible(true);
		return (Cache<URI, SolrInputDocument>) f.get(index);
	}

	@SuppressWarnings("unchecked")
	private Set<URI> deleteQueue() throws Exception {
		Field f = SolrSearchIndex.class.getDeclaredField("deleteQueue");
		f.setAccessible(true);
		return (Set<URI>) f.get(index);
	}

	/**
	 * Returns the delete query of the only request sent to Solr.
	 */
	private String sentDeleteQuery() throws Exception {
		ArgumentCaptor<UpdateRequest> captor = ArgumentCaptor.forClass(UpdateRequest.class);
		verify(solrServer).request(captor.capture(), any());
		return captor.getValue().getDeleteQuery().getFirst();
	}

	/**
	 * Returns the exception that loading an entry with corrupt data throws: the context wraps the cause.
	 */
	private static RepositoryException corruptEntryFailure(URI entryURI) {
		return new RepositoryException("Unable to load entry " + entryURI,
				new CorruptEntryException(entryURI, "Entry graph <" + entryURI + "> is corrupt"));
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

	@Test
	public void sendQuerySkipsHitWhoseEntryIsCorrupt() throws Exception {
		ContextManager cm = contextManagerListing();
		when(cm.getEntry(ENTRY_1_1)).thenThrow(corruptEntryFailure(ENTRY_1_1));
		Entry readableEntry = mock(Entry.class);
		when(readableEntry.getEntryType()).thenReturn(EntryType.Local);
		when(readableEntry.getRepositoryManager()).thenReturn(rm);
		when(cm.getEntry(ENTRY_1_2)).thenReturn(readableEntry);
		solrReturnsHits(ENTRY_1_1, ENTRY_1_2);

		QueryResult result = index.sendQuery(new SolrQuery("*:*").setStart(0).setRows(10));

		assertEquals(Set.of(readableEntry), result.getEntries());
		assertEquals(1, result.getHits());
	}

	@Test
	public void sendQuerySkipsHitWhoseEntryOverflowsTheStack() throws Exception {
		ContextManager cm = contextManagerListing();
		when(cm.getEntry(ENTRY_1_1)).thenThrow(new StackOverflowError());
		solrReturnsHits(ENTRY_1_1);

		QueryResult result = index.sendQuery(new SolrQuery("*:*").setStart(0).setRows(10));

		assertTrue(result.getEntries().isEmpty());
		assertEquals(0, result.getHits());
	}

	@Test
	public void sendQueryKeepsReferenceHitWhoseReferencedEntryCannotBeResolved() throws Exception {
		ContextManager cm = contextManagerListing();
		URI baseURI = URI.create("http://localhost:8181/");
		Entry reference = mock(Entry.class);
		when(reference.getEntryType()).thenReturn(EntryType.LinkReference);
		when(reference.getRepositoryManager()).thenReturn(rm);
		when(reference.getCachedExternalMetadata()).thenReturn(mock(LocalMetadataWrapper.class));
		when(reference.getExternalMetadataURI()).thenReturn(baseURI);
		when(cm.getEntry(ENTRY_1_1)).thenReturn(reference);
		// As ContextManager.getEntry answers for a URI that does not denote an entry, e.g. the base URL stored
		// before such external metadata URIs were rejected
		when(cm.getEntry(baseURI)).thenReturn(null);
		solrReturnsHits(ENTRY_1_1);

		QueryResult result = index.sendQuery(new SolrQuery("*:*").setStart(0).setRows(10));

		assertEquals(Set.of(reference), result.getEntries());
		assertEquals(1, result.getHits());
	}

	@Test
	public void sendQueryFailsWhenLoadingAHitFailsForAnotherReason() throws Exception {
		ContextManager cm = contextManagerListing();
		// A failure of the store must not turn into an empty search result
		when(cm.getEntry(ENTRY_1_1)).thenThrow(new RepositoryException("Failed to connect to Repository"));
		solrReturnsHits(ENTRY_1_1);

		assertThrows(RepositoryException.class,
				() -> index.sendQuery(new SolrQuery("*:*").setStart(0).setRows(10)));
	}

	@Test
	public void sendQuerySkipsHitWhoseContextEntryIsCorrupt() throws Exception {
		ContextManager cm = contextManagerListing();
		when(cm.getEntry(ENTRY_1_1)).thenThrow(corruptEntryFailure(CONTEXT_1));
		solrReturnsHits(ENTRY_1_1);

		QueryResult result = index.sendQuery(new SolrQuery("*:*").setStart(0).setRows(10));

		assertTrue(result.getEntries().isEmpty());
		assertEquals(0, result.getHits());
	}

	@Test
	public void sendQueryDoesNotRemoveTheDocumentOfACorruptHit() throws Exception {
		stopDocumentSubmitter(); // keeps queued deletions in their queue
		ContextManager cm = contextManagerListing();
		when(cm.getEntry(ENTRY_1_1)).thenThrow(corruptEntryFailure(ENTRY_1_1));
		solrReturnsHits(ENTRY_1_1);

		index.sendQuery(new SolrQuery("*:*").setStart(0).setRows(10));

		assertTrue(deleteQueue().isEmpty(), "A search must leave the removal of documents to the reindex");
	}

	@Test
	public void sendQueryFailsWhenLoadingAHitFailsBecauseAnotherEntryIsCorrupt() throws Exception {
		ContextManager cm = contextManagerListing();
		// As when the access check on a cached hit loads a corrupt principal
		when(cm.getEntry(ENTRY_1_1)).thenThrow(corruptEntryFailure(PRINCIPAL_ENTRY));
		solrReturnsHits(ENTRY_1_1);

		assertThrows(RepositoryException.class,
				() -> index.sendQuery(new SolrQuery("*:*").setStart(0).setRows(10)));
	}

	@Test
	public void sendQueryFailsWhenTheAccessCheckFailsBecauseOfACorruptPrincipal() throws Exception {
		ContextManager cm = contextManagerListing();
		Entry entry = mock(Entry.class);
		when(entry.getEntryType()).thenReturn(EntryType.Local);
		when(entry.getRepositoryManager()).thenReturn(rm);
		when(cm.getEntry(ENTRY_1_1)).thenReturn(entry);
		PrincipalManager pm = rm.getPrincipalManager();
		doThrow(corruptEntryFailure(PRINCIPAL_ENTRY)).when(pm)
				.checkAuthenticatedUserAuthorized(entry, AccessProperty.ReadMetadata);
		solrReturnsHits(ENTRY_1_1);

		assertThrows(RepositoryException.class,
				() -> index.sendQuery(new SolrQuery("*:*").setStart(0).setRows(10)));
	}

	@Test
	public void sendQuerySkipsReferenceHitWhoseReferencedEntryIsCorrupt() throws Exception {
		ContextManager cm = contextManagerListing();
		referenceTo(cm, ENTRY_1_1, METADATA_2_1, ENTRY_2_1);
		// The access to the referenced metadata, which the indexed document may contain, cannot be checked
		when(cm.getEntry(METADATA_2_1)).thenThrow(corruptEntryFailure(ENTRY_2_1));
		solrReturnsHits(ENTRY_1_1);

		QueryResult result = index.sendQuery(new SolrQuery("*:*").setStart(0).setRows(10));

		assertTrue(result.getEntries().isEmpty());
		assertEquals(0, result.getHits());
	}

	@Test
	public void sendQuerySkipsReferenceHitWhoseReferencedEntrysContextEntryIsCorrupt() throws Exception {
		ContextManager cm = contextManagerListing();
		referenceTo(cm, ENTRY_1_1, METADATA_2_1, ENTRY_2_1);
		when(cm.getEntry(METADATA_2_1)).thenThrow(corruptEntryFailure(CONTEXT_2));
		solrReturnsHits(ENTRY_1_1);

		QueryResult result = index.sendQuery(new SolrQuery("*:*").setStart(0).setRows(10));

		assertTrue(result.getEntries().isEmpty());
		assertEquals(0, result.getHits());
	}

	@Test
	public void sendQueryFailsWhenLoadingTheReferencedEntryFailsBecauseAnotherEntryIsCorrupt() throws Exception {
		ContextManager cm = contextManagerListing();
		referenceTo(cm, ENTRY_1_1, METADATA_2_1, ENTRY_2_1);
		// As when the access check on the cached referenced entry loads a corrupt principal
		when(cm.getEntry(METADATA_2_1)).thenThrow(corruptEntryFailure(PRINCIPAL_ENTRY));
		solrReturnsHits(ENTRY_1_1);

		assertThrows(RepositoryException.class,
				() -> index.sendQuery(new SolrQuery("*:*").setStart(0).setRows(10)));
	}

	/**
	 * Stubs a LinkReference that the given hit loads as, whose external metadata is the local metadata of the given
	 * referenced entry.
	 */
	private Entry referenceTo(ContextManager cm, URI hitURI, URI externalMetadataURI, URI referencedEntryURI) {
		LocalMetadataWrapper wrapper = mock(LocalMetadataWrapper.class);
		when(wrapper.getReferencedEntryURI()).thenReturn(referencedEntryURI);
		Entry reference = mock(Entry.class);
		when(reference.getEntryType()).thenReturn(EntryType.LinkReference);
		when(reference.getRepositoryManager()).thenReturn(rm);
		when(reference.getCachedExternalMetadata()).thenReturn(wrapper);
		when(reference.getExternalMetadataURI()).thenReturn(externalMetadataURI);
		when(cm.getEntry(hitURI)).thenReturn(reference);
		return reference;
	}

	@Test
	public void failureKindIsTheSameForTheSameFailureOfDifferentEntries() {
		assertEquals(SolrSearchIndex.failureKind("Unable to load entry", corruptEntryFailure(ENTRY_1_1)),
				SolrSearchIndex.failureKind("Unable to load entry", corruptEntryFailure(ENTRY_1_2)));
	}

	@Test
	public void failureKindDiffersForADifferentCause() {
		assertNotEquals(SolrSearchIndex.failureKind("Unable to load entry", corruptEntryFailure(ENTRY_1_1)),
				SolrSearchIndex.failureKind("Unable to load entry", new RepositoryException("Failed to connect")));
	}

	@Test
	public void failureKindDiffersForADifferentMessage() {
		assertNotEquals(SolrSearchIndex.failureKind("Unable to load entry", corruptEntryFailure(ENTRY_1_1)),
				SolrSearchIndex.failureKind("Not indexing entry", corruptEntryFailure(ENTRY_1_1)));
	}

	private void solrReturnsHits(URI... entryURIs) throws Exception {
		SolrDocumentList docs = new SolrDocumentList();
		for (URI entryURI : entryURIs) {
			SolrDocument doc = new SolrDocument();
			doc.setField("uri", entryURI.toString());
			docs.add(doc);
		}
		docs.setNumFound(entryURIs.length);
		QueryResponse response = mock(QueryResponse.class);
		when(response.getResults()).thenReturn(docs);
		when(solrServer.query(any(SolrParams.class))).thenReturn(response);
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
