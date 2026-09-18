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

import org.entrystore.Context;
import org.entrystore.Entry;
import org.entrystore.GraphType;
import org.entrystore.Group;
import org.entrystore.PrincipalManager.AccessProperty;
import org.entrystore.User;
import org.entrystore.repository.RepositoryEvent;
import org.entrystore.repository.RepositoryEventObject;
import org.entrystore.repository.RepositoryListener;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Configurator;
import org.apache.logging.log4j.core.config.Property;
import org.apache.logging.log4j.core.layout.PatternLayout;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * The cross-request user-to-groups cache must never grant access after a membership revocation: every mutation
 * path that changes what {@code getGroupUris} would return has to invalidate it. These tests pin the no-staleness
 * contract through the public authorization API and the cache lifecycle through package-private internals.
 */
public class UserGroupsCacheTest extends AbstractCoreTest {

	private PrincipalManagerImpl pmi;
	private Entry target;
	private Entry userEntry;
	private User user;
	private Entry groupEntry;
	private Group group;

	@BeforeEach
	public void setUp() {
		super.setUp();
		pmi = (PrincipalManagerImpl) pm;
		pm.setAuthenticatedUserURI(pm.getAdminUser().getURI());

		// A fresh entry in a non-guest-readable context, readable only via a group grant.
		Context mouse = cm.getContext("mouse");
		target = mouse.createResource(null, GraphType.None, null, null);
		userEntry = pm.createResource(null, GraphType.User, null, null);
		user = (User) userEntry.getResource();
		groupEntry = pm.createResource(null, GraphType.Group, null, null);
		group = (Group) groupEntry.getResource();
		target.addAllowedPrincipalsFor(AccessProperty.ReadMetadata, group.getURI());
	}

	@Test
	public void removeMemberRevokesAccessImmediately() {
		group.addMember(user);
		assertTrue(isAuthorized(user, target, AccessProperty.ReadMetadata),
				"member must be authorized via the group grant");

		group.removeMember(user);
		assertFalse(isAuthorized(user, target, AccessProperty.ReadMetadata),
				"a removed member must lose access on the very next decision; a stale cached group set here "
						+ "is a security bug");
	}

	@Test
	public void addMemberGrantsAccessImmediately() {
		assertFalse(isAuthorized(user, target, AccessProperty.ReadMetadata),
				"non-member must not be authorized (this decision caches the group set, which holds only _users)");

		group.addMember(user);
		assertTrue(isAuthorized(user, target, AccessProperty.ReadMetadata),
				"a new member must gain access on the very next decision");
	}

	@Test
	public void setChildrenReplacementSwapsAccess() {
		Entry otherUserEntry = pm.createResource(null, GraphType.User, null, null);
		User otherUser = (User) otherUserEntry.getResource();
		group.addMember(user);

		assertTrue(isAuthorized(user, target, AccessProperty.ReadMetadata));
		assertFalse(isAuthorized(otherUser, target, AccessProperty.ReadMetadata));

		// Full membership replacement via the inherited ListImpl.setChildren path.
		group.setChildren(List.of(otherUserEntry.getEntryURI()));

		assertFalse(isAuthorized(user, target, AccessProperty.ReadMetadata),
				"replaced-away member must lose access immediately");
		assertTrue(isAuthorized(otherUser, target, AccessProperty.ReadMetadata),
				"replaced-in member must gain access immediately");
	}

	@Test
	public void groupDeleteRevokesAccessImmediately() {
		group.addMember(user);
		assertTrue(isAuthorized(user, target, AccessProperty.ReadMetadata));

		pm.remove(groupEntry.getEntryURI());

		assertFalse(isAuthorized(user, target, AccessProperty.ReadMetadata),
				"deleting the granting group must revoke access on the very next decision");
	}

