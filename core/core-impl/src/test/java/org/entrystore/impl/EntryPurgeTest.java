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
import org.eclipse.rdf4j.model.Resource;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.LinkedHashModel;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.model.vocabulary.DCTERMS;
import org.eclipse.rdf4j.model.vocabulary.RDF;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.RepositoryResult;
import org.entrystore.Data;
import org.entrystore.Entry;
import org.entrystore.GraphType;
import org.entrystore.List;
import org.entrystore.PrincipalManager.AccessProperty;
import org.entrystore.ResourceType;
import org.entrystore.repository.RepositoryEvent;
import org.entrystore.repository.RepositoryEventObject;
import org.entrystore.repository.RepositoryListener;
import org.entrystore.repository.config.Settings;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.eclipse.rdf4j.model.util.Values.iri;
import static org.eclipse.rdf4j.model.util.Values.literal;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ENTRYSTORE-1273. {@link EntryPurge} removes entries as {@code ContextImpl.remove} does, but reads them before taking
 * the repository monitor and removes them by exact quad under it.
 */
public class EntryPurgeTest extends AbstractCoreTest {

	private static final ValueFactory VF = SimpleValueFactory.getInstance();

	@BeforeEach
	public void setUp() {
		super.setUp();
		rm.setCheckForAuthorization(false);
	}

	@Test
	public void purge_removesTheEntriesGraphsAndIndexTriples() {
		ContextImpl context = newContext();
		Entry link = context.createLink(null, URI.create("https://slashdot.org/"), null);
		link.getLocalMetadata().setGraph(title(link, "a link"));

		purge(context, link);

		assertFalse(hasGraph(link.getEntryURI()));
		assertFalse(hasGraph(link.getLocalMetadataURI()));
		assertFalse(hasGraph(link.getRelationURI()));
		assertFalse(isIndexed(context, link));
		assertNull(context.getByEntryURI(link.getEntryURI()));
		assertTrue(link.isDeleted(), "an instance held elsewhere reports the entry removed");
	}

	@Test
	public void purge_takesTheRemovedEntryOutOfTheIndexesWithoutARescan() {
		ContextImpl context = newContext();
		URI shared = URI.create("https://slashdot.org/");
		Entry removed = context.createLink(null, shared, null);
		Entry kept = context.createLink(null, shared, null);
		assertEquals(2, context.getByResourceURI(shared).size(), "precondition: the indexes are loaded");
		AtomicInteger scans = new AtomicInteger();
		context.indexScanHookForTests = scans::incrementAndGet;

		purge(context, removed);

		assertEquals(Set.of(kept.getEntryURI()), context.getByResourceURI(shared).stream().map(Entry::getEntryURI)
				.collect(Collectors.toSet()));
		assertEquals(0, scans.get(), "a tree removal purges once per chunk, which must not make every read rescan");
	}

	@Test
	public void purge_takesARemovedReferenceOutOfTheExternalMetadataIndex() {
		ContextImpl context = newContext();
		URI metadata = URI.create("https://example.com/metadata");
		Entry removed = context.createReference(null, URI.create("https://example.com/a"), metadata, null);
		Entry kept = context.createReference(null, URI.create("https://example.com/b"), metadata, null);
		assertEquals(2, context.getByExternalMdURI(metadata).size(), "precondition: the indexes are loaded");
		AtomicInteger scans = new AtomicInteger();
		context.indexScanHookForTests = scans::incrementAndGet;

		purge(context, removed);

		assertEquals(Set.of(kept.getEntryURI()), context.getByExternalMdURI(metadata).stream()
				.map(Entry::getEntryURI).collect(Collectors.toSet()));
		assertEquals(0, scans.get());
	}

