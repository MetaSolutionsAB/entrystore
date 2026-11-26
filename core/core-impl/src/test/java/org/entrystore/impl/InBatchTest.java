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

import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Model;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.LinkedHashModel;
import org.eclipse.rdf4j.model.vocabulary.DCTERMS;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.entrystore.Context;
import org.entrystore.Entry;
import org.entrystore.GraphType;
import org.entrystore.Group;
import org.entrystore.PrincipalManager.AccessProperty;
import org.entrystore.User;
import org.entrystore.exception.InvalidExternalMetadataURIException;
import org.entrystore.repository.RepositoryEvent;
import org.entrystore.repository.RepositoryEventObject;
import org.entrystore.repository.RepositoryListener;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.ArrayList;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link RepositoryManagerImpl#inBatch} holds back what its operations would publish until the batch commits, and
 * bounds the transaction with a checkpoint commit.
 */
public class InBatchTest extends AbstractCoreTest {

	private Context duck;

	private final AtomicInteger created = new AtomicInteger();

	private final RepositoryListener counting = new RepositoryListener() {
		@Override
		public void repositoryUpdated(RepositoryEventObject eventObject) {
			created.incrementAndGet();
		}
	};

	@BeforeEach
	public void setUp() {
		super.setUp();
		pm.setAuthenticatedUserURI(pm.getAdminUser().getURI());
		duck = cm.getContext("duck");
		rm.registerListener(counting, RepositoryEvent.EntryCreated);
	}

	@AfterEach
	public void unregister() {
		if (rm != null) {
			rm.unregisterListener(counting, RepositoryEvent.EntryCreated);
		}
	}

	@Test
	public void anEventRaisedInsideABatchReachesListenersOnlyAfterTheCommit() {
		AtomicInteger seenInsideTheBatch = new AtomicInteger(-1);

		rm.inBatch(() -> {
			duck.createLink(null, URI.create("http://example.com/a"), null);
			seenInsideTheBatch.set(created.get());
		});

		assertEquals(0, seenInsideTheBatch.get(), "a listener must not see a change the batch has only staged");
		assertEquals(1, created.get());
	}

	@Test
	public void theEventsOfARolledBackBatchAreDropped() {
		assertThrows(IllegalStateException.class, () -> rm.inBatch(() -> {
			duck.createLink(null, URI.create("http://example.com/a"), null);
			throw new IllegalStateException("boom");
		}));

		assertEquals(0, created.get(), "a listener must not hear of a change that was rolled back");
	}

	@Test
	public void aListenerFailingAfterTheCommitDoesNotFailTheCommittedBatch() {
		RepositoryListener failing = new RepositoryListener() {
			@Override
			public void repositoryUpdated(RepositoryEventObject eventObject) {
				throw new IllegalStateException("listener failure");
			}
		};
		rm.registerListener(failing, RepositoryEvent.EntryCreated);
		List<Entry> entries = new ArrayList<>();
		try {
			rm.inBatch(() -> entries.add(duck.createLink(null, URI.create("http://example.com/a"), null)));
		} finally {
			rm.unregisterListener(failing, RepositoryEvent.EntryCreated);
		}

		assertTrue(isStored(entries.getFirst()));
	}

	@Test
	public void theBatchCommitsAtTheCheckpointSoARollbackKeepsTheWorkBeforeIt() {
		rm.setBatchMaxOperations(2);
		List<Entry> entries = new ArrayList<>();

		assertThrows(IllegalStateException.class, () -> rm.inBatch(() -> {
			entries.add(duck.createLink(null, URI.create("http://example.com/a"), null));
			entries.add(duck.createLink(null, URI.create("http://example.com/b"), null));
			// the third operation reaches the checkpoint first, which commits the two before it
			entries.add(duck.createLink(null, URI.create("http://example.com/c"), null));
			throw new IllegalStateException("boom");
		}));

		assertTrue(isStored(entries.get(0)));
		assertTrue(isStored(entries.get(1)));
		assertFalse(isStored(entries.get(2)), "the work after the checkpoint is rolled back");
		assertEquals(2, created.get(), "only the committed entries are announced");
	}

