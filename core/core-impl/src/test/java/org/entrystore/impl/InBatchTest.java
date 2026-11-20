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
import org.entrystore.exception.InvalidExternalMetadataURIException;
import org.entrystore.repository.RepositoryEvent;
import org.entrystore.repository.RepositoryEventObject;
import org.entrystore.repository.RepositoryListener;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.ArrayList;
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

		assertTrue(existing.getLocalMetadata().getGraph().contains(null, DCTERMS.TITLE, rm.getValueFactory().createLiteral("kept")),
				"the cleared graph must not be committed");
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
			entries.add(duck.createLink(null, URI.create("http://example.com/b"), null));
			// the third operation commits the two before it at the checkpoint, then fails
			try {
				existing.getLocalMetadata().setGraph(unreadableGraph());
			} catch (org.entrystore.repository.RepositoryException skipped) {
				// the work goes on
			}
			duck.createLink(null, URI.create("http://example.com/c"), null);
			operationsAfterTheFailure.incrementAndGet();
		}));

		assertEquals(0, operationsAfterTheFailure.get(), "an operation in a rollback-only batch must fail");
		assertTrue(isStored(entries.get(0)));
		assertTrue(isStored(entries.get(1)));
		assertTrue(existing.getLocalMetadata().getGraph().contains(null, DCTERMS.TITLE, rm.getValueFactory().createLiteral("kept")),
				"the cleared graph must not be committed");
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