	@Test
	public void purge_ofAnEntryThatCannotBeLoaded_dropsTheIndexes() {
		ContextImpl context = newContext();
		URI resource = URI.create("https://slashdot.org/");
		Entry link = context.createLink(null, resource, null);
		assertEquals(1, context.getByResourceURI(resource).size(), "precondition: the indexes are loaded");
		removeFromEntryGraph(link.getEntryURI(), RepositoryProperties.resource);
		evictFromSoftCache(link);
		AtomicInteger scans = new AtomicInteger();
		context.indexScanHookForTests = scans::incrementAndGet;

		purge(context, link);

		assertEquals(Set.of(), context.getByResourceURI(resource));
		assertEquals(1, scans.get(), "without the entry there is no resource URI to take out, so the indexes are "
				+ "rebuilt");
	}

	@Test
	public void purge_ofAnEntryWithAResourceIRIJavaNetURIRejects_dropsTheIndexes() {
		ContextImpl context = newContext();
		URI resource = URI.create("https://slashdot.org/");
		Entry link = context.createLink(null, resource, null);
		assertEquals(1, context.getByResourceURI(resource).size(), "precondition: the indexes are loaded");
		// the entry still loads, as its resource IRI is only parsed when asked for
		removeFromEntryGraph(link.getEntryURI(), RepositoryProperties.resource);
		IRI entryGraph = iri(link.getEntryURI().toString());
		try (RepositoryConnection rc = rm.getRepository().getConnection()) {
			// Values.iri validates the IRI; the store, as SimpleValueFactory, does not
			rc.add(entryGraph, RepositoryProperties.resource,
					SimpleValueFactory.getInstance().createIRI("https://example.com/a b"), entryGraph);
		}
		evictFromSoftCache(link);
		AtomicInteger scans = new AtomicInteger();
		context.indexScanHookForTests = scans::incrementAndGet;

		purge(context, link);

		assertNull(context.getByEntryURI(link.getEntryURI()));
		assertEquals(Set.of(), context.getByResourceURI(resource));
		assertEquals(1, scans.get(), "the resource URI cannot be taken out, so the indexes are rebuilt");
	}

	@Test
	public void purge_leavesAnEntryOfAnotherContextAlone() {
		ContextImpl context = newContext();
		Entry foreign = newContext().createLink(null, URI.create("https://slashdot.org/"), null);
		foreign.getLocalMetadata().setGraph(title(foreign, "a link of another context"));

		purge(context, foreign);

		assertTrue(hasGraph(foreign.getEntryURI()));
		assertTrue(hasGraph(foreign.getLocalMetadataURI()));
		assertFalse(foreign.isDeleted());
	}

	@Test
	public void purge_deletesTheDataFileOnceCommitted(@TempDir Path dataDir) throws Exception {
		rm.getConfiguration().setProperty(Settings.DATA_FOLDER, dataDir.toString());
		ContextImpl context = newContext();
		Entry data = context.createResource(null, GraphType.None, ResourceType.InformationResource, null);
		((Data) data.getResource()).setData(new ByteArrayInputStream("content".getBytes(UTF_8)));
		File file = dataDir.resolve(context.id).resolve(data.getId()).toFile();
		assertTrue(file.exists(), "precondition: the entry has a file");

		purge(context, data);

		assertFalse(file.exists());
	}

	@Test
	public void purge_stampsTheContextModified() throws Exception {
		ContextImpl context = newContext();
		Entry link = context.createLink(null, URI.create("https://slashdot.org/"), null);
		Date before = context.getEntry().getModifiedDate();
		// past the millisecond of the modified date read before
		Thread.sleep(10);

		purge(context, link);

		assertTrue(context.getEntry().getModifiedDate().after(before));
	}

