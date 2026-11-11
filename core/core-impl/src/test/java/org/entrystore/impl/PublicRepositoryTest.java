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

import static org.eclipse.rdf4j.model.util.Statements.statement;
import static org.eclipse.rdf4j.model.util.Values.iri;
import static org.eclipse.rdf4j.model.util.Values.literal;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;

import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Model;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.LinkedHashModel;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.entrystore.Entry;
import org.entrystore.GraphType;
import org.entrystore.PrincipalManager.AccessProperty;
import org.entrystore.repository.config.Settings;
import org.entrystore.repository.util.URISplit;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class PublicRepositoryTest extends AbstractCoreTest {

	/** A valid RDF4J IRI that {@code java.net.URI.create} rejects, so {@code loadIndex} cannot index it. */
	private static final String UNPARSEABLE_IRI = "http://example.com/not a uri";

	private static final IRI TITLE = iri("http://purl.org/dc/terms/title");

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
		assertContextCleared();
	}

	@Test
	public void removeEntries_contextEntryInANativeStore_clearsTheContextAndAllItsEntriesOnly(
			@TempDir Path storeDirectory) {
		rm.getConfiguration().setProperty(Settings.REPOSITORY_PUBLIC_TYPE, "native");
		rm.getConfiguration().setProperty(Settings.REPOSITORY_PUBLIC_PATH, storeDirectory.toUri().toString());
		assertContextCleared();
	}

	private void assertContextCleared() {
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
	public void addEntry_publicLocalGraphEntry_publishesItsResourceGraphAndTheContextGraph() {
		rm.setCheckForAuthorization(false);
		ContextImpl context = newContext();
		EntryImpl graph = newGraphEntry(context, "graph-md", "graph-resource");
		PublicRepository publicRepository = new PublicRepository(rm);
		try {
			assertTrue(holds(publicRepository, resourceGraph(graph), title("graph-resource")));
			assertTrue(holds(publicRepository, contextGraph(context), title("graph-resource")));
			assertTrue(holds(publicRepository, graph.getSesameLocalMetadataURI(), title("graph-md")));
		} finally {
			publicRepository.shutdown();
		}
	}

	@Test
	public void updateEntries_metadataPublicResourcePrivate_publishesOnlyTheMetadata() {
		Entry contextEntry = newPublicContext();
		EntryImpl graph = newGraphEntry((ContextImpl) contextEntry.getResource(), "graph-md", "graph-resource");
		graph.addAllowedPrincipalsFor(AccessProperty.ReadMetadata, pm.getGuestUser().getURI());
		PublicRepository publicRepository = new PublicRepository(rm);
		try {
			publicRepository.updateEntries(Set.of(graph));

			assertTrue(holds(publicRepository, graph.getSesameLocalMetadataURI(), title("graph-md")));
			assertFalse(holdsAnything(publicRepository, resourceGraph(graph)));
			assertFalse(holds(publicRepository, contextGraph(contextEntry), title("graph-resource")));
		} finally {
			publicRepository.shutdown();
		}
	}

	@Test
	public void updateEntries_metadataPrivateResourcePublic_publishesOnlyTheResource() {
		Entry contextEntry = newPublicContext();
		EntryImpl graph = newGraphEntry((ContextImpl) contextEntry.getResource(), "graph-md", "graph-resource");
		graph.addAllowedPrincipalsFor(AccessProperty.ReadResource, pm.getGuestUser().getURI());
		PublicRepository publicRepository = new PublicRepository(rm);
		try {
			publicRepository.updateEntries(Set.of(graph));

			assertFalse(holdsAnything(publicRepository, graph.getSesameLocalMetadataURI()));
			assertFalse(holds(publicRepository, contextGraph(contextEntry), title("graph-md")));
			assertTrue(holds(publicRepository, resourceGraph(graph), title("graph-resource")));
			assertTrue(holds(publicRepository, contextGraph(contextEntry), title("graph-resource")));
		} finally {
			publicRepository.shutdown();
		}
	}

	@Test
	public void updateEntries_resourceReadRevoked_removesTheResourceGraphAndWhatOnlyItStatedFromTheContextGraph() {
		URI guest = pm.getGuestUser().getURI();
		Entry contextEntry = newPublicContext();
		ContextImpl context = (ContextImpl) contextEntry.getResource();
		EntryImpl graph = newGraphEntry(context, "graph-md", "graph-resource", SHARED_TITLE);
		graph.addAllowedPrincipalsFor(AccessProperty.ReadMetadata, guest);
		graph.addAllowedPrincipalsFor(AccessProperty.ReadResource, guest);
		newEntryStating(context, "other", SHARED_TITLE);
		PublicRepository publicRepository = new PublicRepository(rm);
		try {
			assertTrue(holds(publicRepository, resourceGraph(graph), title("graph-resource")),
					"the resource was public");
			graph.removeAllowedPrincipalsFor(AccessProperty.ReadResource, guest);

			publicRepository.updateEntries(Set.of(graph));

			assertFalse(holdsAnything(publicRepository, resourceGraph(graph)));
			assertFalse(holds(publicRepository, contextGraph(context), title("graph-resource")));
			assertTrue(holds(publicRepository, contextGraph(context), title(SHARED_TITLE)),
					"another entry of the context still states the shared title");
			assertTrue(holds(publicRepository, contextGraph(context), title("graph-md")));
		} finally {
			publicRepository.shutdown();
		}
	}

	@Test
	public void updateEntries_contextWithEntriesReadablePerPart_publishesTheReadablePartsOfEach() {
		URI guest = pm.getGuestUser().getURI();
		Entry contextEntry = newPublicContext();
		ContextImpl context = (ContextImpl) contextEntry.getResource();
		EntryImpl metadataOnly = newGraphEntry(context, "md-public", "resource-private");
		EntryImpl resourceOnly = newGraphEntry(context, "md-private", "resource-public");
		PublicRepository publicRepository = new PublicRepository(rm);
		try {
			metadataOnly.addAllowedPrincipalsFor(AccessProperty.ReadMetadata, guest);
			resourceOnly.addAllowedPrincipalsFor(AccessProperty.ReadResource, guest);

			publicRepository.updateEntries(Set.of(contextEntry));

			assertTrue(holds(publicRepository, contextGraph(context), title("md-public")));
			assertFalse(holds(publicRepository, contextGraph(context), title("resource-private")));
			assertFalse(holds(publicRepository, contextGraph(context), title("md-private")));
			assertTrue(holds(publicRepository, contextGraph(context), title("resource-public")));
			assertTrue(holds(publicRepository, resourceGraph(resourceOnly), title("resource-public")));
		} finally {
			publicRepository.shutdown();
		}
	}

	@Test
	public void removeEntries_localGraphEntryLargerThanOneChunk_removesItsResourceGraph() {
		rm.setCheckForAuthorization(false);
		ContextImpl context = newContext();
		String[] titles = new String[2 * PublicRepository.REMOVAL_CHUNK_SIZE + 1];
		for (int i = 0; i < titles.length; i++) {
			titles[i] = "resource-" + i;
		}
		EntryImpl graph = newGraphEntry(context, "graph-md", titles);
		PublicRepository publicRepository = new PublicRepository(rm);
		try (RepositoryConnection rc = publicRepository.getConnection()) {
			assertEquals(titles.length, rc.size(resourceGraph(graph)));
			assertEquals(titles.length + 1, rc.size(contextGraph(context)));

			publicRepository.removeEntries(Set.of(graph));

			assertEquals(0, rc.size(resourceGraph(graph)));
			assertEquals(0, rc.size(contextGraph(context)));
		} finally {
			publicRepository.shutdown();
		}
	}

	@Test
	public void updateEntries_contextMadeNonPublicAndPublicAgain_keepsAndRestoresEntriesByTheirOwnAcl() {
		URI guest = pm.getGuestUser().getURI();
		Entry contextEntry = newPublicContext();
		ContextImpl context = (ContextImpl) contextEntry.getResource();
		EntryImpl ownAcl = newEntryStating(context, "own-acl", SHARED_TITLE);
		ownAcl.addAllowedPrincipalsFor(AccessProperty.ReadMetadata, guest);
		EntryImpl inheriting = newEntryStating(context, "inheriting", SHARED_TITLE);
		PublicRepository publicRepository = new PublicRepository(rm);
		try {
			contextEntry.removeAllowedPrincipalsFor(AccessProperty.ReadResource, guest);
			publicRepository.updateEntries(Set.of(contextEntry));

			assertTrue(holds(publicRepository, contextGraph(context), title("own-acl")));
			assertTrue(holds(publicRepository, contextGraph(context), title(SHARED_TITLE)));
			assertFalse(holds(publicRepository, contextGraph(context), title("inheriting")));
			assertFalse(holdsAnything(publicRepository, inheriting.getSesameLocalMetadataURI()));

			contextEntry.addAllowedPrincipalsFor(AccessProperty.ReadResource, guest);
			publicRepository.updateEntries(Set.of(contextEntry));

			assertTrue(holds(publicRepository, contextGraph(context), title("inheriting")));
			assertTrue(holds(publicRepository, inheriting.getSesameLocalMetadataURI(), title("inheriting")));
			assertTrue(holds(publicRepository, contextGraph(context), title("own-acl")));
		} finally {
			publicRepository.shutdown();
		}
	}

	@Test
	public void updateEntries_twoContextsMadeNonPublicInOneBatch_clearsBothAndLeavesAThirdAlone() {
		URI guest = pm.getGuestUser().getURI();
		Entry first = newPublicContext();
		Entry second = newPublicContext();
		Entry third = newPublicContext();
		EntryImpl inFirst = newEntryStating((ContextImpl) first.getResource(), "first", SHARED_TITLE);
		EntryImpl inSecond = newEntryStating((ContextImpl) second.getResource(), "second", SHARED_TITLE);
		newEntryStating((ContextImpl) third.getResource(), "third", SHARED_TITLE);
		PublicRepository publicRepository = new PublicRepository(rm);
		try {
			first.removeAllowedPrincipalsFor(AccessProperty.ReadResource, guest);
			second.removeAllowedPrincipalsFor(AccessProperty.ReadResource, guest);

			publicRepository.updateEntries(Set.of(first, second));

			assertFalse(holdsAnything(publicRepository, contextGraph(first)));
			assertFalse(holdsAnything(publicRepository, inFirst.getSesameLocalMetadataURI()));
			assertFalse(holdsAnything(publicRepository, contextGraph(second)));
			assertFalse(holdsAnything(publicRepository, inSecond.getSesameLocalMetadataURI()));
			assertTrue(holds(publicRepository, contextGraph(third), title("third")));
		} finally {
			publicRepository.shutdown();
		}
	}

	@Test
	public void removeEntries_contextWithAnotherResourceURI_clearsItsEntriesButNotTheGraphItsResourceURINames() {
		rm.setCheckForAuthorization(false);
		Entry contextEntry = cm.createResource(null, GraphType.Context, null, null);
		ContextImpl context = (ContextImpl) contextEntry.getResource();
		ContextImpl other = newContext();
		EntryImpl a = newEntryStating(context, "a", SHARED_TITLE);
		newEntryStating(other, "in-other", SHARED_TITLE);
		Entry renamed = withResourceURI(contextEntry, other.getURI());
		PublicRepository publicRepository = new PublicRepository(rm);
		try {
			publicRepository.removeEntries(Set.of(renamed));

			assertFalse(holdsAnything(publicRepository, a.getSesameLocalMetadataURI()));
			assertTrue(holds(publicRepository, contextGraph(other), title("in-other")),
					"the graph the resource URI names is another context's");
		} finally {
			publicRepository.shutdown();
		}
	}

	@Test
	public void updateEntries_contextWithAnotherResourceURI_publishesItsEntriesAgainAndLeavesThatGraphAlone() {
		rm.setCheckForAuthorization(false);
		Entry contextEntry = cm.createResource(null, GraphType.Context, null, null);
		ContextImpl context = (ContextImpl) contextEntry.getResource();
		ContextImpl other = newContext();
		EntryImpl a = newEntryStating(context, "a", SHARED_TITLE);
		newEntryStating(other, "in-other", SHARED_TITLE);
		Entry renamed = withResourceURI(contextEntry, other.getURI());
		PublicRepository publicRepository = new PublicRepository(rm);
		try {
			removeTitle(a, "a");

			publicRepository.updateEntries(Set.of(renamed));

			assertTrue(holds(publicRepository, contextGraph(other), title("in-other")),
					"the graph the resource URI names is another context's");
			assertTrue(holds(publicRepository, a.getSesameLocalMetadataURI(), title(SHARED_TITLE)));
			assertFalse(holds(publicRepository, contextGraph(context), title("a")),
					"each entry took its statements out of the context graph");
		} finally {
			publicRepository.shutdown();
		}
	}

	@Test
	public void removeEntries_localEntryOfGraphTypeContextOutsideContexts_leavesTheContextWithItsIdAlone() {
		rm.setCheckForAuthorization(false);
		ContextImpl context = newContext();
		Entry otherEntry = cm.createResource(null, GraphType.Context, null, null);
		EntryImpl inOther = newEntryStating((ContextImpl) otherEntry.getResource(), "in-other", SHARED_TITLE);
		Entry sameId = context.createResource(otherEntry.getId(), GraphType.Graph, null, null);
		Entry contextLike = spy(sameId);
		doReturn(GraphType.Context).when(contextLike).getGraphType();
		PublicRepository publicRepository = new PublicRepository(rm);
		try {
			publicRepository.removeEntries(Set.of(contextLike));

			assertTrue(holds(publicRepository, inOther.getSesameLocalMetadataURI(), title("in-other")),
					"only an entry of _contexts stands for a context");
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

	private ContextImpl newContext() {
		return (ContextImpl) cm.createResource(null, GraphType.Context, null, null).getResource();
	}

	/** A context the guest can read, created as admin with authorization checks on. */
	private Entry newPublicContext() {
		rm.setCheckForAuthorization(true);
		pm.setAuthenticatedUserURI(pm.getAdminUser().getURI());
		Entry contextEntry = cm.createResource(null, GraphType.Context, null, null);
		contextEntry.addAllowedPrincipalsFor(AccessProperty.ReadResource, pm.getGuestUser().getURI());
		return contextEntry;
	}

	/** A local Graph entry stating {@code metadataTitle} in its metadata and the other titles in its resource. */
	private EntryImpl newGraphEntry(ContextImpl context, String metadataTitle, String... resourceTitles) {
		EntryImpl entry = (EntryImpl) context.createResource(null, GraphType.Graph, null, null);
		Model resource = new LinkedHashModel();
		for (String resourceTitle : resourceTitles) {
			resource.add(SUBJECT, TITLE, literal(resourceTitle));
		}
		((RDFResource) entry.getResource()).setGraph(resource);
		try (RepositoryConnection rc = rm.getRepository().getConnection()) {
			rc.add(SUBJECT, TITLE, literal(metadataTitle), entry.getSesameLocalMetadataURI());
		}
		return entry;
	}

	/** The context entry as the public repository would see it if its resource URI were {@code resourceURI}. */
	private static Entry withResourceURI(Entry contextEntry, URI resourceURI) {
		Entry renamed = spy(contextEntry);
		doReturn(resourceURI).when(renamed).getResourceURI();
		return renamed;
	}

	private static IRI resourceGraph(EntryImpl entry) {
		return iri(entry.getResourceURI().toString());
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

	private static IRI contextGraph(Entry contextEntry) {
		return iri(contextEntry.getResourceURI().toString());
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
