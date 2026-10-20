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

import static org.awaitility.Awaitility.await;
import static org.eclipse.rdf4j.model.util.Values.iri;
import static org.eclipse.rdf4j.model.util.Values.literal;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.time.Duration;
import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.LogEvent;
import org.awaitility.core.ConditionFactory;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.RepositoryException;
import org.entrystore.Context;
import org.entrystore.Entry;
import org.entrystore.GraphType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

public class PublicRepositoryTest extends AbstractCoreTest {

	/** A valid RDF4J IRI that {@code java.net.URI.create} rejects, so {@code loadIndex} cannot index it. */
	private static final String UNPARSEABLE_IRI = "http://example.com/not a uri";

	private static final IRI TITLE = iri("http://purl.org/dc/terms/title");

	private static final Duration TIMEOUT = Duration.ofSeconds(10);

	/**
	 * ENTRYSTORE-1095. {@code rebuildRepository} clears the whole public repository and then re-adds only
	 * what it enumerates, so a listing short by one unindexable statement drops those entries from the
	 * public repository until the data is repaired <em>and</em> the process restarts —
	 * {@code rebuildRepository} has one call site, at construction.
	 *
	 * <p>Refusing to rebuild such a context, which an earlier revision did, is strictly worse than a
	 * short listing: {@code rc.clear()} has already run by then, so it drops every public entry of the
	 * context rather than the one the listing misses. Enumerating from the store removes the choice.
	 *
	 * <p>The context is re-resolved from a cold cache before the rebuild, because that is the only state
	 * in which the index genuinely cannot see the child — the instance that created it replays its own
	 * buffered index write.
	 */
	@Test
	public void rebuildRepository_publishesEntriesTheIndexCannotSee() {
		rm.setCheckForAuthorization(false);
		Entry contextEntry = cm.createResource(null, GraphType.Context, null, null);
		ContextImpl context = (ContextImpl) contextEntry.getResource();

		EntryImpl visible = (EntryImpl) context.createLink(null, URI.create("http://example.com/visible"), null);
		EntryImpl hidden = (EntryImpl) context.createLink(null, URI.create("http://example.com/hidden"), null);
		addTitle(visible, "visible");
		addTitle(hidden, "hidden");
		hideFromIndex(context, hidden);

		((ContextManagerImpl) cm).softCache.remove(contextEntry);
		ContextImpl reloaded = (ContextImpl) cm.getContext(context.id);
		assertFalse(reloaded.getEntries().contains(hidden.getEntryURI()),
			"the index must be short for this test to mean anything");
		assertFalse(reloaded.isIndexComplete(), "and the context must know that it is");

		// Rebuilds in its constructor, because the store starts empty.
		PublicRepository publicRepository = new PublicRepository(rm);
		try (RepositoryConnection rc = publicRepository.getConnection()) {
			assertTrue(rc.hasStatement(null, TITLE, rc.getValueFactory().createLiteral("visible"), false),
				"skipping a context with a short index drops every one of its public entries, not the "
					+ "one the listing missed — the clear has already happened by then");
			assertTrue(rc.hasStatement(null, TITLE, rc.getValueFactory().createLiteral("hidden"), false),
				"and the entry the index could not see must reach the public repository too, since the "
					+ "enumeration no longer depends on the index");
		} finally {
			publicRepository.shutdown();
		}
	}