	@Test
	public void purge_leavesEverythingAsItWasWhenRolledBack(@TempDir Path dataDir) throws Exception {
		rm.getConfiguration().setProperty(Settings.DATA_FOLDER, dataDir.toString());
		ContextImpl context = newContext();
		Entry list = context.createResource(null, GraphType.List, null, null);
		Entry data = context.createResource(null, GraphType.None, ResourceType.InformationResource,
				list.getResourceURI());
		((Data) data.getResource()).setData(new ByteArrayInputStream("content".getBytes(UTF_8)));
		// a member that remains, so that the apply stamps the list modified
		Entry kept = context.createLink(null, URI.create("https://slashdot.org/"), list.getResourceURI());
		File file = dataDir.resolve(context.id).resolve(data.getId()).toFile();
		Date contextModified = context.getEntry().getModifiedDate();
		Date listModified = list.getModifiedDate();
		EntryPurge purge = EntryPurge.plan(context, iris(data));
		// past the millisecond of the modified dates, which the apply stamps in memory
		Thread.sleep(10);

		synchronized (rm.getRepository()) {
			try (RepositoryConnection rc = rm.getRepository().getConnection()) {
				rc.begin();
				purge.refresh(rc);
				purge.apply(rc);
				purge.rollBack(rc);
			}
		}

		assertTrue(hasGraph(data.getEntryURI()));
		assertTrue(isIndexed(context, data));
		assertFalse(data.isDeleted());
		assertTrue(file.exists());
		assertEquals(java.util.List.of(data.getEntryURI(), kept.getEntryURI()),
				((List) list.getResource()).getChildren());
		assertEquals(contextModified, context.getEntry().getModifiedDate());
		assertEquals(listModified, list.getModifiedDate());
	}

	@Test
	public void purge_prunesTheEntryFromAListThatRemains() {
		ContextImpl context = newContext();
		Entry list = context.createResource(null, GraphType.List, null, null);
		Entry removed = context.createLink(null, URI.create("https://slashdot.org/"), list.getResourceURI());
		Entry kept = context.createLink(null, URI.create("https://digg.com/"), list.getResourceURI());

		purge(context, removed);

		assertEquals(java.util.List.of(kept.getEntryURI()), ((List) list.getResource()).getChildren());
		assertEquals(Set.of(statement(list.getResourceURI(), iri(RDF.NAMESPACE + "_1"), kept.getEntryURI())),
				positionsOf(list), "the remaining member moves up to the first position");
	}

	@Test
	public void purge_ofAList_takesItOutOfTheRelationsOfAMemberThatRemains() {
		ContextImpl context = newContext();
		Entry removedList = context.createResource(null, GraphType.List, null, null);
		Entry otherList = context.createResource(null, GraphType.List, null, null);
		Entry member = context.createLink(null, URI.create("https://slashdot.org/"), removedList.getResourceURI());
		((List) otherList.getResource()).addChild(member.getEntryURI());
		java.util.List<URI> updated = recordEvents(RepositoryEvent.EntryUpdated, () -> purge(context, removedList));

		assertNotNull(context.getByEntryURI(member.getEntryURI()));
		assertEquals(Set.of(otherList.getResourceURI()),
				((EntryImpl) context.getByEntryURI(member.getEntryURI())).getReferringListsInSameContext());
		assertTrue(updated.contains(member.getEntryURI()), "the member's change is published");
	}

	@Test
	public void purge_removesARelationCopyNoRemainingGraphStates() {
		ContextImpl context = newContext();
		Entry target = local(newContext());
		Entry source = local(context);
		source.getLocalMetadata().setGraph(relation(source, target));
		assertTrue(hasCopy(source, target), "precondition: the target holds a copy of the relation");

		purge(context, source);

		assertFalse(hasCopy(source, target));
	}

	@Test
	public void purge_keepsARelationCopyARemainingGraphStillStates() {
		ContextImpl context = newContext();
		Entry target = local(newContext());
		Entry source = local(context);
		Entry restating = local(context);
		source.getLocalMetadata().setGraph(relation(source, target));
		restating.getLocalMetadata().setGraph(relation(source, target));
		assertTrue(hasCopy(source, target), "precondition: the target holds a copy of the relation");

		purge(context, source);

		assertTrue(hasCopy(source, target), "the remaining entry still states the relation");
	}

