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
import org.eclipse.rdf4j.model.impl.LinkedHashModel;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.model.vocabulary.DCTERMS;
import org.entrystore.AuthorizationException;
import org.entrystore.ContextManager;
import org.entrystore.Entry;
import org.entrystore.GraphType;
import org.entrystore.PrincipalManager.AccessProperty;
import org.entrystore.repository.CorruptEntryException;
import org.entrystore.repository.RepositoryException;
import org.entrystore.repository.RepositoryManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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
		Entry reference = context.createLinkReference(null, URI.create("http://example.com/resource"),
				referenced.getLocalMetadataURI(), null);

		Model graph = reference.getCachedExternalMetadata().getGraph();

		assertTrue(graph.contains(null, DCTERMS.TITLE, vf.createLiteral("referenced title")));
	}

	@Test
	public void loadingLinkReferenceWhoseExternalMetadataIsItsOwnMetadataDoesNotRecurse() {
		Entry placeholder = context.createResource(null, GraphType.None, null, null);
		Entry reference = context.createLinkReference(null, URI.create("http://example.com/resource"),
				placeholder.getLocalMetadataURI(), null);
		setTitle(reference, "own title");
		// Such an entry can no longer be created through the API, so the self-reference is written to the store
		replaceExternalMetadataInStore(reference, reference.getLocalMetadataURI());
		context.softCache.remove(reference);

		Entry loaded = context.getByEntryURI(reference.getEntryURI());
		// Evicted again, so that resolving the referenced entry has to load it as well
		context.softCache.remove(loaded);

		assertNotNull(loaded);
		assertEquals(reference.getLocalMetadataURI(), loaded.getExternalMetadataURI());
		Model cachedExternalMetadata = loaded.getCachedExternalMetadata().getGraph();
		assertTrue(cachedExternalMetadata.contains(null, DCTERMS.TITLE, vf.createLiteral("own title")));
	}

	@Test
	public void getGraphReturnsEmptyGraphWhenExternalMetadataURIIsNoEntryURI() {
		// A URI of this repository that does not denote an entry, since it has no entry ID. Such a URI is rejected
		// when it is set (ENTRYSTORE-1182), so it can only be in the store from before, and is written there.
		URI noEntryURI = URI.create(rm.getRepositoryURL() + context.getEntry().getId() + "/entry");
		Entry reference = context.createLinkReference(null, URI.create("http://example.com/resource"),
				URI.create("http://example.com/metadata"), null);
		replaceExternalMetadataInStore(reference, noEntryURI);
		context.softCache.remove(reference);
		Entry loaded = context.get(reference.getId());

		Model graph = loaded.getCachedExternalMetadata().getGraph();

		assertTrue(graph.isEmpty());
	}

	@Test
	public void loadingLinkReferencesWhoseExternalMetadataRefersToEachOtherDoesNotRecurse() {
		Entry placeholder = context.createResource(null, GraphType.None, null, null);
		Entry first = context.createLinkReference(null, URI.create("http://example.com/first"),
				placeholder.getLocalMetadataURI(), null);
		Entry second = context.createLinkReference(null, URI.create("http://example.com/second"),
				first.getLocalMetadataURI(), null);
		first.setExternalMetadataURI(second.getLocalMetadataURI());
		setTitle(second, "second title");
		context.softCache.remove(first);
		context.softCache.remove(second);

		Entry loaded = context.getByEntryURI(first.getEntryURI());

		assertNotNull(loaded);
		Model cachedExternalMetadata = loaded.getCachedExternalMetadata().getGraph();
		assertTrue(cachedExternalMetadata.contains(null, DCTERMS.TITLE, vf.createLiteral("second title")));
	}

	@Test
	public void getGraphReturnsEmptyGraphWhenReferencedEntryIsCorrupt() {
		Entry referenced = context.createResource(null, GraphType.None, null, null);
		setTitle(referenced, "referenced title");
		Entry reference = context.createLinkReference(null, URI.create("http://example.com/resource"),
				referenced.getLocalMetadataURI(), null);
		setTitle(reference, "own title");
		corrupt(referenced);

		Model graph = reference.getCachedExternalMetadata().getGraph();

		assertTrue(graph.isEmpty());
		assertTrue(reference.getMetadataGraph().contains(null, DCTERMS.TITLE, vf.createLiteral("own title")));
	}

	@Test
	public void getGraphReturnsEmptyGraphWhenTheContextEntryOfTheReferencedEntryIsCorrupt() {
		Entry otherContextEntry = cm.createResource(null, GraphType.Context, null, null);
		Entry referenced = ((ContextImpl) otherContextEntry.getResource()).createResource(null, GraphType.None, null,
				null);
		setTitle(referenced, "referenced title");
		Entry reference = context.createLinkReference(null, URI.create("http://example.com/resource"),
				referenced.getLocalMetadataURI(), null);
		corrupt(otherContextEntry);
		evictFromSoftCache(referenced);

		Model graph = reference.getCachedExternalMetadata().getGraph();

		assertTrue(graph.isEmpty());
	}

	@Test
	public void getGraphReturnsEmptyGraphWhenReferencedEntryDoesNotExist() {
		Entry referenced = context.createResource(null, GraphType.None, null, null);
		Entry reference = context.createLinkReference(null, URI.create("http://example.com/resource"),
				referenced.getLocalMetadataURI(), null);
		context.remove(referenced.getEntryURI());

		Model graph = reference.getCachedExternalMetadata().getGraph();

		assertTrue(graph.isEmpty());
	}

	@Test
	public void getGraphPropagatesAnAuthorizationFailureOfTheReferencedEntry() throws Exception {
		AuthorizationException denied = new AuthorizationException(null, null, AccessProperty.ReadMetadata);

		assertSame(denied, assertThrows(AuthorizationException.class,
				() -> wrapperWhoseReferencedEntryLookupFailsWith(denied).getGraph()));
	}

	@Test
	public void getGraphPropagatesAFailureOfTheStore() throws Exception {
		RepositoryException storeFailure = new RepositoryException("Failed to connect to Repository");

		assertSame(storeFailure, assertThrows(RepositoryException.class,
				() -> wrapperWhoseReferencedEntryLookupFailsWith(storeFailure).getGraph()));
	}

	@Test
	public void getGraphPropagatesAFailureCausedByAnotherCorruptEntry() throws Exception {
		// As when the access check on the referenced entry loads a corrupt principal
		URI principalEntryURI = URI.create(rm.getRepositoryURL() + "_principals/entry/5");
		RepositoryException principalCorrupt = new RepositoryException("Unable to load entry " + principalEntryURI,
				new CorruptEntryException(principalEntryURI, "Entry graph <" + principalEntryURI + "> is corrupt"));

		assertSame(principalCorrupt, assertThrows(RepositoryException.class,
				() -> wrapperWhoseReferencedEntryLookupFailsWith(principalCorrupt).getGraph()));
	}

	/**
	 * @return a wrapper of a reference to an entry that is not cached, and whose lookup fails with the given failure
	 */
	private LocalMetadataWrapper wrapperWhoseReferencedEntryLookupFailsWith(RuntimeException failure)
			throws Exception {
		URI externalMetadataURI = URI.create(rm.getRepositoryURL() + "2/metadata/1");
		ContextManager contextManager = mock(ContextManager.class);
		when(contextManager.getEntry(URI.create(rm.getRepositoryURL() + "2/entry/1"))).thenThrow(failure);
		RepositoryManager repositoryManager = mock(RepositoryManager.class);
		when(repositoryManager.getContextManager()).thenReturn(contextManager);
		when(repositoryManager.getRepositoryURL()).thenReturn(rm.getRepositoryURL());
		ContextImpl referenceContext = mock(ContextImpl.class);
		when(referenceContext.getSoftCache()).thenReturn(mock(SoftCache.class));
		Entry reference = mock(Entry.class);
		when(reference.getContext()).thenReturn(referenceContext);
		when(reference.getRepositoryManager()).thenReturn(repositoryManager);
		when(reference.getExternalMetadataURI()).thenReturn(externalMetadataURI);
		return new LocalMetadataWrapper(reference);
	}

	/**
	 * Makes loading the entry fail with a {@link org.entrystore.repository.CorruptEntryException}.
	 */
	private void corrupt(Entry entry) {
		removeFromEntryGraph(entry.getEntryURI(), RepositoryProperties.resource);
		evictFromSoftCache(entry);
	}

	private static void setTitle(Entry entry, String title) {
		Model graph = new LinkedHashModel();
		graph.add(vf.createIRI(entry.getResourceURI().toString()), DCTERMS.TITLE, vf.createLiteral(title));
		entry.getLocalMetadata().setGraph(graph);
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
