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
import static org.eclipse.rdf4j.model.util.Statements.statement;
import static org.eclipse.rdf4j.model.util.Values.iri;
import static org.eclipse.rdf4j.model.util.Values.literal;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.LogEvent;
import org.awaitility.core.ConditionFactory;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.RepositoryException;
import org.entrystore.Context;
import org.entrystore.Entry;
import org.entrystore.GraphType;
import org.entrystore.PrincipalManager.AccessProperty;
import org.entrystore.repository.config.Settings;
import org.entrystore.repository.util.URISplit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

public class PublicRepositoryTest extends AbstractCoreTest {

	/** A valid RDF4J IRI that {@code java.net.URI.create} rejects, so {@code loadIndex} cannot index it. */
	private static final String UNPARSEABLE_IRI = "http://example.com/not a uri";

	private static final IRI TITLE = iri("http://purl.org/dc/terms/title");

	private static final Duration TIMEOUT = Duration.ofSeconds(10);

	/** Every entry states its titles about the same subject, so equal titles are equal statements. */
	private static final IRI SUBJECT = iri("http://example.com/shared-subject");

	private static final String SHARED_TITLE = "shared";

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

	/**
	 * ENTRYSTORE-1189. The rebuild runs at construction, where no user is set: it runs as guest, since checking each
	 * entry it loads as nobody would warn that guest is assumed, once per entry.
	 */
	@Test
	public void rebuildRepository_doesNotWarnThatNoUserIsSet() {
		URI previous = pm.getAuthenticatedUserURI();
		pm.setAuthenticatedUserURI(null);
		try (LogCapture warnings = new LogCapture(PrincipalManagerImpl.class, Level.WARN)) {
			// Rebuilds in its constructor, because the store starts empty.
			new PublicRepository(rm).shutdown();

			assertEquals(0, warnings.count(Level.WARN, "Authenticated user not set"));
			assertNull(pm.getAuthenticatedUserURI(), "the rebuild restores the caller's principal");
		} finally {
			pm.setAuthenticatedUserURI(previous);
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

		/**
		 * ENTRYSTORE-1189. Updating a context removes each of its entries before adding back what guest may read, so
		 * an entry guest can no longer read leaves the public repository, although looking it up as guest is denied.
		 */
		@Test
		public void entryOfAContextTurnedPrivate_isGoneFromPublicRepository() {
			EntryImpl child = entryInPublicContext("turned private");
			publicRepository.enqueue(child);
			awaiting().until(() -> hasTitle("turned private"));

			Entry contextEntry = makePrivate(child.getContext());
			publicRepository.enqueue(contextEntry);

			awaiting().until(() -> !hasTitle("turned private"));
		}

		@Test
		public void uncachedEntryOfAContextTurnedPrivate_isGoneFromPublicRepository() {
			EntryImpl child = entryInPublicContext("turned private, uncached");
			publicRepository.enqueue(child);
			awaiting().until(() -> hasTitle("turned private, uncached"));

			Entry contextEntry = makePrivate(child.getContext());
			evictFromSoftCache(child);
			publicRepository.enqueue(contextEntry);

			awaiting().until(() -> !hasTitle("turned private, uncached"));
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

	/** An entry guest may read through ReadResource on its context, created with authorization checked. */
	private EntryImpl entryInPublicContext(String title) {
		return asUser(pm.getAdminUser(), () -> {
			Entry contextEntry = cm.createResource(null, GraphType.Context, null, null);
			contextEntry.addAllowedPrincipalsFor(AccessProperty.ReadResource, pm.getGuestUser().getURI());
			return titledLink((Context) contextEntry.getResource(), title);
		});
	}

	/** Withdraws guest's read access from the context. */
	private Entry makePrivate(Context context) {
		return asUser(pm.getAdminUser(), () -> {
			Entry contextEntry = context.getEntry();
			contextEntry.removeAllowedPrincipalsFor(AccessProperty.ReadResource, pm.getGuestUser().getURI());
			return contextEntry;
		});
	}

	/**
	 * A link with the given title in a new context. The submitter reads entries as guest, so authorization checks
	 * are turned off for it to publish the title.
	 */
	private EntryImpl publishableEntry(String title) {
		rm.setCheckForAuthorization(false);
		return titledLink((Context) cm.createResource(null, GraphType.Context, null, null).getResource(), title);
	}

	private EntryImpl titledLink(Context context, String title) {
		URI resource = URI.create("http://example.com/" + UUID.randomUUID());
		EntryImpl link = (EntryImpl) context.createLink(null, resource, null);
		addTitle(link, title);
		return link;
	}

	@Test
	public void removeEntries_oneOfTwoEntriesStatingAStatement_keepsItInTheContextGraph() {
		rm.setCheckForAuthorization(false);
		ContextImpl context = newContext();
		EntryImpl a = newEntryStating(context, "a", SHARED_TITLE);
		EntryImpl b = newEntryStating(context, "b", SHARED_TITLE);
		PublicRepository publicRepository = new PublicRepository(rm);
		try {
			publicRepository.removeEntries(Set.of(a));

			assertTrue(holds(publicRepository, contextGraph(context), title(SHARED_TITLE)),
					"b still states the shared title");
			assertFalse(holds(publicRepository, contextGraph(context), title("a")));
			assertFalse(holds(publicRepository, a.getSesameLocalMetadataURI(), title(SHARED_TITLE)),
					"the removed entry's own graph is cleared");
		} finally {
			publicRepository.shutdown();
		}
	}

	@Test
	public void removeEntries_bothEntriesStatingAStatement_removeItFromTheContextGraph() {
		rm.setCheckForAuthorization(false);
		ContextImpl context = newContext();
		EntryImpl a = newEntryStating(context, "a", SHARED_TITLE);
		EntryImpl b = newEntryStating(context, "b", SHARED_TITLE);
		PublicRepository publicRepository = new PublicRepository(rm);
		try {
			publicRepository.removeEntries(Set.of(a, b));

			assertFalse(holds(publicRepository, contextGraph(context), title(SHARED_TITLE)));
		} finally {
			publicRepository.shutdown();
		}
	}

	@Test
	public void updateEntries_entryNoLongerStatingASharedStatement_keepsItInTheContextGraph() {
		rm.setCheckForAuthorization(false);
		ContextImpl context = newContext();
		EntryImpl a = newEntryStating(context, "a", SHARED_TITLE);
		EntryImpl b = newEntryStating(context, "b", SHARED_TITLE);
		PublicRepository publicRepository = new PublicRepository(rm);
		try {
			removeTitle(a, SHARED_TITLE);

			publicRepository.updateEntries(Set.of(a));

			assertTrue(holds(publicRepository, contextGraph(context), title(SHARED_TITLE)),
					"b still states the shared title");
			assertFalse(holds(publicRepository, a.getSesameLocalMetadataURI(), title(SHARED_TITLE)));
			assertTrue(holds(publicRepository, contextGraph(context), title("a")),
					"the rest of a is published again");
		} finally {
			publicRepository.shutdown();
		}
	}

	@Test
	public void removeEntries_contextEntry_clearsTheContextAndAllItsEntriesOnly() {
		rm.setCheckForAuthorization(false);
		ContextImpl context = newContext();
		ContextImpl other = newContext();
		EntryImpl a = newEntryStating(context, "a", SHARED_TITLE);
		EntryImpl b = newEntryStating(context, "b", SHARED_TITLE);
		EntryImpl inOther = newEntryStating(other, "c", SHARED_TITLE);
		PublicRepository publicRepository = new PublicRepository(rm);
		try {
			publicRepository.removeEntries(Set.of(context.getEntry()));

			assertFalse(holdsAnything(publicRepository, contextGraph(context)));
			assertFalse(holdsAnything(publicRepository, a.getSesameLocalMetadataURI()));
			assertFalse(holdsAnything(publicRepository, b.getSesameLocalMetadataURI()));
			assertTrue(holds(publicRepository, contextGraph(other), title(SHARED_TITLE)));
			assertTrue(holds(publicRepository, inOther.getSesameLocalMetadataURI(), title(SHARED_TITLE)));
		} finally {
			publicRepository.shutdown();
		}
	}

	@Test
	public void updateEntries_contextMadeNonPublic_leavesNothingOfItAndOtherContextsAlone() {
		rm.setCheckForAuthorization(true);
		URI guest = pm.getGuestUser().getURI();
		pm.setAuthenticatedUserURI(pm.getAdminUser().getURI());
		Entry contextEntry = cm.createResource(null, GraphType.Context, null, null);
		contextEntry.addAllowedPrincipalsFor(AccessProperty.ReadResource, guest);
		Entry otherEntry = cm.createResource(null, GraphType.Context, null, null);
		otherEntry.addAllowedPrincipalsFor(AccessProperty.ReadResource, guest);
		ContextImpl context = (ContextImpl) contextEntry.getResource();
		ContextImpl other = (ContextImpl) otherEntry.getResource();
		EntryImpl a = newEntryStating(context, "a", SHARED_TITLE);
		newEntryStating(context, "b", SHARED_TITLE);
		newEntryStating(other, "c", SHARED_TITLE);
		PublicRepository publicRepository = new PublicRepository(rm);
		try {
			assertTrue(holds(publicRepository, a.getSesameLocalMetadataURI(), title("a")), "a was public");
			pm.setAuthenticatedUserURI(pm.getAdminUser().getURI());
			contextEntry.removeAllowedPrincipalsFor(AccessProperty.ReadResource, guest);

			publicRepository.updateEntries(Set.of(contextEntry));

			assertFalse(holdsAnything(publicRepository, contextGraph(context)));
			assertFalse(holdsAnything(publicRepository, a.getSesameLocalMetadataURI()));
			assertTrue(holds(publicRepository, contextGraph(other), title(SHARED_TITLE)));
		} finally {
			publicRepository.shutdown();
		}
	}

	@Test
	public void removeEntries_statementAlsoInACachedExternalMetadataGraph_keepsIt() {
		rm.setCheckForAuthorization(false);
		ContextImpl context = newContext();
		EntryImpl reference = (EntryImpl) context.createLinkReference(null,
				URI.create("http://example.com/referenced"), URI.create("http://example.com/referenced-metadata"),
				null);
		try (RepositoryConnection rc = rm.getRepository().getConnection()) {
			rc.add(SUBJECT, TITLE, rc.getValueFactory().createLiteral(SHARED_TITLE),
					reference.getSesameCachedExternalMetadataURI());
		}
		EntryImpl stating = newEntryStating(context, "b", SHARED_TITLE);
		PublicRepository publicRepository = new PublicRepository(rm);
		try {
			assertTrue(holds(publicRepository, reference.getSesameCachedExternalMetadataURI(), title(SHARED_TITLE)),
					"the cached external metadata is published");

			publicRepository.removeEntries(Set.of(stating));

			assertTrue(holds(publicRepository, contextGraph(context), title(SHARED_TITLE)),
					"the reference's cached external metadata still states it");
		} finally {
			publicRepository.shutdown();
		}
	}

	@Test
	public void updateAndRemoveEntries_linkToAnotherEntrysMetadataGraph_leaveThatGraphAlone() {
		rm.setCheckForAuthorization(false);
		ContextImpl context = newContext();
		EntryImpl described = newEntryStating(context, "described", SHARED_TITLE);
		EntryImpl link = (EntryImpl) context.createLink(null,
				URI.create(described.getSesameLocalMetadataURI().stringValue()), null);
		PublicRepository publicRepository = new PublicRepository(rm);
		try {
			publicRepository.updateEntries(Set.of(link));
			assertTrue(holds(publicRepository, described.getSesameLocalMetadataURI(), title("described")));

			publicRepository.removeEntries(Set.of(link));
			assertTrue(holds(publicRepository, described.getSesameLocalMetadataURI(), title("described")));
		} finally {
			publicRepository.shutdown();
		}
	}

	@Test
	public void updateAndRemoveEntries_linkToAnotherContext_leaveThatContextsGraphAlone() {
		rm.setCheckForAuthorization(false);
		ContextImpl context = newContext();
		ContextImpl other = newContext();
		newEntryStating(other, "in-other", SHARED_TITLE);
		EntryImpl link = (EntryImpl) context.createLink(null, other.getURI(), null);
		PublicRepository publicRepository = new PublicRepository(rm);
		try {
			publicRepository.updateEntries(Set.of(link));
			assertTrue(holds(publicRepository, contextGraph(other), title("in-other")));

			publicRepository.removeEntries(Set.of(link));
			assertTrue(holds(publicRepository, contextGraph(other), title("in-other")));
		} finally {
			publicRepository.shutdown();
		}
	}

	@Test
	public void updateEntries_linkThatBecameGraphTypeContext_isRemovedFromTheContextGraph() {
		rm.setCheckForAuthorization(false);
		ContextImpl context = newContext();
		EntryImpl link = newEntryStating(context, "link", SHARED_TITLE);
		PublicRepository publicRepository = new PublicRepository(rm);
		try {
			link.setGraphType(GraphType.Context);

			publicRepository.updateEntries(Set.of(link));

			assertFalse(holds(publicRepository, contextGraph(context), title("link")));
		} finally {
			publicRepository.shutdown();
		}
	}

	@Test
	public void removeEntries_linkThatBecameGraphTypeContext_isRemovedFromTheContextGraphOnly() {
		rm.setCheckForAuthorization(false);
		ContextImpl context = newContext();
		EntryImpl link = newEntryStating(context, "link", SHARED_TITLE);
		EntryImpl sibling = newEntryStating(context, "sibling", SHARED_TITLE);
		PublicRepository publicRepository = new PublicRepository(rm);
		try {
			link.setGraphType(GraphType.Context);

			publicRepository.removeEntries(Set.of(link));

			assertFalse(holds(publicRepository, contextGraph(context), title("link")));
			assertTrue(holds(publicRepository, contextGraph(context), title("sibling")),
					"a link is no context, so the rest of its context stays");
		} finally {
			publicRepository.shutdown();
		}
	}

	@Test
	public void updateEntries_contextWithAReferenceLoadedFromTheStore_publishesItsEntriesAgain() {
		rm.setCheckForAuthorization(false);
		Entry contextEntry = cm.createResource(null, GraphType.Context, null, null);
		ContextImpl context = (ContextImpl) contextEntry.getResource();
		EntryImpl reference = (EntryImpl) context.createReference(null, URI.create("http://example.com/referenced"),
				URI.create("http://example.com/referenced-metadata"), null);
		try (RepositoryConnection rc = rm.getRepository().getConnection()) {
			rc.add(SUBJECT, TITLE, rc.getValueFactory().createLiteral("referenced"),
					reference.getSesameCachedExternalMetadataURI());
		}
		EntryImpl a = newEntryStating(context, "a", SHARED_TITLE);
		PublicRepository publicRepository = new PublicRepository(rm);
		try {
			((ContextManagerImpl) cm).softCache.remove(reference);
			assertNull(context.getByEntryURI(reference.getEntryURI()).getLocalMetadataURI(),
					"a Reference loaded from the store has no local metadata graph");

			publicRepository.updateEntries(Set.of(contextEntry));

			assertTrue(holds(publicRepository, a.getSesameLocalMetadataURI(), title("a")));
			assertTrue(holds(publicRepository, reference.getSesameCachedExternalMetadataURI(), title("referenced")));
		} finally {
			publicRepository.shutdown();
		}
	}

	@Test
	public void updateEntries_systemContextOfAllContexts_leavesTheContextsPublished() {
		rm.setCheckForAuthorization(false);
		ContextImpl context = newContext();
		EntryImpl a = newEntryStating(context, "a", SHARED_TITLE);
		PublicRepository publicRepository = new PublicRepository(rm);
		try {
			assertEquals(GraphType.SystemContext, cm.getEntry().getGraphType());

			publicRepository.updateEntries(Set.of(cm.getEntry()));

			assertTrue(holds(publicRepository, contextGraph(context), title("a")));
			assertTrue(holds(publicRepository, a.getSesameLocalMetadataURI(), title("a")));
		} finally {
			publicRepository.shutdown();
		}
	}

	@Test
	public void removeEntries_linkWithItsOwnResourceGraphPublished_removesThatGraph() {
		rm.setCheckForAuthorization(false);
		ContextImpl context = newContext();
		EntryImpl link = newEntryStating(context, "link", SHARED_TITLE);
		IRI ownResourceGraph = iri(URISplit.of(link.getEntryURI(), rm.getRepositoryURL()).orElseThrow()
				.getResourceURI().toString());
		PublicRepository publicRepository = new PublicRepository(rm);
		try (RepositoryConnection rc = publicRepository.getConnection()) {
			// as published while the entry was a local Graph entry, before it became a link to another resource
			rc.add(SUBJECT, TITLE, literal("as a graph"), ownResourceGraph);

			publicRepository.removeEntries(Set.of(link));

			assertFalse(holdsAnything(publicRepository, ownResourceGraph));
		} finally {
			publicRepository.shutdown();
		}
	}

	@Test
	public void removeEntries_entryLargerThanOneChunkInANativeStore_keepsOnlyWhatAnotherEntryStates(
			@TempDir Path storeDirectory) {
		rm.getConfiguration().setProperty(Settings.REPOSITORY_PUBLIC_TYPE, "native");
		rm.getConfiguration().setProperty(Settings.REPOSITORY_PUBLIC_PATH, storeDirectory.toUri().toString());
		assertLargeEntryRemoved();
	}

	@Test
	public void removeEntries_entryLargerThanOneChunk_keepsOnlyWhatAnotherEntryStates() {
		assertLargeEntryRemoved();
	}

	private void assertLargeEntryRemoved() {
		rm.setCheckForAuthorization(false);
		ContextImpl context = newContext();
		EntryImpl large = newEntryStating(context, "large", SHARED_TITLE);
		newEntryStating(context, "other", SHARED_TITLE);
		try (RepositoryConnection rc = rm.getRepository().getConnection()) {
			ValueFactory vf = rc.getValueFactory();
			for (int i = 0; i < 2 * PublicRepository.REMOVAL_CHUNK_SIZE + 1; i++) {
				rc.add(SUBJECT, TITLE, vf.createLiteral("bulk " + i), large.getSesameLocalMetadataURI());
			}
		}
		PublicRepository publicRepository = new PublicRepository(rm);
		try {
			publicRepository.removeEntries(Set.of(large));

			try (RepositoryConnection rc = publicRepository.getConnection()) {
				assertFalse(rc.hasStatement(null, null, null, false, large.getSesameLocalMetadataURI()));
				assertEquals(List.of("other", SHARED_TITLE), rc.getStatements(SUBJECT, TITLE, null, false,
						contextGraph(context)).stream().map(st -> st.getObject().stringValue()).sorted().toList());
			}
		} finally {
			publicRepository.shutdown();
		}
	}

	@Test
	public void removeEntries_entryThatBecameAdministrative_removesWhatItPublished() {
		rm.setCheckForAuthorization(false);
		ContextImpl context = newContext();
		EntryImpl link = newEntryStating(context, "published-before", SHARED_TITLE);
		PublicRepository publicRepository = new PublicRepository(rm);
		try {
			link.setGraphType(GraphType.User);

			publicRepository.removeEntries(Set.of(link));

			assertFalse(holds(publicRepository, contextGraph(context), title("published-before")));
		} finally {
			publicRepository.shutdown();
		}
	}

	@Test
	public void removeEntries_statementAlsoStatedInAnotherContext_leavesThatContextAlone() {
		rm.setCheckForAuthorization(false);
		ContextImpl first = newContext();
		ContextImpl second = newContext();
		EntryImpl inFirst = newEntryStating(first, "a", SHARED_TITLE);
		newEntryStating(second, "b", SHARED_TITLE);
		PublicRepository publicRepository = new PublicRepository(rm);
		try {
			publicRepository.removeEntries(Set.of(inFirst));

			assertFalse(holds(publicRepository, contextGraph(first), title(SHARED_TITLE)),
					"an entry of another context does not keep the statement in this context's graph");
			assertTrue(holds(publicRepository, contextGraph(second), title(SHARED_TITLE)));
		} finally {
			publicRepository.shutdown();
		}
	}

	private EntryImpl newEntryStating(ContextImpl context, String ownTitle, String sharedTitle) {
		EntryImpl entry = (EntryImpl) context.createLink(null, URI.create("http://example.com/" + ownTitle), null);
		try (RepositoryConnection rc = rm.getRepository().getConnection()) {
			ValueFactory vf = rc.getValueFactory();
			rc.add(SUBJECT, TITLE, vf.createLiteral(ownTitle), entry.getSesameLocalMetadataURI());
			rc.add(SUBJECT, TITLE, vf.createLiteral(sharedTitle), entry.getSesameLocalMetadataURI());
		}
		return entry;
	}

	private void removeTitle(EntryImpl entry, String title) {
		try (RepositoryConnection rc = rm.getRepository().getConnection()) {
			rc.remove(SUBJECT, TITLE, rc.getValueFactory().createLiteral(title), entry.getSesameLocalMetadataURI());
		}
	}

	private static IRI contextGraph(ContextImpl context) {
		return iri(context.getURI().toString());
	}

	private static Statement title(String title) {
		return statement(SUBJECT, TITLE, literal(title), null);
	}

	private static boolean holdsAnything(PublicRepository publicRepository, IRI graph) {
		try (RepositoryConnection rc = publicRepository.getConnection()) {
			return rc.hasStatement(null, null, null, false, graph);
		}
	}

	private static boolean holds(PublicRepository publicRepository, IRI graph, Statement statement) {
		try (RepositoryConnection rc = publicRepository.getConnection()) {
			return rc.hasStatement(statement.getSubject(), statement.getPredicate(), statement.getObject(), false,
					graph);
		}
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
