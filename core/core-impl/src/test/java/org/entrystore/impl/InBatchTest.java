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
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.entrystore.Context;
import org.entrystore.Entry;
import org.entrystore.repository.RepositoryEvent;
import org.entrystore.repository.RepositoryEventObject;
import org.entrystore.repository.RepositoryListener;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

	private boolean isStored(Entry entry) {
		try (RepositoryConnection rc = rm.getRepository().getConnection()) {
			IRI entryIRI = ((EntryImpl) entry).getSesameEntryURI();
			return rc.hasStatement(null, null, null, false, entryIRI);
		}
	}
}