	@Test
	public void aMetadataWriteThatFailsAndIsCaughtByTheWorkRollsTheBatchBack() {
		Entry existing = duck.createLink(null, URI.create("http://example.com/existing"), null);
		existing.getLocalMetadata().setGraph(titleOf(existing, "kept"));
		int createdBefore = created.get();
		List<Entry> entries = new ArrayList<>();

		assertThrows(org.entrystore.repository.RepositoryException.class, () -> rm.inBatch(() -> {
			entries.add(duck.createLink(null, URI.create("http://example.com/a"), null));
			try {
				// fails after the old graph is cleared on the batch connection
				existing.getLocalMetadata().setGraph(unreadableGraph());
			} catch (org.entrystore.repository.RepositoryException skipped) {
				// the work goes on, as an import that skips a bad record does
			}
		}));

		assertTrue(hasTitle(existing, "kept"), "the cleared graph must not be committed");
		assertFalse(isStored(entries.getFirst()));
		assertEquals(createdBefore, created.get());
	}

	@Test
	public void aCreateThatFailsAndIsCaughtByTheWorkRollsTheBatchBack() {
		List<Entry> entries = new ArrayList<>();

		assertThrows(org.entrystore.repository.RepositoryException.class, () -> rm.inBatch(() -> {
			entries.add(duck.createLink(null, URI.create("http://example.com/a"), null));
			try {
				duck.createReference(null, URI.create("http://example.com/b"), null, null);
			} catch (org.entrystore.repository.RepositoryException skipped) {
				// the work goes on
			}
		}));

		assertFalse(isStored(entries.getFirst()));
	}

	@Test
	public void anOperationAfterAFailedOneFailsInsteadOfReachingTheCheckpoint() {
		rm.setBatchMaxOperations(2);
		Entry existing = duck.createLink(null, URI.create("http://example.com/existing"), null);
		existing.getLocalMetadata().setGraph(titleOf(existing, "kept"));
		List<Entry> entries = new ArrayList<>();
		AtomicInteger operationsAfterTheFailure = new AtomicInteger();

		assertThrows(org.entrystore.repository.RepositoryException.class, () -> rm.inBatch(() -> {
			entries.add(duck.createLink(null, URI.create("http://example.com/a"), null));
			try {
				existing.getLocalMetadata().setGraph(unreadableGraph());
			} catch (org.entrystore.repository.RepositoryException skipped) {
				// the work goes on
			}
			// the third operation would reach the checkpoint, and so commit the cleared graph
			duck.createLink(null, URI.create("http://example.com/c"), null);
			operationsAfterTheFailure.incrementAndGet();
		}));

		assertEquals(0, operationsAfterTheFailure.get(), "an operation in a rollback-only batch must fail");
		assertFalse(isStored(entries.getFirst()), "the checkpoint must not commit a rollback-only batch");
		assertTrue(hasTitle(existing, "kept"), "the cleared graph must not be committed");
	}

	@Test
	public void aCreateRejectedForItsInputDoesNotFailTheBatch() {
		URI baseURI = URI.create(rm.getRepositoryURL().toString());
		List<Entry> entries = new ArrayList<>();

		rm.inBatch(() -> {
			entries.add(duck.createLink(null, URI.create("http://example.com/a"), null));
			assertThrows(InvalidExternalMetadataURIException.class,
					() -> duck.createLinkReference(null, URI.create("http://example.com/b"), baseURI, null));
		});

		assertTrue(isStored(entries.getFirst()), "nothing was written for the rejected entry, so the batch commits");
	}

	@Test
	public void aResourceRenameInsideABatchIsRefused() {
		URI oldResourceURI = URI.create("http://example.com/existing");
		Entry existing = duck.createLink(null, oldResourceURI, null);
		existing.getLocalMetadata().setGraph(titleOf(existing, "kept"));

		assertThrows(IllegalStateException.class,
				() -> rm.inBatch(() -> existing.setResourceURI(URI.create("http://example.com/renamed"))));

		ValueFactory vf = rm.getValueFactory();
		IRI oldResourceIRI = vf.createIRI(oldResourceURI.toString());
		assertEquals(oldResourceURI, existing.getResourceURI());
		assertTrue(existing.getLocalMetadata().getGraph().contains(oldResourceIRI, DCTERMS.TITLE, null),
				"the metadata must not be moved to a resource URI the entry graph does not name");
		try (RepositoryConnection rc = rm.getRepository().getConnection()) {
			IRI entryIRI = ((EntryImpl) existing).getSesameEntryURI();
			assertTrue(rc.hasStatement(entryIRI, RepositoryProperties.resource, oldResourceIRI, false, entryIRI));
		}
	}

