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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.util.Set;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.entrystore.Entry;
import org.entrystore.GraphType;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

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