	@Test
	public void getRightsReflectsMembershipChange() {
		group.addMember(user);
		assertTrue(rightsOf(user, target).contains(AccessProperty.ReadMetadata),
				"getRights must report the group-granted right while the user is a member");

		group.removeMember(user);
		assertFalse(rightsOf(user, target).contains(AccessProperty.ReadMetadata),
				"getRights must drop the group-granted right on the very next call after removal");
	}

	@Test
	public void userDeleteEvictsCache() {
		group.addMember(user);
		isAuthorized(user, target, AccessProperty.ReadMetadata);
		assertTrue(pmi.cachedGroupsView().containsKey(user.getURI()),
				"precondition: a group-consulting decision populates the cache");

		pm.remove(userEntry.getEntryURI());

		assertFalse(pmi.cachedGroupsView().containsKey(user.getURI()),
				"deleting a user must evict their cached group set");
	}

	@Test
	public void staleStampIsNotServedAfterInvalidation() {
		group.addMember(user);
		assertTrue(isAuthorized(user, target, AccessProperty.ReadMetadata));
		assertTrue(pmi.cachedGroupsView().containsKey(user.getURI()), "precondition: the decision cached the set");

		group.removeMember(user);

		// Invalidation only bumps the epoch, so the entry is still mapped and must be rejected by its stamp.
		assertTrue(pmi.cachedGroupsView().containsKey(user.getURI()), "precondition: the stale entry is still mapped");
		assertFalse(isAuthorized(user, target, AccessProperty.ReadMetadata),
				"a set stamped with an older epoch must not be served after the revocation");
	}

	@Test
	public void invalidationSurvivesAThrowingPeerListener() {
		group.addMember(user);
		assertTrue(isAuthorized(user, target, AccessProperty.ReadMetadata));
		RepositoryListener thrower = new RepositoryListener() {
			@Override
			public void repositoryUpdated(RepositoryEventObject eventObject) {
				throw new IllegalStateException("best-effort peer listener failed");
			}
		};
		// removeChild fires the member's EntryUpdated before the group's ResourceUpdated: a throw on the first
		// used to abort the dispatch before the invalidator ever saw the group event.
		RepositoryManagerImpl rmi = (RepositoryManagerImpl) rm;
		rmi.registerListener(thrower, RepositoryEvent.EntryUpdated);
		rmi.registerListener(thrower, RepositoryEvent.ResourceUpdated);
		try {
			group.removeMember(user);

			assertFalse(isAuthorized(user, target, AccessProperty.ReadMetadata),
					"a peer listener's failure must not skip the group-cache invalidation");
		} finally {
			rmi.unregisterListener(thrower, RepositoryEvent.EntryUpdated);
			rmi.unregisterListener(thrower, RepositoryEvent.ResourceUpdated);
		}
	}

	@Test
	public void incompleteIndexScanIsNotCached() {
		group.addMember(user);
		PrincipalManagerImpl spied = spy(pmi);
		doReturn(false).when(spied).isIndexComplete();

		Set<URI> groups = spied.getGroupUrisCached(user.getURI());

		assertTrue(groups.contains(group.getURI()), "the scan itself still answers from what it could see");
		assertFalse(pmi.cachedGroupsView().containsKey(user.getURI()),
				"a scan over an incomplete index is a denial, not a fact, and must not be published");
	}

	@Test
	public void unresolvableUserIsNotCached() {
		URI unknownUser = URI.create("http://localhost:8181/_principals/resource/does-not-exist");

		assertTrue(pmi.getGroupUrisCached(unknownUser).isEmpty());
		assertFalse(pmi.cachedGroupsView().containsKey(unknownUser),
				"an unresolvable principal must not get an empty set parked in the cache");
	}

	@Test
	public void secondDecisionIsServedFromTheCache() {
		group.addMember(user);
		PrincipalManagerImpl spied = spy(pmi);

		Set<URI> first = spied.getGroupUrisCached(user.getURI());
		Set<URI> second = spied.getGroupUrisCached(user.getURI());

		assertTrue(first.contains(group.getURI()));
		assertSame(first, second, "while the epoch is unchanged the cached set itself must be served");
		verify(spied, times(1)).scanGroups(user.getURI());
	}