	@Test
	public void aRefusedRenameCaughtByTheWorkDoesNotFailTheBatch() {
		Entry existing = duck.createLink(null, URI.create("http://example.com/existing"), null);
		List<Entry> entries = new ArrayList<>();

		rm.inBatch(() -> {
			entries.add(duck.createLink(null, URI.create("http://example.com/a"), null));
			assertThrows(IllegalStateException.class,
					() -> existing.setResourceURI(URI.create("http://example.com/renamed")));
		});

		assertTrue(isStored(entries.getFirst()), "nothing was written for the refused rename, so the batch commits");
	}

	@Test
	public void anEntryOfARolledBackBatchIsNotServedFromTheCache() {
		List<Entry> entries = new ArrayList<>();

		assertThrows(IllegalStateException.class, () -> rm.inBatch(() -> {
			entries.add(duck.createLink(null, URI.create("http://example.com/a"), null));
			throw new IllegalStateException("boom");
		}));

		assertNull(duck.getByEntryURI(entries.getFirst().getEntryURI()));
	}

	@Test
	public void anEntryCommittedAtACheckpointStaysCachedWhenTheRestIsRolledBack() {
		rm.setBatchMaxOperations(2);
		List<Entry> entries = new ArrayList<>();

		assertThrows(IllegalStateException.class, () -> rm.inBatch(() -> {
			entries.add(duck.createLink(null, URI.create("http://example.com/a"), null));
			entries.add(duck.createLink(null, URI.create("http://example.com/b"), null));
			entries.add(duck.createLink(null, URI.create("http://example.com/c"), null));
			throw new IllegalStateException("boom");
		}));

		assertSame(entries.get(0), duck.getByEntryURI(entries.get(0).getEntryURI()));
		assertSame(entries.get(1), duck.getByEntryURI(entries.get(1).getEntryURI()));
		assertNull(duck.getByEntryURI(entries.get(2).getEntryURI()));
	}

	@Test
	public void anEntryCreatedInABatchIsReadableInsideIt() {
		List<Entry> created = new ArrayList<>();
		List<Entry> readInsideTheBatch = new ArrayList<>();

		rm.inBatch(() -> {
			created.add(duck.createLink(null, URI.create("http://example.com/a"), null));
			readInsideTheBatch.add(duck.getByEntryURI(created.getFirst().getEntryURI()));
		});

		assertSame(created.getFirst(), readInsideTheBatch.getFirst());
	}

	@Test
	public void anAclChangeInABatchThatFailsStillReachesListeners() {
		Entry existing = duck.createLink(null, URI.create("http://example.com/existing"), null);
		List<RepositoryEventObject> updates = new ArrayList<>();
		RepositoryListener recording = recording(updates);
		rm.registerListener(recording, RepositoryEvent.EntryUpdated);
		try {
			assertThrows(IllegalStateException.class, () -> rm.inBatch(() -> {
				// committed on its own connection, so the rollback below does not undo it
				existing.addAllowedPrincipalsFor(AccessProperty.ReadMetadata, pm.getGuestUser().getURI());
				throw new IllegalStateException("boom");
			}));
		} finally {
			rm.unregisterListener(recording, RepositoryEvent.EntryUpdated);
		}

		assertTrue(existing.getAllowedPrincipalsFor(AccessProperty.ReadMetadata).contains(pm.getGuestUser().getURI()));
		assertTrue(updates.stream().anyMatch(event -> event.getSource() == existing),
				"the Solr ACL fields follow this event, and the ACL change is stored");
	}

	@Test
	public void aMembershipRevokedInABatchThatFailsIsRevokedOnTheNextDecision() {
		Entry target = cm.getContext("mouse").createResource(null, GraphType.None, null, null);
		User user = (User) pm.createResource(null, GraphType.User, null, null).getResource();
		Group group = (Group) pm.createResource(null, GraphType.Group, null, null).getResource();
		target.addAllowedPrincipalsFor(AccessProperty.ReadMetadata, group.getURI());
		group.addMember(user);
		assertTrue(isAuthorized(user, target, AccessProperty.ReadMetadata), "this decision caches the group set");

		assertThrows(IllegalStateException.class, () -> rm.inBatch(() -> {
			group.removeMember(user);
			throw new IllegalStateException("boom");
		}));

		assertFalse(isAuthorized(user, target, AccessProperty.ReadMetadata),
				"the removal is stored, so a cached group set must not keep granting access");
	}