	@Test
	public void purge_removesARelationCopyWhoseRestatingEntryIsRemovedToo() {
		ContextImpl context = newContext();
		Entry target = local(newContext());
		Entry source = local(context);
		Entry restating = local(context);
		source.getLocalMetadata().setGraph(relation(source, target));
		restating.getLocalMetadata().setGraph(relation(source, target));
		assertTrue(hasCopy(source, target), "precondition: the target holds a copy of the relation");

		purge(context, source, restating);

		assertFalse(hasCopy(source, target), "no graph that remains states the relation");
	}

	@Test
	public void refresh_readsAgainAnEntryEditedAfterThePlan() throws Exception {
		ContextImpl context = newContext();
		Entry edited = context.createLink(null, URI.create("https://slashdot.org/"), null);
		EntryPurge purge = EntryPurge.plan(context, iris(edited));
		// past the millisecond of the modified date the plan read
		Thread.sleep(10);
		edited.getLocalMetadata().setGraph(title(edited, "edited after the plan"));

		assertEquals(1, commit(purge));

		assertFalse(hasGraph(edited.getLocalMetadataURI()), "the edit is removed with the entry");
	}

	@Test
	public void refresh_readsAgainAnEntryAddedToAListAfterThePlan() {
		ContextImpl context = newContext();
		Entry list = context.createResource(null, GraphType.List, null, null);
		Entry added = context.createLink(null, URI.create("https://slashdot.org/"), null);
		EntryPurge purge = EntryPurge.plan(context, iris(added));
		((List) list.getResource()).addChild(added.getEntryURI());

		assertEquals(1, commit(purge));

		assertEquals(java.util.List.of(), ((List) list.getResource()).getChildren(),
				"the list it joined after the plan is pruned too");
	}

	@Test
	public void apply_removesAnAclGrantMadeAfterThePlan() throws Exception {
		ContextImpl context = newContext();
		Entry link = context.createLink(null, URI.create("https://slashdot.org/"), null);
		EntryPurge purge = EntryPurge.plan(context, iris(link));
		// past the millisecond of the modified date the plan read, which the grant would have to change
		Thread.sleep(10);
		link.addAllowedPrincipalsFor(AccessProperty.ReadMetadata, pm.getPrincipalEntry("Mickey").getResourceURI());

		assertEquals(0, commit(purge), "precondition: a grant does not stamp the entry, so it is not read again");

		assertFalse(hasGraph(link.getEntryURI()), "the grant goes with the entry");
	}

	@Test
	public void refresh_removesARelationCopyWrittenIntoAnotherRemovedEntryAfterThePlan() throws Exception {
		ContextImpl context = newContext();
		Entry source = local(context);
		Entry target = local(context);
		EntryPurge purge = EntryPurge.plan(context, iris(source, target));
		// past the millisecond of the modified date the plan read
		Thread.sleep(10);
		source.getLocalMetadata().setGraph(relation(source, target));
		assertTrue(hasCopy(source, target), "precondition: the target holds a copy of the relation");

		commit(purge);

		assertFalse(hasCopy(source, target), "the copy was written after the plan read the target's relations");
	}

	@Test
	public void read_readsTheSameWithAStorePassAsGraphByGraph() {
		ContextImpl context = newContext();
		Entry list = context.createResource(null, GraphType.List, null, null);
		Entry link = context.createLink(null, URI.create("https://slashdot.org/"), list.getResourceURI());
		Entry other = context.createLink(null, URI.create("https://digg.com/"), list.getResourceURI());
		link.getLocalMetadata().setGraph(title(link, "a link"));
		other.getLocalMetadata().setGraph(title(other, "another link"));
		// 12 graphs, more than the 8 above which a store pass replaces reading graph by graph
		java.util.List<IRI> graphs = graphsOf(list, link, other);
		ExactRemoval exactRemoval = new ExactRemoval(rm);
		Map<IRI, Set<Statement>> graphByGraph = readAsSets(exactRemoval, graphs);

		rm.readingGraphsByScan = true;
		Map<IRI, Set<Statement>> storePass = readAsSets(exactRemoval, graphs);

		assertFalse(graphByGraph.get(iri(link.getLocalMetadataURI().toString())).isEmpty());
		assertEquals(graphByGraph, storePass);
	}

