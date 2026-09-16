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
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.entrystore.Context;
import org.entrystore.Entry;
import org.entrystore.EntryType;
import org.entrystore.GraphType;
import org.entrystore.List;
import org.entrystore.PrincipalManager.AccessProperty;
import org.entrystore.ResourceType;
import org.entrystore.repository.RepositoryException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.net.URI;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

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

	@Test
	public void setResourceURI_refusesALocalEntry() {
		URI listURI = listEntry.getResourceURI();
		assertThrows(IllegalArgumentException.class, () -> listEntry.setResourceURI(URI.create(listURI + "-renamed")));
		URI fileURI = resourceEntry.getResourceURI();
		assertThrows(IllegalArgumentException.class, () -> resourceEntry.setResourceURI(URI.create(fileURI + "-renamed")));
		Entry contextEntry = context.getEntry();
		assertThrows(IllegalArgumentException.class,
			() -> contextEntry.setResourceURI(URI.create(contextEntry.getResourceURI() + "-renamed")));
		Entry daisy = pm.getPrincipalEntry("Daisy");
		assertThrows(IllegalArgumentException.class,
			() -> daisy.setResourceURI(URI.create(daisy.getResourceURI() + "-renamed")));

		assertEquals(listURI, listEntry.getResourceURI());
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
		pm.setAuthenticatedUserURI(pm.getAdminUser().getURI());
		ValueFactory vf = rm.getValueFactory();
		EntryImpl impl = (EntryImpl) linkEntry;
		IRI admin = vf.createIRI(pm.getAdminUser().getURI().toString());
		assertFalse(linkEntry.getGraph().contains(impl.getSesameEntryURI(), RepositoryProperties.Contributor, admin));
		Model metadata = new LinkedHashModel();
		metadata.add(impl.getSesameResourceURI(), DCTERMS.TITLE, vf.createLiteral("triggers a metadata write"));
		linkEntry.getLocalMetadata().setGraph(metadata);

		linkEntry.setResourceURI(URI.create("http://slashdot.org/renamed"));

		// the metadata rewrite inside the rename adds the contributor to the entry graph; the entry-graph
		// rewrite that follows must carry it rather than restore a snapshot taken before it
		assertTrue(linkEntry.getGraph().contains(impl.getSesameEntryURI(), RepositoryProperties.Contributor, admin));
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
}