	@Test
	public void noEventAboutAnEntryCreatedInABatchReachesListenersBeforeTheCommit() {
		Entry list = duck.createResource(null, GraphType.List, null, null);
		List<RepositoryEventObject> events = new ArrayList<>();
		RepositoryListener recording = recording(events);
		rm.registerListener(recording, RepositoryEvent.All);
		List<Entry> entries = new ArrayList<>();
		AtomicInteger aboutTheNewEntryInsideTheBatch = new AtomicInteger(-1);
		try {
			rm.inBatch(() -> {
				// adding it to the list commits on its own connection and raises events about the new entry
				entries.add(duck.createLink(null, URI.create("http://example.com/a"), list.getResourceURI()));
				aboutTheNewEntryInsideTheBatch.set(eventsAbout(entries.getFirst(), events));
			});
		} finally {
			rm.unregisterListener(recording, RepositoryEvent.All);
		}

		assertEquals(0, aboutTheNewEntryInsideTheBatch.get(), "a listener would index an entry a rollback removes");
		assertTrue(eventsAbout(entries.getFirst(), events) > 0);
	}

	@Test
	public void noEventAboutAnEntryCreatedInARolledBackBatchReachesListeners() {
		Entry list = duck.createResource(null, GraphType.List, null, null);
		List<RepositoryEventObject> events = new ArrayList<>();
		RepositoryListener recording = recording(events);
		rm.registerListener(recording, RepositoryEvent.All);
		List<Entry> entries = new ArrayList<>();
		try {
			assertThrows(IllegalStateException.class, () -> rm.inBatch(() -> {
				entries.add(duck.createLink(null, URI.create("http://example.com/a"), list.getResourceURI()));
				throw new IllegalStateException("boom");
			}));
		} finally {
			rm.unregisterListener(recording, RepositoryEvent.All);
		}

		assertEquals(0, eventsAbout(entries.getFirst(), events));
	}

	@Test
	public void theIndexDoesNotListAnEntryOfARolledBackBatch() {
		// Published first, so an index op applied too early changes a live index instead of a buffer
		duck.getEntries();
		List<Entry> entries = new ArrayList<>();

		assertThrows(IllegalStateException.class, () -> rm.inBatch(() -> {
			entries.add(duck.createLink(null, URI.create("http://example.com/a"), null));
			throw new IllegalStateException("boom");
		}));

		assertFalse(duck.getEntries().contains(entries.getFirst().getEntryURI()));
	}

	@Test
	public void theIndexListsAnEntryCreatedInABatchOnlyAfterTheCommit() {
		duck.getEntries();
		List<Entry> entries = new ArrayList<>();
		List<Boolean> listedInsideTheBatch = new ArrayList<>();

		rm.inBatch(() -> {
			entries.add(duck.createLink(null, URI.create("http://example.com/a"), null));
			listedInsideTheBatch.add(duck.getEntries().contains(entries.getFirst().getEntryURI()));
		});

		assertFalse(listedInsideTheBatch.getFirst());
		assertTrue(duck.getEntries().contains(entries.getFirst().getEntryURI()));
	}

	@Test
	public void aMetadataWriteInsideABatchLandsWithTheCommit() {
		Entry existing = duck.createLink(null, URI.create("http://example.com/existing"), null);
		List<RepositoryEventObject> updates = new ArrayList<>();
		RepositoryListener recording = recording(updates);
		rm.registerListener(recording, RepositoryEvent.MetadataUpdated);
		AtomicInteger updatesInsideTheBatch = new AtomicInteger(-1);
		try {
			rm.inBatch(() -> {
				existing.getLocalMetadata().setGraph(titleOf(existing, "new"));
				updatesInsideTheBatch.set(updates.size());
			});
		} finally {
			rm.unregisterListener(recording, RepositoryEvent.MetadataUpdated);
		}

		assertEquals(0, updatesInsideTheBatch.get(), "a listener must not see a change the batch has only staged");
		assertEquals(1, updates.size());
		assertTrue(hasTitle(existing, "new"));
	}

	@Test
	public void aMetadataWriteOfARolledBackBatchIsDiscarded() {
		Entry existing = duck.createLink(null, URI.create("http://example.com/existing"), null);
		List<RepositoryEventObject> updates = new ArrayList<>();
		RepositoryListener recording = recording(updates);
		rm.registerListener(recording, RepositoryEvent.MetadataUpdated);
		try {
			assertThrows(IllegalStateException.class, () -> rm.inBatch(() -> {
				existing.getLocalMetadata().setGraph(titleOf(existing, "new"));
				throw new IllegalStateException("boom");
			}));
		} finally {
			rm.unregisterListener(recording, RepositoryEvent.MetadataUpdated);
		}

		assertFalse(hasTitle(existing, "new"));
		assertEquals(0, updates.size());
	}

