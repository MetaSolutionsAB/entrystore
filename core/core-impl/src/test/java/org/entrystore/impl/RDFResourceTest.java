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

import static org.eclipse.rdf4j.model.util.Values.iri;
import static org.eclipse.rdf4j.model.util.Values.literal;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.eclipse.rdf4j.model.Model;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.model.impl.LinkedHashModel;
import org.entrystore.Entry;
import org.entrystore.GraphType;
import org.entrystore.repository.RepositoryEvent;
import org.entrystore.repository.RepositoryEventObject;
import org.entrystore.repository.RepositoryListener;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

public class RDFResourceTest extends AbstractCoreTest {

	@Test
	public void setGraph_firesResourceUpdatedForItsEntryAfterTheGraphIsCommitted() {
		rm.setCheckForAuthorization(false);
		Entry entry = newGraphEntry();
		List<Set<Value>> seen = recordTitlesOnResourceUpdated(entry);
		Model graph = titled("new title");

		((RDFResource) entry.getResource()).setGraph(graph);

		assertEquals(List.of(Set.of(literal("new title"))), seen);
	}

	@Test
	public void setGraph_failingSave_firesNoEventAndKeepsTheGraph() {
		rm.setCheckForAuthorization(false);
		Entry entry = newGraphEntry();
		RDFResource resource = (RDFResource) entry.getResource();
		resource.setGraph(titled("old title"));
		List<Set<Value>> seen = recordTitlesOnResourceUpdated(entry);

		assertThrows(org.entrystore.repository.RepositoryException.class, () -> resource.setGraph(null));

		assertTrue(seen.isEmpty());
		assertEquals(Set.of(literal("old title")), resource.getGraph().objects());
	}

	private Entry newGraphEntry() {
		ContextImpl context = (ContextImpl) cm.createResource(null, GraphType.Context, null, null).getResource();
		return context.createResource(null, GraphType.Graph, null, null);
	}

	/** The titles in the entry's resource graph as a ResourceUpdated listener reads them. */
	private List<Set<Value>> recordTitlesOnResourceUpdated(Entry entry) {
		List<Set<Value>> seen = new ArrayList<>();
		rm.registerListener(new RepositoryListener() {
			@Override
			public void repositoryUpdated(RepositoryEventObject eventObject) {
				if (eventObject.getSource() == entry) {
					seen.add(((RDFResource) entry.getResource()).getGraph().objects());
				}
			}
		}, RepositoryEvent.ResourceUpdated);
		return seen;
	}

	private static Model titled(String title) {
		Model graph = new LinkedHashModel();
		graph.add(iri("http://example.com/subject"), iri("http://purl.org/dc/terms/title"), literal(title));
		return graph;
	}

	@Disabled("To be implemented")
	@Test
	public void testGetGraph() throws Exception {
		// TODO
	}

	@Disabled("To be implemented")
	@Test
	public void testSetGraph() throws Exception {
		// TODO
	}

	@Disabled("To be implemented")
	@Test
	public void testRemove() throws Exception {
		// TODO
	}
}
