/**
 * Copyright (c) 2007-2010
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

package org.entrystore.repository.impl;

import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.net.URI;

import org.entrystore.repository.ResourceType;
import org.entrystore.repository.Context;
import org.entrystore.repository.ContextManager;
import org.entrystore.repository.Entry;
import org.entrystore.repository.EntryType;
import org.entrystore.repository.RepositoryException;
import org.entrystore.repository.RepositoryProperties;
import org.entrystore.repository.RepresentationType;
import org.entrystore.repository.config.Config;
import org.entrystore.repository.config.ConfigurationManager;
import org.entrystore.repository.config.Settings;
import org.junit.Before;
import org.junit.Test;
import org.openrdf.model.Graph;
import org.openrdf.model.impl.GraphImpl;
import org.openrdf.model.vocabulary.RDF;

public class MetaMetadataTest {
	private RepositoryManagerImpl rm;

	private ContextManager cm;

	private Context context;

	private Entry listEntry;

	private Entry linkEntry;

	private Entry refEntry;

	private Entry refLinkEntry;

	@Before
	public void setup() {
		ConfigurationManager confMan = null;
		try {
			confMan = new ConfigurationManager(ConfigurationManager.getConfigurationURI());
		} catch (IOException e) {
			e.printStackTrace();
		}
		Config config = confMan.getConfiguration();
		config.setProperty(Settings.STORE_TYPE, "memory");
		rm = new RepositoryManagerImpl("http://my.confolio.org/", config);
		rm.setCheckForAuthorization(false);
		cm = rm.getContextManager();
		// A new Context
		Entry entry = cm.createResource(null, ResourceType.Context, null, null);
		context = (Context) entry.getResource();
		listEntry = context.createResource(null, ResourceType.List, null, null);
		linkEntry = context.createLink(null, URI.create("http://slashdot.org/"), null);
		refEntry = context
				.createReference(null, URI.create("http://reddit.com/"), URI.create("http://example.com/md1"), null);
		refLinkEntry = context.createLinkReference(null, URI.create("http://vk.se/"), URI.create("http://vk.se/md1"), null);
	}

	@Test
	public void builtinType() {
		// Checking that builtintype cannot be changed for local resources
		try {
			listEntry.setResourceType(ResourceType.None);
			assertTrue("Succesfully (and erronously) changed the builtintype" + " of a local resource!", false);
		} catch (RepositoryException re) {
		}

		// Checking that builtintype CAN be changed for links.
		assertTrue(linkEntry.getResourceType() == ResourceType.None);
		linkEntry.setResourceType(ResourceType.List);
		assertTrue(linkEntry.getResourceType() == ResourceType.List);

		// Checking that builtintype CAN be changed for references.
		assertTrue(refEntry.getResourceType() == ResourceType.None);
		refEntry.setResourceType(ResourceType.List);
		assertTrue(refEntry.getResourceType() == ResourceType.List);
	}

	@Test
	public void referenceType() {
		assertTrue(listEntry.getLocationType() == EntryType.Local);
		assertTrue(linkEntry.getLocationType() == EntryType.Link);
		assertTrue(refEntry.getLocationType() == EntryType.Reference);
	}

	@Test
	public void representationType() {
		assertTrue(listEntry.getRepresentationType() == RepresentationType.InformationResource);
		// Checking that representationtype cannot be changed for local
		// resources
		try {
			listEntry.setRepresentationType(RepresentationType.NamedResource);
			assertTrue("Succesfully (and erronously) changed the representationtype" + " of a local resource!", false);
		} catch (RepositoryException re) {
		}

		assertTrue(linkEntry.getRepresentationType() == RepresentationType.InformationResource);
		linkEntry.setRepresentationType(RepresentationType.NamedResource);
		assertTrue(linkEntry.getRepresentationType() == RepresentationType.NamedResource);

		assertTrue(refEntry.getRepresentationType() == RepresentationType.InformationResource);
		refEntry.setRepresentationType(RepresentationType.Unknown);
		assertTrue(refEntry.getRepresentationType() == RepresentationType.Unknown);
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
		Graph mmdGraph = listEntry.getGraph();
//		assertTrue(mmdGraph.size() == 6);
		assertTrue(mmdGraph.match(null, RepositoryProperties.resource, null).hasNext());
		assertTrue(mmdGraph.match(null, RepositoryProperties.metadata, null).hasNext());
		assertTrue(mmdGraph.match(null, RepositoryProperties.Created, null).hasNext());
		assertTrue(mmdGraph.match(null, RDF.TYPE, null).hasNext());

		assertTrue(refEntry.getExternalMetadataCacheDate() == null);
		refEntry.getCachedExternalMetadata().setGraph(new GraphImpl());
		assertTrue(refEntry.getExternalMetadataCacheDate() != null);

		assertTrue(refLinkEntry.getExternalMetadataCacheDate() == null);
		refLinkEntry.getCachedExternalMetadata().setGraph(new GraphImpl());
		assertTrue(refLinkEntry.getExternalMetadataCacheDate() != null);

	}

	@Test
	public void setEntryGraph() {
		Graph mmdGraph = listEntry.getGraph();
		listEntry.setGraph(mmdGraph);
		Graph mmdGraph2 = listEntry.getGraph();
		assertTrue(mmdGraph.size() == mmdGraph2.size());
	}
	
	@Test
	public void refLocalEntry() {
		Entry ref = context.createReference(null, linkEntry.getResourceURI(), linkEntry.getLocalMetadataURI(), null);
		int sizeBefore = ref.getCachedExternalMetadata().getGraph().size();
		int sizeAfter = ref.getCachedExternalMetadata().getGraph().size();
		assertTrue(ref.getCachedExternalMetadata().getGraph().size() == linkEntry.getLocalMetadata().getGraph().size());
		assertTrue(sizeBefore != sizeAfter);
	}

}