	@Test
	public void aContributorOfARolledBackBatchIsRecordedOnTheNextWrite() {
		Entry existing = duck.createLink(null, URI.create("http://example.com/existing"), null);
		User editor = (User) pm.createResource(null, GraphType.User, null, null).getResource();
		existing.addAllowedPrincipalsFor(AccessProperty.WriteMetadata, editor.getURI());

		asUser(editor, () -> {
			assertThrows(IllegalStateException.class, () -> rm.inBatch(() -> {
				existing.getLocalMetadata().setGraph(titleOf(existing, "discarded"));
				throw new IllegalStateException("boom");
			}));
			existing.getLocalMetadata().setGraph(titleOf(existing, "kept"));
			return null;
		});

		try (RepositoryConnection rc = rm.getRepository().getConnection()) {
			IRI entryIRI = ((EntryImpl) existing).getSesameEntryURI();
			IRI editorIRI = rm.getValueFactory().createIRI(editor.getURI().toString());
			assertTrue(rc.hasStatement(entryIRI, RepositoryProperties.Contributor, editorIRI, false, entryIRI),
					"the rolled-back write must not leave the editor cached as a contributor the store does not have");
		}
	}

	@Test
	public void aMetadataWriteOfARolledBackBatchLeavesTheStoredModifiedDate() {
		Entry existing = duck.createLink(null, URI.create("http://example.com/existing"), null);
		existing.getLocalMetadata().setGraph(titleOf(existing, "kept"));
		Date storedModified = existing.getModifiedDate();
		// so that the write in the batch cannot stamp the same millisecond
		while (System.currentTimeMillis() <= storedModified.getTime()) {
			Thread.onSpinWait();
		}

		assertThrows(IllegalStateException.class, () -> rm.inBatch(() -> {
			existing.getLocalMetadata().setGraph(titleOf(existing, "discarded"));
			throw new IllegalStateException("boom");
		}));

		assertEquals(storedModified, existing.getModifiedDate());
	}

	@Test
	public void aFailingPostCommitActionNeitherFailsTheBatchNorSkipsTheRest() {
		List<Entry> entries = new ArrayList<>();
		AtomicInteger laterActions = new AtomicInteger();

		rm.inBatch(() -> {
			entries.add(duck.createLink(null, URI.create("http://example.com/a"), null));
			rm.runAfterBatchCommit(() -> {
				throw new IllegalStateException("post-commit failure");
			});
			rm.runAfterBatchCommit(laterActions::incrementAndGet);
		});

		assertTrue(isStored(entries.getFirst()));
		assertEquals(1, laterActions.get());
		assertEquals(1, created.get());
	}

	private boolean hasTitle(Entry entry, String title) {
		return entry.getLocalMetadata().getGraph()
				.contains(null, DCTERMS.TITLE, rm.getValueFactory().createLiteral(title));
	}

	private static RepositoryListener recording(List<RepositoryEventObject> events) {
		return new RepositoryListener() {
			@Override
			public void repositoryUpdated(RepositoryEventObject eventObject) {
				events.add(eventObject);
			}
		};
	}

	private static int eventsAbout(Entry entry, List<RepositoryEventObject> events) {
		return (int) events.stream().filter(event -> event.getSource() == entry).count();
	}

	private Model titleOf(Entry entry, String title) {
		ValueFactory vf = rm.getValueFactory();
		Model graph = new LinkedHashModel();
		graph.add(vf.createIRI(entry.getResourceURI().toString()), DCTERMS.TITLE, vf.createLiteral(title));
		return graph;
	}

	/** A graph that cannot be read, so that writing it fails after the old graph has been removed. */
	private static Model unreadableGraph() {
		return new LinkedHashModel() {
			@Override
			public Iterator<Statement> iterator() {
				throw new IllegalStateException("unreadable graph");
			}
		};
	}

	private boolean isStored(Entry entry) {
		try (RepositoryConnection rc = rm.getRepository().getConnection()) {
			IRI entryIRI = ((EntryImpl) entry).getSesameEntryURI();
			return rc.hasStatement(null, null, null, false, entryIRI);
		}
	}
}
