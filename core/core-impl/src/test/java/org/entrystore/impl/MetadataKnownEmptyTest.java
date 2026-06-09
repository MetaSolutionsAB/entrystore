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

import org.eclipse.rdf4j.model.Model;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.LinkedHashModel;
import org.eclipse.rdf4j.model.vocabulary.DCTERMS;
import org.entrystore.Context;
import org.entrystore.Entry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link MetadataImpl} skips removing the old graph when it knows the metadata graph is empty. A write must still
 * replace, never merge with, whatever the store holds, also after a batch that rolled back.
 */
public class MetadataKnownEmptyTest extends AbstractCoreTest {

	private Context duck;

	@BeforeEach
	public void setUp() {
		super.setUp();
		pm.setAuthenticatedUserURI(pm.getAdminUser().getURI());
		duck = cm.getContext("duck");
	}

	@Test
	public void theSecondWriteToANewEntryReplacesTheFirst() {
		Entry entry = newEntry();

		entry.getLocalMetadata().setGraph(titleOf(entry, "first"));
		entry.getLocalMetadata().setGraph(titleOf(entry, "second"));

		assertEquals(Set.of("second"), titlesOf(entry));
	}

	@Test
	public void aWriteAfterAnEmptyGraphIsReplacedByTheNextWrite() {
		Entry entry = newEntry();

		entry.getLocalMetadata().setGraph(new LinkedHashModel());
		entry.getLocalMetadata().setGraph(titleOf(entry, "first"));
		entry.getLocalMetadata().setGraph(titleOf(entry, "second"));

		assertEquals(Set.of("second"), titlesOf(entry));
	}

	@Test
	public void theSecondWriteToAnEntryCreatedInTheSameBatchReplacesTheFirst() {
		List<Entry> entries = new ArrayList<>();

		rm.inBatch(() -> {
			Entry entry = newEntry();
			entries.add(entry);
			entry.getLocalMetadata().setGraph(titleOf(entry, "first"));
			entry.getLocalMetadata().setGraph(titleOf(entry, "second"));
		});

		assertEquals(Set.of("second"), titlesOf(entries.getFirst()));
	}

	@Test
	public void anEmptyWriteOfARolledBackBatchDoesNotMakeTheNextWriteKeepTheOldGraph() {
		Entry entry = newEntry();
		entry.getLocalMetadata().setGraph(titleOf(entry, "old"));

		assertThrows(IllegalStateException.class, () -> rm.inBatch(() -> {
			entry.getLocalMetadata().setGraph(new LinkedHashModel());
			throw new IllegalStateException("boom");
		}));
		assertEquals(Set.of("old"), titlesOf(entry), "the rollback restores the old graph");

		entry.getLocalMetadata().setGraph(titleOf(entry, "new"));

		assertEquals(Set.of("new"), titlesOf(entry));
	}

	@Test
	public void theLastWriteOfABatchDecidesWhetherTheGraphIsKnownEmpty() {
		Entry entry = newEntry();
		entry.getLocalMetadata().setGraph(titleOf(entry, "old"));

		rm.inBatch(() -> {
			entry.getLocalMetadata().setGraph(new LinkedHashModel());
			entry.getLocalMetadata().setGraph(titleOf(entry, "batched"));
		});
		entry.getLocalMetadata().setGraph(titleOf(entry, "new"));

		assertEquals(Set.of("new"), titlesOf(entry));
	}

	@Test
	public void aWriteAfterACheckpointThatCommittedAnEmptyGraphIsReplacedByTheNextWrite() {
		Entry entry = newEntry();
		entry.getLocalMetadata().setGraph(titleOf(entry, "old"));
		rm.setBatchMaxOperations(1);

		rm.inBatch(() -> {
			entry.getLocalMetadata().setGraph(new LinkedHashModel());
			// reaches the checkpoint first, which commits the empty graph
			entry.getLocalMetadata().setGraph(titleOf(entry, "batched"));
		});
		entry.getLocalMetadata().setGraph(titleOf(entry, "new"));

		assertEquals(Set.of("new"), titlesOf(entry));
	}

	private Entry newEntry() {
		return duck.createLink(null, URI.create("http://example.com/known-empty"), null);
	}

	private Model titleOf(Entry entry, String title) {
		ValueFactory vf = rm.getValueFactory();
		Model graph = new LinkedHashModel();
		graph.add(vf.createIRI(entry.getResourceURI().toString()), DCTERMS.TITLE, vf.createLiteral(title));
		return graph;
	}

	private Set<String> titlesOf(Entry entry) {
		return entry.getLocalMetadata().getGraph().filter(null, DCTERMS.TITLE, null).objects().stream()
				.map(Value::stringValue)
				.collect(Collectors.toSet());
	}

}