	private void addTitle(EntryImpl entry, String title) {
		try (RepositoryConnection rc = rm.getRepository().getConnection()) {
			ValueFactory vf = rc.getValueFactory();
			rc.add(vf.createIRI(entry.getResourceURI().toString()), TITLE, vf.createLiteral(title),
				entry.getSesameLocalMetadataURI());
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

	@ParameterizedTest(name = "{0} consecutive failures back off {1} ms")
	@CsvSource({
		"1, 1000",
		"2, 2000",
		"3, 4000",
		"6, 32000",
		"7, 60000",
		"8, 60000",
		"2147483647, 60000"
	})
	public void backoffMillis_doublesFromOneSecondAndCapsAtSixtySeconds(int consecutiveFailures, long expectedMillis) {
		assertEquals(expectedMillis, PublicRepository.backoffMillis(consecutiveFailures));
	}

	/** Each test gets a fresh submitter, so that it starts without failures. */
	@Nested
	class Submitter {

		private LogCapture logs;

		private PublicRepository publicRepository;

		@BeforeEach
		public void startSubmitter() {
			logs = new LogCapture(PublicRepository.class, Level.INFO);
			publicRepository = new PublicRepository(rm);
		}

		@AfterEach
		public void stopSubmitter() {
			publicRepository.shutdown();
			logs.close();
		}

		@Test
		public void failedBatch_isLoggedWithConsecutiveFailureCountAndBackoff() {
			publicRepository.enqueue(entryFailingUpdate());

			LogEvent failure = awaitEvent(Level.ERROR, "consecutive failures: 1,");
			assertTrue(messageOf(failure).contains("backing off 1000 ms"), messageOf(failure));
			assertInstanceOf(IllegalStateException.class, failure.getThrown(),
				"the failure must be logged with its stack trace, not only counted");
		}

		/**
		 * Under steady writes, a backoff that new work could end would end every time: the escalation would never
		 * take effect and each new entry would be sent into the failing store.
		 */
		@Test
		public void enqueueDuringBackoff_doesNotEndIt() {
			Entry queuedDuringBackoff = publishableEntry("queued during backoff");
			publicRepository.enqueue(entryFailingUpdate());
			LogEvent failure = awaitEvent(Level.ERROR, "consecutive failures: 1,");
			// enqueue inside the backoff, so that a backoff waiting on queueSignal would receive this notify
			awaiting().until(this::isBackingOff);

			publicRepository.enqueue(queuedDuringBackoff);

			LogEvent recovery = awaitEvent(Level.INFO, "recovered after 1 consecutive failures");
			// 100 ms below the 1 s backoff, as slack for the wall clock the event timestamps come from
			assertTrue(recovery.getTimeMillis() - failure.getTimeMillis() >= 900,
				"the entry must wait out the backoff, not end it");
			assertTrue(hasTitle("queued during backoff"), "and it must be sent once the backoff is over, not dropped");
		}

		@Test
		public void successAfterFailure_logsRecoveryAndResetsCount() {
			Entry published = publishableEntry("sent after a failure");
			publicRepository.enqueue(entryFailingUpdate());
			awaitEvent(Level.ERROR, "consecutive failures: 1,");
			publicRepository.enqueue(published);
			awaitEvent(Level.INFO, "recovered after 1 consecutive failures");

			publicRepository.enqueue(entryFailingUpdate());

			awaiting().until(() -> logs.count(Level.ERROR, "consecutive failures: ") == 2);
			assertEquals(2, logs.count(Level.ERROR, "consecutive failures: 1, failing for 0 s"),
				"a failure after a sent batch must start a new run, counted and timed from that failure");
		}

		/** The idle wait returns without having sent anything, so it proves nothing about the store. */
		@Test
		public void idleIterationAfterFailure_doesNotResetCount() {
			publicRepository.enqueue(entryFailingUpdate());
			awaitEvent(Level.ERROR, "consecutive failures: 1,");
			awaiting().until(this::isWaitingForWork);

			publicRepository.enqueue(entryFailingUpdate());

			awaiting().until(() -> logs.count(Level.ERROR, "consecutive failures: ") == 2);
			Optional<LogEvent> second = logs.first(Level.ERROR, "consecutive failures: 2,");
			assertTrue(second.isPresent(), "an iteration that only waited for work must not end the run of failures");
			assertTrue(messageOf(second.get()).contains("backing off 2000 ms"), messageOf(second.get()));
			assertEquals(0, logs.count(Level.INFO, "recovered after"));
		}

		@Test
		public void interruptDuringBackoff_stopsSubmitter() throws InterruptedException {
			publicRepository.enqueue(entryFailingUpdate());
			awaitEvent(Level.ERROR, "consecutive failures: 1,");

			publicRepository.shutdown();

			awaitEvent(Level.INFO, "interrupted during failure backoff");
			assertTrue(publicRepository.entrySubmitter.join(TIMEOUT),
				"the submitter thread must end instead of continuing after the backoff");
		}

		/** A store outage must be counted and backed off like any other failure. */
		@Test
		public void storeFailureInUpdateBatch_countsAsSubmitterFailure() {
			publicRepository.enqueue(entryFailingUpdate(new RepositoryException("simulated store failure")));

			awaitEvent(Level.ERROR, "updateEntries failed; dropped 1 entries");
			LogEvent failure = awaitEvent(Level.ERROR, "consecutive failures: 1,");
			assertInstanceOf(RepositoryException.class, failure.getThrown());
		}

		@Test
		public void storeFailureInRemoveBatch_countsAsSubmitterFailure() {
			publicRepository.remove(entryFailingRemoval());

			awaitEvent(Level.ERROR, "removeEntries failed; dropped 1 entries");
			LogEvent failure = awaitEvent(Level.ERROR, "consecutive failures: 1,");
			assertInstanceOf(RepositoryException.class, failure.getThrown());
		}

		/** The failure case cannot tell a removal from an update, which also removes the entry before re-adding it. */
		@Test
		public void removedEntry_isGoneFromPublicRepository() {
			Entry published = publishableEntry("removed");
			publicRepository.enqueue(published);
			awaiting().until(() -> hasTitle("removed"));

			publicRepository.remove(published);

			awaiting().until(() -> !hasTitle("removed"));
		}

		/** An entry that always fails must not take the entries queued beside it during the backoff with it. */
		@Test
		public void failingEntryInBatch_dropsOnlyItself() {
			Entry published = publishableEntry("sent beside a failing entry");
			publicRepository.enqueue(entryFailingUpdate());
			awaitEvent(Level.ERROR, "consecutive failures: 1,");
			Entry failing = entryFailingUpdate();

			publicRepository.enqueue(failing);
			publicRepository.enqueue(published);

			awaitEvent(Level.INFO, "recovered after 1 consecutive failures");
			assertTrue(hasTitle("sent beside a failing entry"), "an entry batched with a failing one must be written");
			assertEquals(1, logs.count(Level.ERROR, "dropped entry " + failing.getEntryURI()),
				"the failing entry must be logged as dropped on its own");
			assertEquals(0, logs.count(Level.ERROR, "consecutive failures: 2,"),
				"a batch that was partly written is not a failure of the store");
		}

		/** A batch of which nothing can be written fails as a whole, and is not logged entry by entry. */
		@Test
		public void batchWhoseEntriesAllFail_countsAsFailure() {
			publicRepository.enqueue(entryFailingUpdate());
			awaitEvent(Level.ERROR, "consecutive failures: 1,");

			publicRepository.enqueue(entryFailingUpdate());
			publicRepository.enqueue(entryFailingUpdate());

			LogEvent second = awaitEvent(Level.ERROR, "consecutive failures: 2,");
			assertTrue(messageOf(second).contains("backing off 2000 ms"), messageOf(second));
			assertEquals(1, logs.count(Level.ERROR, "updateEntries failed; dropped 2 entries"));
			assertEquals(0, logs.count(Level.ERROR, "dropped entry"));
		}

		/** An Error is not isolated like a RuntimeException, so it must reach the log before the thread ends. */
		@Test
		public void errorInBatch_isLoggedAndStopsSubmitter() throws InterruptedException {
			publicRepository.enqueue(entryFailingUpdate(new StackOverflowError("simulated")));

			// the submitter's Error is expected; by default Awaitility rethrows it here
			LogEvent stopped = awaiting().dontCatchUncaughtExceptions()
				.until(() -> logs.first(Level.ERROR, "submitter stopped"), Optional::isPresent)
				.orElseThrow();
			assertInstanceOf(StackOverflowError.class, stopped.getThrown());
			assertTrue(publicRepository.entrySubmitter.join(TIMEOUT), "an Error must end the submitter thread");
		}

		/** Guest cannot read the entry, which is what keeps it out of the public repository: the normal case. */
		@Test
		public void privateEntry_isLeftOutWithoutWarning() {
			pm.setAuthenticatedUserURI(pm.getAdminUser().getURI());
			Context context = (Context) cm.createResource(null, GraphType.Context, null, null).getResource();
			URI resource = URI.create("http://example.com/private");
			EntryImpl privateEntry = (EntryImpl) context.createLink(null, resource, null);
			addTitle(privateEntry, "private");

			publicRepository.enqueue(privateEntry);

			awaitEvent(Level.INFO, "Sending 1 entries for update");
			awaiting().until(this::isWaitingForWork);
			assertFalse(hasTitle("private"), "an entry guest cannot read must not reach the public repository");
			assertEquals(0, logs.count(Level.WARN, ""), "leaving out a non-public entry is not a warning");
		}

		private LogEvent awaitEvent(Level level, String needle) {
			return awaiting().until(() -> logs.first(level, needle), Optional::isPresent).orElseThrow();
		}

		private boolean hasTitle(String title) {
			try (RepositoryConnection rc = publicRepository.getConnection()) {
				return rc.hasStatement(null, TITLE, literal(title), false);
			}
		}

		/** The backoff sleeps in {@code run()}, so the only timed wait inside {@code runOnce()} is the idle one. */
		private boolean isWaitingForWork() {
			Thread submitter = publicRepository.entrySubmitter;
			return submitter.getState() == Thread.State.TIMED_WAITING
				&& Arrays.stream(submitter.getStackTrace()).anyMatch(frame -> frame.getMethodName().equals("runOnce"));
		}

		/** The only timed wait outside {@code runOnce()} is the failure backoff, whether it sleeps or waits. */
		private boolean isBackingOff() {
			Thread submitter = publicRepository.entrySubmitter;
			return submitter.getState() == Thread.State.TIMED_WAITING
				&& Arrays.stream(submitter.getStackTrace()).noneMatch(frame -> frame.getMethodName().equals("runOnce"));
		}
	}

	/** Polls from the start and often, as most conditions hold within milliseconds. */
	private static ConditionFactory awaiting() {
		return await().pollDelay(Duration.ZERO).pollInterval(Duration.ofMillis(10)).atMost(TIMEOUT);
	}

	private static String messageOf(LogEvent event) {
		return event.getMessage().getFormattedMessage();
	}

	private static Entry entryFailingUpdate() {
		return entryFailingUpdate(new IllegalStateException("simulated failure"));
	}

	/** An entry whose update fails with {@code failure} when the submitter reads its graph type. */
	private static Entry entryFailingUpdate(Throwable failure) {
		Entry entry = mock(Entry.class);
		when(entry.getEntryURI()).thenReturn(URI.create("http://example.com/failing/" + UUID.randomUUID()));
		when(entry.getGraphType()).thenThrow(failure);
		return entry;
	}

	/** An entry whose removal throws a store exception inside the store transaction. */
	private static Entry entryFailingRemoval() {
		Entry entry = mock(Entry.class);
		when(entry.getEntryURI()).thenReturn(URI.create("http://example.com/failing/" + UUID.randomUUID()));
		when(entry.getRepositoryManager()).thenThrow(new RepositoryException("simulated store failure"));
		return entry;
	}

	/**
	 * A link with the given title in a new context. The submitter reads entries as guest, so authorization checks
	 * are turned off for it to publish the title.
	 */
	private EntryImpl publishableEntry(String title) {
		rm.setCheckForAuthorization(false);
		Context context = (Context) cm.createResource(null, GraphType.Context, null, null).getResource();
		URI resource = URI.create("http://example.com/" + UUID.randomUUID());
		EntryImpl link = (EntryImpl) context.createLink(null, resource, null);
		addTitle(link, title);
		return link;
	}

	@Disabled("To be implemented")
	@Test
	public void testGetConnection() throws Exception {
		// TODO
	}

	@Disabled("To be implemented")
	@Test
	public void testAddEntry() throws Exception {
		// TODO
	}

	@Disabled("To be implemented")
	@Test
	public void testUpdateEntry() throws Exception {
		// TODO
	}

	@Disabled("To be implemented")
	@Test
	public void testRemoveEntry() throws Exception {
		// TODO
	}

	@Disabled("To be implemented")
	@Test
	public void testGetTripleCount() throws Exception {
		// TODO
	}

	@Disabled("To be implemented")
	@Test
	public void testShutdown() throws Exception {
		// TODO
	}
}