	@Test
	public void plan_refusesAContextThatKeepsEntryNames() {
		Entry user = pm.getPrincipalEntry("Donald");

		assertThrows(IllegalArgumentException.class,
				() -> EntryPurge.plan((ContextImpl) user.getContext(), iris(user)));
	}

	/**
	 * The same entry removed from two copies of one context, once by {@code ContextImpl.remove} and once by a purge,
	 * leaves the same statements in both, apart from the creation and modification dates.
	 */
	@Test
	public void purge_leavesWhatContextRemoveLeaves() {
		Entry target = local(newContext());
		ContextImpl removedOneByOne = fixture(target);
		ContextImpl purged = fixture(target);
		assertTrue(hasCopy(purged.get("a"), target), "precondition: the target holds a copy of the relation");

		removedOneByOne.remove(removedOneByOne.get("a").getEntryURI());
		purge(purged, purged.get("a"));

		assertEquals(statementsOf(removedOneByOne, target), statementsOf(purged, target));
	}

	/** As above for the last member of a list, which the purge must leave as an emptied list is saved. */
	@Test
	public void purge_ofALastMember_leavesWhatContextRemoveLeaves() {
		Entry target = local(newContext());
		ContextImpl removedOneByOne = singleMemberFixture(target);
		ContextImpl purged = singleMemberFixture(target);

		removedOneByOne.remove(removedOneByOne.get("a").getEntryURI());
		purge(purged, purged.get("a"));

		assertEquals(statementsOf(removedOneByOne, target), statementsOf(purged, target));
	}

	/** A list holding only entry a, whose metadata relates it to the target in another context. */
	private ContextImpl singleMemberFixture(Entry target) {
		ContextImpl context = newContext();
		Entry list = context.createResource("list", GraphType.List, null, null);
		Entry a = context.createResource("a", GraphType.None, null, list.getResourceURI());
		a.getLocalMetadata().setGraph(relation(a, target));
		return context;
	}

	/** A list holding entries a and b, whose metadata relates a to b and to the target in another context. */
	private ContextImpl fixture(Entry target) {
		ContextImpl context = newContext();
		Entry list = context.createResource("list", GraphType.List, null, null);
		Entry a = context.createResource("a", GraphType.None, null, list.getResourceURI());
		Entry b = context.createResource("b", GraphType.None, null, list.getResourceURI());
		Model metadata = relation(a, target);
		metadata.addAll(relation(a, b));
		a.getLocalMetadata().setGraph(metadata);
		return context;
	}

	/**
	 * The statements of the context's graphs and of the target's relations graph, with the context's id replaced
	 * and without creation and modification dates, which differ by when they were written.
	 */
	private Set<String> statementsOf(ContextImpl context, Entry target) {
		Set<String> statements = new HashSet<>();
		String contextURI = context.getURI().toString();
		try (RepositoryConnection rc = rm.getRepository().getConnection();
			 RepositoryResult<Statement> all = rc.getStatements(null, null, null, false)) {
			for (Statement statement : all) {
				if (statement.getContext() == null) {
					continue;
				}
				String graph = statement.getContext().stringValue();
				boolean ofContext = graph.equals(contextURI) || graph.startsWith(contextURI + "/");
				boolean targetRelations = graph.equals(target.getRelationURI().toString())
						&& statement.getSubject().stringValue().startsWith(contextURI + "/");
				boolean date = statement.getPredicate().equals(RepositoryProperties.Created)
						|| statement.getPredicate().equals(RepositoryProperties.Modified);
				if ((ofContext || targetRelations) && !date) {
					statements.add(statement.toString().replace(contextURI, "{context}"));
				}
			}
		}
		return statements;
	}

	/** Runs a purge as a tree removal does. */
	private void purge(ContextImpl context, Entry... entries) {
		commit(EntryPurge.plan(context, iris(entries)));
	}

