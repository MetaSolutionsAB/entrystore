/*
 * Copyright (c) 2007-2017 MetaSolutions AB
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

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.net.URI;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Model;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.LinkedHashModel;
import org.eclipse.rdf4j.model.vocabulary.RDF;
import org.entrystore.Context;
import org.entrystore.Entry;
import org.entrystore.EntryType;
import org.entrystore.GraphType;
import org.entrystore.ResourceType;
import org.entrystore.repository.RepositoryException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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
		// Checking that builtintype cannot be changed for local resources
		try {
			listEntry.setGraphType(GraphType.None);
			assertTrue(false, "Successfully (and erroneously) changed the builtin type" + " of a local resource!");
		} catch (RepositoryException re) {
		}

		// Checking that builtintype CAN be changed for links.
		assertTrue(linkEntry.getGraphType() == GraphType.None);
		linkEntry.setGraphType(GraphType.List);
		assertTrue(linkEntry.getGraphType() == GraphType.List);

		// Checking that builtintype CAN be changed for references.
		assertTrue(refEntry.getGraphType() == GraphType.None);
		refEntry.setGraphType(GraphType.List);
		assertTrue(refEntry.getGraphType() == GraphType.List);
	}

	@Test
	public void referenceType() {
		assertTrue(listEntry.getEntryType() == EntryType.Local);
		assertTrue(linkEntry.getEntryType() == EntryType.Link);
		assertTrue(refEntry.getEntryType() == EntryType.Reference);
	}

	@Test
	public void representationType() {
		assertTrue(listEntry.getResourceType() == ResourceType.InformationResource);
		// Checking that representationtype cannot be changed for local
		// resources
		try {
			listEntry.setResourceType(ResourceType.NamedResource);
			assertTrue(false, "Succesfully (and erronously) changed the representationtype" + " of a local resource!");
		} catch (RepositoryException re) {
		}

		assertTrue(linkEntry.getResourceType() == ResourceType.InformationResource);
		linkEntry.setResourceType(ResourceType.NamedResource);
		assertTrue(linkEntry.getResourceType() == ResourceType.NamedResource);

		assertTrue(refEntry.getResourceType() == ResourceType.InformationResource);
		refEntry.setResourceType(ResourceType.Unknown);
		assertTrue(refEntry.getResourceType() == ResourceType.Unknown);
	}

	@Test
	public void dates() {
		assertTrue(listEntry.getCreationDate() != null);
		assertTrue(listEntry.getModifiedDate() != null);
		listEntry.getLocalMetadata().setGraph(listEntry.getLocalMetadata().getGraph()); // pretend
		// to
		// change
		// the
		// metadata
		// graph.
		assertTrue(listEntry.getModifiedDate() != null);
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
		assertTrue(!mmdGraph.filter(null, RepositoryProperties.resource, null).isEmpty());
		assertTrue(!mmdGraph.filter(null, RepositoryProperties.metadata, null).isEmpty());
		assertTrue(!mmdGraph.filter(null, RepositoryProperties.Created, null).isEmpty());
		assertTrue(!mmdGraph.filter(null, RDF.TYPE, null).isEmpty());

		assertTrue(refEntry.getExternalMetadataCacheDate() == null);
		refEntry.getCachedExternalMetadata().setGraph(new LinkedHashModel());
		assertTrue(refEntry.getExternalMetadataCacheDate() != null);

		assertTrue(refLinkEntry.getExternalMetadataCacheDate() == null);
		refLinkEntry.getCachedExternalMetadata().setGraph(new LinkedHashModel());
		assertTrue(refLinkEntry.getExternalMetadataCacheDate() != null);

	}

	@Test
	public void setEntryGraph() {
		Model mmdGraph = listEntry.getGraph();
		listEntry.setGraph(mmdGraph);
		Model mmdGraph2 = listEntry.getGraph();
		assertTrue(mmdGraph.size() == mmdGraph2.size());
	}

	@Test
	public void refLocalEntry() {
		Entry ref = context.createReference(null, linkEntry.getResourceURI(), linkEntry.getLocalMetadataURI(), null);
		assertTrue(ref.getCachedExternalMetadata().getGraph().size() == linkEntry.getLocalMetadata().getGraph().size());
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
        assertTrue(!targetEntry.getRelations().isEmpty());

        //Testing to manually remove relation to target entry
        g.remove(stm);
        sourceEntry.setGraph(g);
        assertTrue(targetEntry.getRelations().isEmpty());

        //Testing to change acl and making sure that principal inv-rel-cache (relations) is not affected.
        int rels = guestE.getRelations().size();
        g.add(readStm);
        sourceEntry.setGraph(g);
        assertTrue(guestE.getRelations().size() == rels);

        //Testing that inv-rel-cache of target entry is updated upon remove of source entry.
        g.add(stm);
        sourceEntry.setGraph(g);
        assertTrue(!targetEntry.getRelations().isEmpty());
        context.remove(sourceEntry.getEntryURI());
        assertTrue(targetEntry.getRelations().isEmpty());
    }
}
