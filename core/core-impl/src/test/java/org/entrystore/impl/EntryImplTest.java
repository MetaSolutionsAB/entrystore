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
import org.eclipse.rdf4j.model.vocabulary.RDF;
import org.eclipse.rdf4j.repository.Repository;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.entrystore.Context;
import org.entrystore.Entry;
import org.entrystore.EntryType;
import org.entrystore.GraphType;
import org.entrystore.List;
import org.entrystore.PrincipalManager.AccessProperty;
import org.entrystore.ResourceType;
import org.entrystore.repository.RepositoryEvent;
import org.entrystore.repository.RepositoryEventObject;
import org.entrystore.repository.RepositoryException;
import org.entrystore.repository.RepositoryListener;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.File;
import java.net.URI;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class EntryImplTest extends AbstractCoreTest {

	private Context context;

	private Entry listEntry;

	private Entry linkEntry;

	private Entry refEntry;

	private Entry refLinkEntry;

	private Entry resourceEntry;

	@BeforeEach
	public void setUp() {
		super.setUp();
		rm.setCheckForAuthorization(false);

		// A new Context
		Entry entry = cm.createResource(null, GraphType.Context, null, null);
		context = (Context) entry.getResource();
		listEntry = context.createResource(null, GraphType.List, null, null);
		linkEntry = context.createLink(null, URI.create("http://slashdot.org/"), null);
		refEntry = context.createReference(null, URI.create("http://reddit.com/"), URI.create("http://example.com/md1"), null);
		refLinkEntry = context.createLinkReference(null, URI.create("http://vk.se/"), URI.create("http://vk.se/md1"), null);
		resourceEntry = context.createResource(null, GraphType.None, ResourceType.InformationResource, null);
		File pomFile = new File("pom.xml");
		resourceEntry.setFilename(pomFile.getName());
		resourceEntry.setMimetype("text/xml");
	}

	@Test
	public void builtinType() {
		// Checking that built-in type cannot be changed for local resources
		try {
			listEntry.setGraphType(GraphType.None);
			fail("Successfully (and erroneously) changed the builtin type" + " of a local resource!");
		} catch (RepositoryException ignored) {
		}

		// Checking that builtintype CAN be changed for links.
		assertSame(GraphType.None, linkEntry.getGraphType());
		linkEntry.setGraphType(GraphType.List);
		assertSame(GraphType.List, linkEntry.getGraphType());

		// Checking that builtintype CAN be changed for references.
		assertSame(GraphType.None, refEntry.getGraphType());
		refEntry.setGraphType(GraphType.List);
		assertSame(GraphType.List, refEntry.getGraphType());
	}

	@Test
	public void referenceType() {
		assertSame(EntryType.Local, listEntry.getEntryType());
		assertSame(EntryType.Link, linkEntry.getEntryType());
		assertSame(EntryType.Reference, refEntry.getEntryType());
	}

	@Test
	public void representationType() {
		assertSame(ResourceType.InformationResource, listEntry.getResourceType());
		// Checking that representationtype cannot be changed for local
		// resources
		try {
			listEntry.setResourceType(ResourceType.NamedResource);
			fail("Succesfully (and erronously) changed the representationtype" + " of a local resource!");
		} catch (RepositoryException ignored) {
		}

		assertSame(ResourceType.InformationResource, linkEntry.getResourceType());
		linkEntry.setResourceType(ResourceType.NamedResource);
		assertSame(ResourceType.NamedResource, linkEntry.getResourceType());

		assertSame(ResourceType.InformationResource, refEntry.getResourceType());
		refEntry.setResourceType(ResourceType.Unknown);
		assertSame(ResourceType.Unknown, refEntry.getResourceType());
	}

	@Test
	public void dates() {
		assertNotNull(listEntry.getCreationDate());
		assertNotNull(listEntry.getModifiedDate());
		listEntry.getLocalMetadata().setGraph(listEntry.getLocalMetadata().getGraph()); // pretend
		// to
		// change
		// the
		// metadata
		// graph.
		assertNotNull(listEntry.getModifiedDate());
	}


//	@Test
//	public void rdf() {
//		Graph mmdGraph = listEntry.getGraph();
//		assertTrue(mmdGraph.size() == 6);
//		assertTrue(mmdGraph.match(null, RepositoryProperties.resource, null).hasNext());
//		assertTrue(mmdGraph.match(null, RepositoryProperties.metadata, null).hasNext());
//		assertTrue(mmdGraph.match(null, RepositoryProperties.Created, null).hasNext());
//		assertTrue(mmdGraph.match(null, RDF.TYPE, null).hasNext());
//
//		assertTrue(refEntry.getExternalMetadataCacheDate() == null);
//		refEntry.getCachedExternalMetadata().setGraph(new GraphImpl());
//		assertTrue(refEntry.getExternalMetadataCacheDate() != null);
//
//		assertTrue(refLinkEntry.getExternalMetadataCacheDate() == null);
//		refLinkEntry.getCachedExternalMetadata().setGraph(new GraphImpl());
//		assertTrue(refLinkEntry.getExternalMetadataCacheDate() != null);
//
//	}

	@Test
	public void rdf() {
		Model mmdGraph = listEntry.getGraph();
//		assertTrue(mmdGraph.size() == 6);
		assertFalse(mmdGraph.filter(null, RepositoryProperties.resource, null).isEmpty());
		assertFalse(mmdGraph.filter(null, RepositoryProperties.metadata, null).isEmpty());
		assertFalse(mmdGraph.filter(null, RepositoryProperties.Created, null).isEmpty());
		assertFalse(mmdGraph.filter(null, RDF.TYPE, null).isEmpty());

		assertNull(refEntry.getExternalMetadataCacheDate());
		refEntry.getCachedExternalMetadata().setGraph(new LinkedHashModel());
		assertNotNull(refEntry.getExternalMetadataCacheDate());

		assertNull(refLinkEntry.getExternalMetadataCacheDate());
		refLinkEntry.getCachedExternalMetadata().setGraph(new LinkedHashModel());
		assertNotNull(refLinkEntry.getExternalMetadataCacheDate());

	}

	@Test
	public void setEntryGraph() {
		Model mmdGraph = listEntry.getGraph();
		listEntry.setGraph(mmdGraph);
		Model mmdGraph2 = listEntry.getGraph();
		assertEquals(mmdGraph.size(), mmdGraph2.size());
	}

	@Test
	public void refLocalEntry() {
		Entry ref = context.createReference(null, linkEntry.getResourceURI(), linkEntry.getLocalMetadataURI(), null);
		assertEquals(ref.getCachedExternalMetadata().getGraph().size(), linkEntry.getLocalMetadata().getGraph().size());
	}

	@Test
	public void equalsAndHashCode() {
		EntryImpl original = (EntryImpl) linkEntry;
		// a distinct instance for the same entry, as after a SoftCache eviction/miss
		EntryImpl duplicate = new EntryImpl(original.getId(), (ContextImpl) context, rm, original.getRepository());

		assertNotSame(original, duplicate);
		assertEquals(original, duplicate);
		assertEquals(original.hashCode(), duplicate.hashCode());

		Set<Entry> entries = new HashSet<>();
		entries.add(original);
		entries.add(duplicate);
		assertEquals(1, entries.size());

		assertNotEquals(listEntry, linkEntry);
		assertNotEquals(null, linkEntry);
		assertNotEquals("not an entry", linkEntry);
	}

	@Test
	public void equalsAndHashCodeOnUninitializedEntry() {
		// the package-private bootstrap constructor leaves entryURI null
		EntryImpl uninitialized = new EntryImpl(rm, ((EntryImpl) linkEntry).getRepository());

		// below needed bcos: with a null entryURI the field comparison can never match, so only the identity
		// short-circuit keeps equals reflexive
		assertEquals(uninitialized, uninitialized);
		assertNotEquals(uninitialized, linkEntry);
		assertNotEquals(linkEntry, uninitialized);
		assertEquals(0, uninitialized.hashCode());
	}

	@Test
	public void fileSizeSurvivesStoreReload() {
		long expectedSize = 4096L;
		resourceEntry.setFileSize(expectedSize);

		// Drop the in-memory cache and reload from the repository, as happens
		// after a SoftCache eviction. loadFromStatements resets fileSize to -1.
		((EntryImpl) resourceEntry).load();

		assertEquals(expectedSize, resourceEntry.getFileSize());
	}

	@Test
	public void zeroFileSizeSurvivesStoreReload() {
		// The guard pivots at 0: a stored size of 0 was returned as -1 by the old
		// `fileSize > 0` guard, so this pins the fix at the boundary it changes.
		resourceEntry.setFileSize(0L);

		((EntryImpl) resourceEntry).load();

		assertEquals(0L, resourceEntry.getFileSize());
	}

	@Test
	public void fileSizeIsNegativeOneWhenNeverSet() {
		// An entry with no fileSize statement must report -1 after reload rather
		// than throwing on the absent statement.
		Entry unsizedEntry = context.createResource(null, GraphType.None, ResourceType.InformationResource, null);

		((EntryImpl) unsizedEntry).load();

		assertEquals(-1L, unsizedEntry.getFileSize());
	}

	@Test
	public void invRelCache() {
		EntryImpl sourceEntry = (EntryImpl) context.createResource(null, GraphType.None, null, null);
		EntryImpl targetEntry = (EntryImpl) context.createResource(null, GraphType.None, null, null);
		ValueFactory vf = sourceEntry.getRepository().getValueFactory();
		IRI pred = vf.createIRI("http://example.com/related");
		Statement stm = vf.createStatement(sourceEntry.getSesameResourceURI(), pred, targetEntry.getSesameResourceURI());
		EntryImpl guestE = (EntryImpl) pm.getGuestUser().getEntry();
		Statement readStm = vf.createStatement(sourceEntry.getSesameResourceURI(), RepositoryProperties.Read, guestE.getSesameResourceURI());
		Model g = sourceEntry.getGraph();

		//No relations in target entry
		assertTrue(targetEntry.getRelations().isEmpty());

		//Testing to create a relation to target entry
		g.add(stm);
		sourceEntry.setGraph(g);
		assertFalse(targetEntry.getRelations().isEmpty());

		//Testing to manually remove relation to target entry
		g.remove(stm);
		sourceEntry.setGraph(g);
		assertTrue(targetEntry.getRelations().isEmpty());

		//Testing to change acl and making sure that principal inv-rel-cache (relations) is not affected.
		int rels = guestE.getRelations().size();
		g.add(readStm);
		sourceEntry.setGraph(g);
		assertEquals(guestE.getRelations().size(), rels);

		//Testing that inv-rel-cache of target entry is updated upon remove of source entry.
		g.add(stm);
		sourceEntry.setGraph(g);
		assertFalse(targetEntry.getRelations().isEmpty());
		context.remove(sourceEntry.getEntryURI());
		assertTrue(targetEntry.getRelations().isEmpty());
	}

	private static Stream<Arguments> localEntries() {
		return Stream.of(
			local("list", t -> t.listEntry),
			local("file", t -> t.resourceEntry),
			local("context", t -> t.context.getEntry()),
			local("principal", t -> t.pm.getPrincipalEntry("Daisy")));
	}

	private static Arguments local(String kind, Function<EntryImplTest, Entry> pick) {
		return arguments(kind, pick);
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("localEntries")
	public void setResourceURI_refusesALocalEntry(String kind, Function<EntryImplTest, Entry> pick) {
		Entry entry = pick.apply(this);
		URI before = entry.getResourceURI();

		assertThrows(IllegalArgumentException.class, () -> entry.setResourceURI(URI.create(before + "-renamed")));

		assertEquals(before, entry.getResourceURI());
	}

	@Test
	public void setResourceURI_refusesAUserTypedReference() {
		// the shape TestSuite ships: a Reference to a user, typed User, whose URI may be an ACL object
		Entry reference = context.createReference(null, URI.create("http://example.com/someone"), URI.create("http://example.com/someone-md"), null);
		reference.setGraphType(GraphType.User);

		assertThrows(IllegalArgumentException.class, () -> reference.setResourceURI(URI.create("http://example.com/renamed")));
	}

	@Test
	public void setGraph_refusesARenameOfALocalEntryBeforeClearingItsGraph() {
		((List) listEntry.getResource()).addChild(linkEntry.getEntryURI());
		EntryImpl impl = (EntryImpl) listEntry;
		Model body = new LinkedHashModel(listEntry.getGraph());
		body.remove(impl.getSesameEntryURI(), RepositoryProperties.resource, impl.getSesameResourceURI());
		body.add(impl.getSesameEntryURI(), RepositoryProperties.resource, rm.getValueFactory().createIRI(impl.getSesameResourceURI() + "-renamed"));
		int statements = listEntry.getGraph().size();

		assertThrows(IllegalArgumentException.class, () -> listEntry.setGraph(body));

		// refused before the graph is cleared, so nothing about the entry moved
		assertEquals(statements, listEntry.getGraph().size());
		assertEquals(GraphType.List, listEntry.getGraphType());
		assertTrue(((List) listEntry.getResource()).getChildren().contains(linkEntry.getEntryURI()));
	}

	@Test
	public void setResourceURI_onAReferenceToALocalEntrySkipsTheWrappedMetadata() {
		// external metadata under the repository base is another entry's local metadata, wrapped read-only
		ValueFactory vf = rm.getValueFactory();
		Model ownMetadata = new LinkedHashModel();
		ownMetadata.add(vf.createIRI(linkEntry.getResourceURI().toString()), DCTERMS.TITLE, vf.createLiteral("the link's own title"));
		linkEntry.getLocalMetadata().setGraph(ownMetadata);
		Entry reference = context.createReference(null, URI.create("http://example.com/refers"), linkEntry.getLocalMetadataURI(), null);
		assertInstanceOf(LocalMetadataWrapper.class, reference.getCachedExternalMetadata());

		reference.setResourceURI(URI.create("http://example.com/refers-renamed"));

		assertEquals(URI.create("http://example.com/refers-renamed"), reference.getResourceURI());
		Model untouched = linkEntry.getLocalMetadata().getGraph();
		assertEquals(1, untouched.size());
		assertTrue(untouched.contains(vf.createIRI(linkEntry.getResourceURI().toString()), DCTERMS.TITLE, vf.createLiteral("the link's own title")));
	}

	@Test
	public void setResourceURI_leavesMetadataAloneWhenItNeverNamedTheResource() {
		ValueFactory vf = rm.getValueFactory();
		Model unrelated = new LinkedHashModel();
		unrelated.add(vf.createIRI("http://example.com/unrelated"), DCTERMS.TITLE, vf.createLiteral("says nothing about the resource"));
		linkEntry.getLocalMetadata().setGraph(unrelated);
		// events fire synchronously, so a rewrite of the metadata is exactly one MetadataUpdated
		AtomicInteger metadataWrites = new AtomicInteger();
		RepositoryListener counter = new RepositoryListener() {
			@Override
			public void repositoryUpdated(RepositoryEventObject eventObject) {
				metadataWrites.incrementAndGet();
			}
		};
		rm.registerListener(counter, RepositoryEvent.MetadataUpdated);

		try {
			linkEntry.setResourceURI(URI.create("http://slashdot.org/renamed"));
		} finally {
			rm.unregisterListener(counter, RepositoryEvent.MetadataUpdated);
		}

		assertEquals(0, metadataWrites.get());
		Model untouched = linkEntry.getLocalMetadata().getGraph();
		assertEquals(1, untouched.size());
		assertTrue(untouched.contains(vf.createIRI("http://example.com/unrelated"), DCTERMS.TITLE, vf.createLiteral("says nothing about the resource")));
	}

	@Test
	public void setResourceURI_onALinkKeepsTypeAclMetadataAndIndex() {
		linkEntry.setGraphType(GraphType.List);
		URI daisy = pm.getPrincipalEntry("Daisy").getResourceURI();
		linkEntry.addAllowedPrincipalsFor(AccessProperty.ReadResource, daisy);
		ValueFactory vf = rm.getValueFactory();
		URI oldResourceURI = linkEntry.getResourceURI();
		IRI oldResourceIRI = vf.createIRI(oldResourceURI.toString());
		Model metadata = new LinkedHashModel();
		metadata.add(oldResourceIRI, DCTERMS.TITLE, vf.createLiteral("kept across the rename"));
		linkEntry.getLocalMetadata().setGraph(metadata);
		URI newResourceURI = URI.create("http://slashdot.org/renamed");

		linkEntry.setResourceURI(newResourceURI);

		assertEquals(newResourceURI, linkEntry.getResourceURI());
		// type and resource-level ACL hang off the resource URI as subject inside the entry graph
		assertEquals(GraphType.List, linkEntry.getGraphType());
		assertTrue(linkEntry.getAllowedPrincipalsFor(AccessProperty.ReadResource).contains(daisy));
		Model renamedMetadata = linkEntry.getLocalMetadata().getGraph();
		assertTrue(renamedMetadata.contains(vf.createIRI(newResourceURI.toString()), DCTERMS.TITLE, null));
		assertFalse(renamedMetadata.contains(oldResourceIRI, null, null));
		assertTrue(context.getByResourceURI(newResourceURI).contains(linkEntry));
		assertTrue(context.getByResourceURI(oldResourceURI).isEmpty());
	}

	@Test
	public void setResourceURI_onALinkReferenceRewritesTheCachedExternalMetadata() {
		ValueFactory vf = rm.getValueFactory();
		IRI oldResourceIRI = vf.createIRI(refLinkEntry.getResourceURI().toString());
		Model cached = new LinkedHashModel();
		cached.add(oldResourceIRI, DCTERMS.TITLE, vf.createLiteral("cached"));
		refLinkEntry.getCachedExternalMetadata().setGraph(cached);
		URI newResourceURI = URI.create("http://vk.se/renamed");

		refLinkEntry.setResourceURI(newResourceURI);

		Model renamed = refLinkEntry.getCachedExternalMetadata().getGraph();
		assertTrue(renamed.contains(vf.createIRI(newResourceURI.toString()), DCTERMS.TITLE, null));
		assertFalse(renamed.contains(oldResourceIRI, null, null));
	}

	@Test
	public void setResourceURI_mayTargetAURLAnotherLinkAlreadyUses() {
		URI shared = refLinkEntry.getResourceURI();

		linkEntry.setResourceURI(shared);

		Set<Entry> holders = context.getByResourceURI(shared);
		assertTrue(holders.contains(linkEntry));
		assertTrue(holders.contains(refLinkEntry));
	}

	@Test
	public void setResourceURI_keepsTheContributorTheMetadataWriteAdded() {
		// Mickey creates the entry and writes its metadata; Daisy renames. Her contributor triple can only
		// come from the rename's own internal metadata write, which the pre-fix snapshot predated
		pm.setAuthenticatedUserURI(pm.getPrincipalEntry("Mickey").getResourceURI());
		EntryImpl entry = (EntryImpl) context.createLink(null, URI.create("http://example.com/created-by-mickey"), null);
		ValueFactory vf = rm.getValueFactory();
		Model metadata = new LinkedHashModel();
		metadata.add(entry.getSesameResourceURI(), DCTERMS.TITLE, vf.createLiteral("names the resource, so the rename rewrites it"));
		entry.getLocalMetadata().setGraph(metadata);
		IRI daisy = vf.createIRI(pm.getPrincipalEntry("Daisy").getResourceURI().toString());
		assertFalse(entry.getGraph().contains(entry.getSesameEntryURI(), RepositoryProperties.Contributor, daisy));
		pm.setAuthenticatedUserURI(URI.create(daisy.stringValue()));

		entry.setResourceURI(URI.create("http://example.com/renamed-by-daisy"));

		assertTrue(entry.getGraph().contains(entry.getSesameEntryURI(), RepositoryProperties.Contributor, daisy));
	}

	@Test
	public void setGraph_renamingTheResourceKeepsTheTypeAndTheResourceAcl() {
		linkEntry.setGraphType(GraphType.List);
		URI daisy = pm.getPrincipalEntry("Daisy").getResourceURI();
		linkEntry.addAllowedPrincipalsFor(AccessProperty.ReadResource, daisy);
		EntryImpl impl = (EntryImpl) linkEntry;
		IRI oldResourceURI = impl.getSesameResourceURI();
		IRI newResourceURI = rm.getValueFactory().createIRI("http://slashdot.org/renamed");
		// what a client PUTs back: the GET body with only es:resource changed
		Model body = new LinkedHashModel(linkEntry.getGraph());
		body.remove(impl.getSesameEntryURI(), RepositoryProperties.resource, oldResourceURI);
		body.add(impl.getSesameEntryURI(), RepositoryProperties.resource, newResourceURI);

		linkEntry.setGraph(body);

		assertEquals(URI.create(newResourceURI.stringValue()), linkEntry.getResourceURI());
		assertEquals(GraphType.List, linkEntry.getGraphType());
		assertTrue(linkEntry.getAllowedPrincipalsFor(AccessProperty.ReadResource).contains(daisy));
	}

	@Test
	public void setResourceURI_updatesTheInverseRelationOnTheTargetEntry() {
		// only a statement between two repository resources is cached as an inverse relation, so the
		// link points at a local resource and is renamed to another one
		EntryImpl source = (EntryImpl) context.createLink(null, resourceEntry.getResourceURI(), null);
		EntryImpl target = (EntryImpl) context.createResource(null, GraphType.None, null, null);
		ValueFactory vf = source.getRepository().getValueFactory();
		IRI related = vf.createIRI("http://example.com/related");
		IRI oldResourceURI = source.getSesameResourceURI();
		Model graph = source.getGraph();
		graph.add(oldResourceURI, related, target.getSesameResourceURI());
		source.setGraph(graph);
		assertTrue(target.getRelations().contains(oldResourceURI, related, target.getSesameResourceURI()));
		URI newResourceURI = listEntry.getResourceURI();

		source.setResourceURI(newResourceURI);

		Model relations = target.getRelations();
		assertTrue(relations.contains(vf.createIRI(newResourceURI.toString()), related, target.getSesameResourceURI()));
		assertFalse(relations.contains(oldResourceURI, null, null));
	}

	@Test
	public void removeAllowedPrincipalsFor_reportsWhetherThePrincipalWasAllowed() {
		URI daisy = pm.getPrincipalEntry("Daisy").getResourceURI();
		linkEntry.addAllowedPrincipalsFor(AccessProperty.ReadResource, daisy);

		assertTrue(linkEntry.removeAllowedPrincipalsFor(AccessProperty.ReadResource, daisy));
		assertFalse(linkEntry.removeAllowedPrincipalsFor(AccessProperty.ReadResource, daisy));
		assertFalse(linkEntry.getAllowedPrincipalsFor(AccessProperty.ReadResource).contains(daisy));
	}

	@Test
	public void setAllowedPrincipalsFor_replacesTheWholeSet() {
		URI daisy = pm.getPrincipalEntry("Daisy").getResourceURI();
		URI donald = pm.getPrincipalEntry("Donald").getResourceURI();
		linkEntry.addAllowedPrincipalsFor(AccessProperty.ReadResource, daisy);

		linkEntry.setAllowedPrincipalsFor(AccessProperty.ReadResource, Set.of(donald));

		assertEquals(Set.of(donald), linkEntry.getAllowedPrincipalsFor(AccessProperty.ReadResource));
		// the store, not the cache the call just installed, is what survives a reload
		try (RepositoryConnection rc = rm.getRepository().getConnection()) {
			IRI entryIRI = ((EntryImpl) linkEntry).getSesameEntryURI();
			assertFalse(rc.hasStatement(null, RepositoryProperties.Read, rc.getValueFactory().createIRI(daisy.toString()), false, entryIRI));
			assertTrue(rc.hasStatement(null, RepositoryProperties.Read, rc.getValueFactory().createIRI(donald.toString()), false, entryIRI));
		}
	}

	@Test
	public void addAllowedPrincipalsFor_leavesOtherPrincipalsAlone() {
		URI daisy = pm.getPrincipalEntry("Daisy").getResourceURI();
		URI donald = pm.getPrincipalEntry("Donald").getResourceURI();

		linkEntry.addAllowedPrincipalsFor(AccessProperty.ReadResource, daisy);
		linkEntry.addAllowedPrincipalsFor(AccessProperty.ReadResource, donald);

		assertEquals(Set.of(daisy, donald), linkEntry.getAllowedPrincipalsFor(AccessProperty.ReadResource));
	}

	@Test
	public void hasAllowedPrincipals_isFalseUntilAnAclIsSet() {
		assertFalse(linkEntry.hasAllowedPrincipals());

		linkEntry.addAllowedPrincipalsFor(AccessProperty.ReadResource, pm.getPrincipalEntry("Daisy").getResourceURI());

		assertTrue(linkEntry.hasAllowedPrincipals());
	}

	@Test
	public void hasAllowedPrincipals_isFalseAfterRemovingTheLastPrincipal() {
		URI daisy = pm.getPrincipalEntry("Daisy").getResourceURI();
		linkEntry.addAllowedPrincipalsFor(AccessProperty.ReadResource, daisy);
		assertTrue(linkEntry.hasAllowedPrincipals());

		assertTrue(linkEntry.removeAllowedPrincipalsFor(AccessProperty.ReadResource, daisy));

		assertFalse(linkEntry.hasAllowedPrincipals());
	}

	@Test
	public void hasAllowedPrincipals_isFalseAfterReplacingTheLastAclWithAnEmptySet() {
		linkEntry.addAllowedPrincipalsFor(AccessProperty.ReadResource, pm.getPrincipalEntry("Daisy").getResourceURI());
		assertTrue(linkEntry.hasAllowedPrincipals());

		linkEntry.setAllowedPrincipalsFor(AccessProperty.ReadResource, Set.of());

		assertFalse(linkEntry.hasAllowedPrincipals());
	}

	@Test
	public void hasAllowedPrincipals_preservesTheRepositoryConnectionFailure() {
		Repository unavailable = mock(Repository.class);
		var failure = new org.eclipse.rdf4j.repository.RepositoryException("Store unavailable");
		when(unavailable.getConnection()).thenThrow(failure);
		EntryImpl entry = new EntryImpl(rm, unavailable);

		RepositoryException thrown = assertThrows(RepositoryException.class, entry::hasAllowedPrincipals);

		assertSame(failure, thrown.getCause());
	}

	@Test
	public void setResourceURI_firesOneEntryUpdatedAfterUpdatingTheIndex() {
		URI oldURI = linkEntry.getResourceURI();
		URI newURI = URI.create("http://example.com/renamed");
		assertTrue(context.getByResourceURI(oldURI).contains(linkEntry));
		AtomicInteger events = new AtomicInteger();
		AtomicBoolean consistentAtEvent = new AtomicBoolean();
		RepositoryListener listener = new RepositoryListener() {
			@Override
			public void repositoryUpdated(RepositoryEventObject event) {
				events.incrementAndGet();
				consistentAtEvent.set(newURI.equals(linkEntry.getResourceURI())
						&& context.getByResourceURI(newURI).contains(linkEntry)
						&& !context.getByResourceURI(oldURI).contains(linkEntry));
			}
		};
		rm.registerListener(listener, RepositoryEvent.EntryUpdated);
		try {
			linkEntry.setResourceURI(newURI);
			// the second call is a no-op and must not fire again
			linkEntry.setResourceURI(newURI);
			assertEquals(1, events.get());
			assertTrue(consistentAtEvent.get());
		} finally {
			rm.unregisterListener(listener, RepositoryEvent.EntryUpdated);
		}
	}
}