	/**
	 * Refreshes and applies the purge in a transaction under the monitor, publishes it there and finishes it after.
	 *
	 * @return how many entries the refresh read again
	 */
	private int commit(EntryPurge purge) {
		int refreshed;
		synchronized (rm.getRepository()) {
			try (RepositoryConnection rc = rm.getRepository().getConnection()) {
				rc.begin();
				refreshed = purge.refresh(rc);
				purge.apply(rc);
				rc.commit();
			}
			purge.publish(false);
		}
		purge.afterPublish();
		return refreshed;
	}

	private java.util.List<URI> recordEvents(RepositoryEvent event, Runnable action) {
		java.util.List<URI> sources = new ArrayList<>();
		RepositoryListener recorder = new RepositoryListener() {
			@Override
			public void repositoryUpdated(RepositoryEventObject eventObject) {
				sources.add(((Entry) eventObject.getSource()).getEntryURI());
			}
		};
		rm.registerListener(recorder, event);
		try {
			action.run();
		} finally {
			rm.unregisterListener(recorder, event);
		}
		return sources;
	}

	private static java.util.List<IRI> iris(Entry... entries) {
		return Arrays.stream(entries).map(entry -> iri(entry.getEntryURI().toString())).toList();
	}

	/** The entry, metadata, relations and resource graphs of the entries. */
	private static java.util.List<IRI> graphsOf(Entry... entries) {
		return Arrays.stream(entries)
				.flatMap(entry -> Stream.of(entry.getEntryURI(), entry.getLocalMetadataURI(), entry.getRelationURI(),
						entry.getResourceURI()))
				.map(uri -> iri(uri.toString()))
				.toList();
	}

	/** What the exact removal reads of each graph, as sets: a store pass reads in another order. */
	private Map<IRI, Set<Statement>> readAsSets(ExactRemoval exactRemoval, java.util.List<IRI> graphs) {
		try (RepositoryConnection rc = rm.getRepository().getConnection()) {
			return exactRemoval.read(rc, graphs).entrySet().stream()
					.collect(Collectors.toMap(Map.Entry::getKey, graph -> Set.copyOf(graph.getValue())));
		}
	}

	/** An entry whose resource is in the repository, which a relation copy needs at both ends. */
	private static Entry local(ContextImpl context) {
		return context.createResource(null, GraphType.None, null, null);
	}

	private static Model title(Entry entry, String title) {
		Model model = new LinkedHashModel();
		model.add(iri(entry.getResourceURI().toString()), DCTERMS.TITLE, literal(title));
		return model;
	}

	private static Model relation(Entry source, Entry target) {
		Model model = new LinkedHashModel();
		model.add(iri(source.getResourceURI().toString()), DCTERMS.RELATION, iri(target.getResourceURI().toString()));
		return model;
	}

	private static Statement statement(URI subject, IRI predicate, URI object) {
		return VF.createStatement(iri(subject.toString()), predicate, iri(object.toString()));
	}

	/** The member positions of a list, without their graph. */
	private Set<Statement> positionsOf(Entry list) {
		IRI resource = iri(list.getResourceURI().toString());
		try (RepositoryConnection rc = rm.getRepository().getConnection()) {
			return rc.getStatements(resource, null, null, false, resource).stream()
					.filter(s -> s.getPredicate().stringValue().startsWith(RDF.NAMESPACE + "_"))
					.map(s -> VF.createStatement(s.getSubject(), s.getPredicate(), s.getObject()))
					.collect(Collectors.toSet());
		}
	}

	private boolean hasCopy(Entry source, Entry target) {
		try (RepositoryConnection rc = rm.getRepository().getConnection()) {
			return rc.hasStatement(iri(source.getResourceURI().toString()), DCTERMS.RELATION,
					iri(target.getResourceURI().toString()), false, iri(target.getRelationURI().toString()));
		}
	}

	private boolean isIndexed(ContextImpl context, Entry entry) {
		try (RepositoryConnection rc = rm.getRepository().getConnection()) {
			return rc.hasStatement((Resource) null, RepositoryProperties.resHasEntry,
					iri(entry.getEntryURI().toString()), false, context.resourceURI);
		}
	}
}
