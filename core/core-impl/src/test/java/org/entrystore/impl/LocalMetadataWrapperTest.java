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
import org.eclipse.rdf4j.model.impl.LinkedHashModel;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.model.vocabulary.DCTERMS;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.entrystore.Entry;
import org.entrystore.GraphType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class LocalMetadataWrapperTest extends AbstractCoreTest {

	private static final SimpleValueFactory vf = SimpleValueFactory.getInstance();

	private ContextImpl context;

	@BeforeEach
	public void setUp() {
		super.setUp();
		rm.setCheckForAuthorization(false);

		Entry contextEntry = cm.createResource(null, GraphType.Context, null, null);
		context = (ContextImpl) contextEntry.getResource();
	}

	@Test
	public void getGraphReturnsLocalMetadataOfReferencedEntry() {
		Entry referenced = context.createResource(null, GraphType.None, null, null);
		setTitle(referenced, "referenced title");
		Entry reference = context.createLinkReference(null, URI.create("http://example.com/resource"), referenced.getLocalMetadataURI(), null);

		Model graph = reference.getCachedExternalMetadata().getGraph();

		assertTrue(graph.contains(null, DCTERMS.TITLE, vf.createLiteral("referenced title")));
	}

	@Test
	public void loadingLinkReferenceWhoseExternalMetadataIsItsOwnMetadataDoesNotRecurse() {
		Entry placeholder = context.createResource(null, GraphType.None, null, null);
		Entry reference = context.createLinkReference(null, URI.create("http://example.com/resource"), placeholder.getLocalMetadataURI(), null);
		setTitle(reference, "own title");
		// Such an entry can no longer be created through the API, so the self-reference is written to the store
		replaceExternalMetadataInStore(reference, reference.getLocalMetadataURI());
		context.softCache.remove(reference);

		Entry loaded = context.getByEntryURI(reference.getEntryURI());

		assertNotNull(loaded);
		assertEquals(reference.getLocalMetadataURI(), loaded.getExternalMetadataURI());
		assertTrue(loaded.getCachedExternalMetadata().getGraph().contains(null, DCTERMS.TITLE, vf.createLiteral("own title")));
	}

	@Test
	public void loadingLinkReferencesWhoseExternalMetadataRefersToEachOtherDoesNotRecurse() {
		Entry placeholder = context.createResource(null, GraphType.None, null, null);
		Entry first = context.createLinkReference(null, URI.create("http://example.com/first"), placeholder.getLocalMetadataURI(), null);
		Entry second = context.createLinkReference(null, URI.create("http://example.com/second"), first.getLocalMetadataURI(), null);
		first.setExternalMetadataURI(second.getLocalMetadataURI());
		setTitle(second, "second title");
		context.softCache.remove(first);
		context.softCache.remove(second);

		Entry loaded = context.getByEntryURI(first.getEntryURI());

		assertNotNull(loaded);
		assertTrue(loaded.getCachedExternalMetadata().getGraph().contains(null, DCTERMS.TITLE, vf.createLiteral("second title")));
	}

	private static void setTitle(Entry entry, String title) {
		Model graph = new LinkedHashModel();
		graph.add(vf.createIRI(entry.getResourceURI().toString()), DCTERMS.TITLE, vf.createLiteral(title));
		entry.getLocalMetadata().setGraph(graph);
	}

	private void replaceExternalMetadataInStore(Entry entry, URI externalMetadataURI) {
		IRI entryIRI = vf.createIRI(entry.getEntryURI().toString());
		try (RepositoryConnection rc = rm.getRepository().getConnection()) {
			rc.remove(entryIRI, RepositoryProperties.externalMetadata, null, entryIRI);
			rc.add(entryIRI, RepositoryProperties.externalMetadata, vf.createIRI(externalMetadataURI.toString()), entryIRI);
		}
	}

	@Disabled("To be implemented")
	@Test
	public void testGetResourceURI() throws Exception {
		// TODO
	}

	@Disabled("To be implemented")
	@Test
	public void testGetURI() throws Exception {
		// TODO
	}

	@Disabled("To be implemented")
	@Test
	public void testIsCached() throws Exception {
		// TODO
	}

	@Disabled("To be implemented")
	@Test
	public void testSetGraph() throws Exception {
		// TODO
	}
}