	@Test
	public void absentIndexedEntryDoesNotDisableTheCache() {
		group.addMember(user);
		Set<URI> listing = new HashSet<>(pmi.getEntries());
		// a stale index mapping, as a tolerated post-commit failure in ContextImpl.remove leaves behind
		listing.add(URI.create("http://localhost:8181/_principals/entry/does-not-exist"));
		PrincipalManagerImpl spied = spy(pmi);
		doReturn(listing).when(spied).getEntries();

		Set<URI> groups = spied.getGroupUrisCached(user.getURI());

		assertTrue(groups.contains(group.getURI()), "the scan still sees every group that does exist");
		assertTrue(pmi.cachedGroupsView().containsKey(user.getURI()),
				"an index mapping without an entry cannot hold a membership and must not stop the scan from being cached");
	}

	/**
	 * The scan treats a listed entry it cannot load as one that cannot hold a membership, which is only safe
	 * because a failed store read reaches it as an exception rather than as null: {@code ContextImpl} catches
	 * the RDF4J RepositoryException but rethrows the core one. Pins the scan's half of that contract, so
	 * widening the catch, or adding a log-and-return-null path, cannot silently turn a read failure into a
	 * short scan that is published as authoritative.
	 */
	@Test
	public void unreadableIndexedEntryFailsTheDecisionAndIsNotCached() {
		group.addMember(user);
		PrincipalManagerImpl spied = spy(pmi);
		doThrow(new org.entrystore.repository.RepositoryException("simulated store failure"))
				.when(spied).getByEntryURI(groupEntry.getEntryURI());

		assertThrows(org.entrystore.repository.RepositoryException.class,
				() -> spied.getGroupUrisCached(user.getURI()));

		assertFalse(pmi.cachedGroupsView().containsKey(user.getURI()),
				"a scan that could not read a listed entry must fail the decision rather than be cached");
	}

	/**
	 * The scan runs on every authorization decision, so an index mapping it cannot load must be warned about
	 * once and logged at debug afterwards. The suppression is observable only in the log: the set the scan keeps
	 * cannot witness it, since {@code Set.add} is idempotent and leaves the same set behind whether the code
	 * warns once or warns every time. Scans are driven directly because {@code getGroupUrisCached} would serve
	 * the second call from the cache without scanning at all.
	 */
	@Test
	public void anUnloadableIndexedEntryIsWarnedAboutOnlyOnce() {
		group.addMember(user);
		URI dangling = URI.create("http://localhost:8181/_principals/entry/does-not-exist");
		Set<URI> listing = new HashSet<>(pmi.getEntries());
		listing.add(dangling);
		PrincipalManagerImpl spied = spy(pmi);
		doReturn(listing).when(spied).getEntries();

		try (LogCapture log = new LogCapture(PrincipalManagerImpl.class)) {
			spied.scanGroups(user.getURI());
			spied.scanGroups(user.getURI());

			assertEquals(1, log.warningsMentioning(dangling.toString()),
					"a dangling index mapping must be warned about once, not on every authorization decision");
		}
		assertTrue(pmi.reportedUnloadableEntriesView().contains(dangling),
				"the URI must be recorded, since that record is what suppresses the second warning");
	}

	@Test
	public void indexIncompleteWhenListedIsNotCached() {
		group.addMember(user);
		PrincipalManagerImpl spied = spy(pmi);
		// incomplete while the listing is taken; the repair lands as getEntries() returns, before any later check
		AtomicBoolean indexComplete = new AtomicBoolean(false);
		doAnswer(invocation -> indexComplete.get()).when(spied).isIndexComplete();
		doAnswer(invocation -> {
			Object listing = invocation.callRealMethod();
			indexComplete.set(true);
			return listing;
		}).when(spied).getEntries();

		spied.getGroupUrisCached(user.getURI());

		assertFalse(pmi.cachedGroupsView().containsKey(user.getURI()),
				"a listing taken over an incomplete index may be short and a later repair must not certify it");
	}

	@Test
	public void deletedUserIsDeniedRatherThanFailingTheTest() {
		group.addMember(user);
		assertTrue(isAuthorized(user, target, AccessProperty.ReadMetadata));

		pm.remove(userEntry.getEntryURI());

		assertFalse(isAuthorized(user, target, AccessProperty.ReadMetadata),
				"a deleted principal is denied in production, so the helper must report that as a denial");
	}

	@Test
	public void failedSetChildrenNotifiesListenersAndRestoresMembers() {
		group.addMember(user);
		GroupImpl spied = spy((GroupImpl) group);
		doThrow(new org.eclipse.rdf4j.repository.RepositoryException("simulated write failure"))
				.when(spied).saveChildren(any(), any(RepositoryConnection.class));
		RecordingListener recorder = RecordingListener.register((RepositoryManagerImpl) rm);
		try {
			assertThrows(org.entrystore.repository.RepositoryException.class, () -> spied.setChildren(List.of()));

			assertTrue(spied.getChildren().contains(userEntry.getEntryURI()), "the in-memory member list must be restored");
			assertTrue(recorder.sources.contains(groupEntry.getEntryURI()),
					"a failed member-list write must still publish a group-sourced ResourceUpdated");
			int invalidation = recorder.indexOf(RepositoryEvent.ResourceUpdated, groupEntry.getEntryURI());
			int recovery = recorder.indexOf(RepositoryEvent.EntryUpdated, userEntry.getEntryURI());
			assertTrue(recovery >= 0, "the recovery must still refresh and publish the removed member");
			assertTrue(invalidation < recovery,
					"the invalidation must fire before the recovery, which is what fails on a broken connection");
		} finally {
			recorder.unregister((RepositoryManagerImpl) rm);
		}
	}

	@Test
	public void failedRemoveChildNotifiesListenersAndRestoresMembers() {
		group.addMember(user);
		GroupImpl spied = spy((GroupImpl) group);
		// Closing the connection first makes the recovery's rollback fail too. That is the state the ordering
		// exists for: a broken connection is what fails the write in the first place, and with the restore and
		// the event sitting behind the rollback neither one would run.
		doAnswer(invocation -> {
			invocation.getArgument(1, RepositoryConnection.class).close();
			throw new org.eclipse.rdf4j.repository.RepositoryException("simulated write failure");
		}).when(spied).saveChildren(any(), any(RepositoryConnection.class));
		RecordingListener recorder = RecordingListener.register((RepositoryManagerImpl) rm);
		try {
			assertFalse(spied.removeChild(userEntry.getEntryURI()), "the failed removal must be reported");

			assertTrue(spied.getChildren().contains(userEntry.getEntryURI()), "the in-memory member list must be restored");
			assertTrue(recorder.sources.contains(groupEntry.getEntryURI()),
					"a failed member removal must still publish a group-sourced ResourceUpdated");
		} finally {
			recorder.unregister((RepositoryManagerImpl) rm);
		}
	}

	/** Captures every ResourceUpdated and EntryUpdated dispatched while registered, in dispatch order. */
	private static final class RecordingListener extends RepositoryListener {
		final List<URI> sources = new ArrayList<>();
		private final List<RepositoryEvent> events = new ArrayList<>();

		static RecordingListener register(RepositoryManagerImpl rm) {
			RecordingListener recorder = new RecordingListener();
			rm.registerListener(recorder, RepositoryEvent.ResourceUpdated);
			rm.registerListener(recorder, RepositoryEvent.EntryUpdated);
			return recorder;
		}

		void unregister(RepositoryManagerImpl rm) {
			rm.unregisterListener(this, RepositoryEvent.ResourceUpdated);
			rm.unregisterListener(this, RepositoryEvent.EntryUpdated);
		}

		/** Dispatch position of the first {@code event} sourced from {@code source}, or -1 if it never arrived. */
		int indexOf(RepositoryEvent event, URI source) {
			for (int i = 0; i < events.size(); i++) {
				if (events.get(i) == event && sources.get(i).equals(source)) {
					return i;
				}
			}
			return -1;
		}

		@Override
		public void repositoryUpdated(RepositoryEventObject eventObject) {
			events.add(eventObject.getEvent());
			sources.add(((Entry) eventObject.getSource()).getEntryURI());
		}
	}

	@Test
	public void loaderRacingInvalidationDoesNotPublishStaleGroups() throws Exception {
		group.addMember(user);

		// The spy shares the cache map and the epoch counter with the real principal manager, so an
		// invalidation triggered through the real listener is visible to a loader running on the spy.
		PrincipalManagerImpl spied = spy(pmi);
		CountDownLatch scanCaptured = new CountDownLatch(1);
		CountDownLatch mayPublish = new CountDownLatch(1);
		doAnswer(invocation -> {
			// Scan first, so the captured set still lists the group, then hold the result back until
			// the membership change has committed and fired its invalidation.
			Object preMutationGroups = invocation.callRealMethod();
			scanCaptured.countDown();
			if (!mayPublish.await(10, TimeUnit.SECONDS)) {
				throw new IllegalStateException("loader was never released");
			}
			return preMutationGroups;
		}).when(spied).scanGroups(any(URI.class));

		ExecutorService loader = Executors.newSingleThreadExecutor();
		try {
			Future<Set<URI>> published = loader.submit(() -> {
				pm.setAuthenticatedUserURI(pm.getAdminUser().getURI());
				return spied.getGroupUrisCached(user.getURI());
			});
			assertTrue(scanCaptured.await(10, TimeUnit.SECONDS), "loader must have scanned before the mutation");

			group.removeMember(user);
			mayPublish.countDown();
			Set<URI> staleGroups = published.get(10, TimeUnit.SECONDS);

			assertTrue(staleGroups.contains(group.getURI()),
					"precondition: the loader really did capture the pre-mutation group set");
			assertFalse(pmi.cachedGroupsView().containsKey(user.getURI()),
					"a scan that started before the membership change committed must not be published");
			assertFalse(isAuthorized(user, target, AccessProperty.ReadMetadata),
					"the next decision must see the revocation, not the racing loader's snapshot");
		} finally {
			loader.shutdownNow();
		}
	}

	/**
	 * Captures what one class logs, so a branch whose only effect is the level it logs at can be asserted on.
	 * Raises that logger to WARN for the duration: the shared test configuration pins {@code org.entrystore.impl}
	 * at ERROR, which would otherwise drop the event before any appender sees it.
	 */
	private static final class LogCapture implements AutoCloseable {

		private final Logger logger;
		private final Level previousLevel;
		private final List<LogEvent> events = Collections.synchronizedList(new ArrayList<>());
		private final AbstractAppender appender;

		private LogCapture(Class<?> owner) {
			logger = (Logger) LogManager.getLogger(owner);
			previousLevel = logger.getLevel();
			appender = new AbstractAppender("capture-" + owner.getSimpleName(), null,
					PatternLayout.createDefaultLayout(), true, Property.EMPTY_ARRAY) {
				@Override
				public void append(LogEvent event) {
					events.add(event.toImmutable());
				}
			};
			appender.start();
			logger.addAppender(appender);
			Configurator.setLevel(owner.getName(), Level.WARN);
		}

		private long warningsMentioning(String needle) {
			synchronized (events) {
				return events.stream()
						.filter(event -> Level.WARN.equals(event.getLevel()))
						.filter(event -> event.getMessage().getFormattedMessage().contains(needle))
						.count();
			}
		}

		@Override
		public void close() {
			Configurator.setLevel(logger.getName(), previousLevel);
			logger.removeAppender(appender);
			appender.stop();
		}
	}
}
