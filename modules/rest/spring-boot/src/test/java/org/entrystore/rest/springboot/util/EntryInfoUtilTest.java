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

package org.entrystore.rest.springboot.util;

import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Model;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.LinkedHashModel;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.model.vocabulary.RDF;
import org.entrystore.impl.RepositoryProperties;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EntryInfoUtilTest {

	private static final ValueFactory VF = SimpleValueFactory.getInstance();
	private static final String BASE = "http://example.com/store/1/";
	private static final IRI ENTRY = VF.createIRI(BASE + "entry/2");
	private static final IRI RESOURCE = VF.createIRI(BASE + "resource/2");
	private static final IRI METADATA = VF.createIRI(BASE + "metadata/2");
	private static final IRI RELATION = VF.createIRI(BASE + "relations/2");
	private static final IRI CACHED_MD = VF.createIRI(BASE + "cached-external-metadata/2");
	private static final IRI USER = VF.createIRI("http://example.com/store/_principals/resource/7");

	@Test
	void reduceForNonReader_localFileEntry_keepsTypesUrisAndDates() {
		Model graph = localEntryGraph();
		graph.add(RESOURCE, RDF.TYPE, RepositoryProperties.None);
		graph.add(RESOURCE, RDF.TYPE, RepositoryProperties.InformationResource);

		Model reduced = reduce(graph);

		Model expected = new LinkedHashModel();
		expected.add(ENTRY, RDF.TYPE, RepositoryProperties.Local);
		expected.add(ENTRY, RepositoryProperties.resource, RESOURCE);
		expected.add(ENTRY, RepositoryProperties.metadata, METADATA);
		expected.add(ENTRY, RepositoryProperties.relation, RELATION);
		expected.add(ENTRY, RepositoryProperties.Created, VF.createLiteral(new Date(1000)));
		expected.add(ENTRY, RepositoryProperties.Modified, VF.createLiteral(new Date(2000)));
		expected.add(RESOURCE, RDF.TYPE, RepositoryProperties.None);
		expected.add(RESOURCE, RDF.TYPE, RepositoryProperties.InformationResource);
		assertEquals(expected, reduced);
	}

	@Test
	void reduceForNonReader_localFileEntry_dropsCreatorContributorsFileDetailsAndStatus() {
		Model graph = localEntryGraph();
		graph.add(ENTRY, RepositoryProperties.Creator, USER);
		graph.add(ENTRY, RepositoryProperties.Contributor, USER);
		graph.add(ENTRY, RepositoryProperties.status, RepositoryProperties.Pending);
		graph.add(ENTRY, RepositoryProperties.originallyCreatedIn, VF.createIRI(BASE + "entry/1"));
		graph.add(ENTRY, RepositoryProperties.wasRevisionOf, VF.createIRI(BASE + "entry/2?rev=1"));
		graph.add(RESOURCE, RepositoryProperties.filename, VF.createLiteral("salaries.xlsx"));
		graph.add(RESOURCE, RepositoryProperties.format, VF.createLiteral("application/vnd.ms-excel"));
		graph.add(RESOURCE, RepositoryProperties.fileSize, VF.createLiteral(4711L));

		Model reduced = reduce(graph);

		assertEquals(localEntryGraph(), reduced);
	}

	@Test
	void reduceForNonReader_entryWithOwnAcl_dropsAclPrincipals() {
		Model graph = localEntryGraph();
		graph.add(ENTRY, RepositoryProperties.Write, USER);
		graph.add(METADATA, RepositoryProperties.Read, USER);
		graph.add(RESOURCE, RepositoryProperties.Read, USER);

		Model reduced = reduce(graph);

		assertTrue(reduced.filter(null, RepositoryProperties.Read, null).isEmpty());
		assertTrue(reduced.filter(null, RepositoryProperties.Write, null).isEmpty());
	}

	@Test
	void reduceForNonReader_unknownPredicate_isDropped() {
		Model graph = localEntryGraph();
		IRI custom = VF.createIRI("http://example.com/terms/projectType");
		graph.add(ENTRY, custom, VF.createLiteral("secret project"));
		graph.add(RESOURCE, custom, VF.createLiteral("secret project"));

		Model reduced = reduce(graph);

		assertTrue(reduced.filter(null, custom, null).isEmpty());
	}

	@Test
	void reduceForNonReader_allowedPredicateOnAnotherSubject_isDropped() {
		Model graph = localEntryGraph();
		graph.add(METADATA, RDF.TYPE, VF.createIRI("http://example.com/terms/Secret"));
		graph.add(USER, RepositoryProperties.Created, VF.createLiteral(new Date(3000)));
		graph.add(ENTRY, RepositoryProperties.homeContext, VF.createIRI(BASE));

		Model reduced = reduce(graph);

		assertEquals(localEntryGraph(), reduced);
	}

	@Test
	void reduceForNonReader_linkReferenceEntry_keepsExternalMetadataAndCachedDate() {
		Model graph = new LinkedHashModel();
		IRI external = VF.createIRI("https://example.org/metadata");
		IRI resource = VF.createIRI("https://example.org/dataset");
		graph.add(ENTRY, RDF.TYPE, RepositoryProperties.LinkReference);
		graph.add(ENTRY, RepositoryProperties.resource, resource);
		graph.add(ENTRY, RepositoryProperties.externalMetadata, external);
		graph.add(ENTRY, RepositoryProperties.cachedExternalMetadata, CACHED_MD);
		graph.add(CACHED_MD, RepositoryProperties.cached, VF.createLiteral(new Date(5000)));
		graph.add(resource, RDF.TYPE, RepositoryProperties.InformationResource);
		graph.add(ENTRY, RepositoryProperties.Creator, USER);

		Model reduced = EntryInfoUtil.reduceForNonReader(graph, URI.create(ENTRY.stringValue()),
				URI.create(resource.stringValue()), URI.create(CACHED_MD.stringValue()));

		Model expected = new LinkedHashModel(graph);
		expected.remove(ENTRY, RepositoryProperties.Creator, USER);
		assertEquals(expected, reduced);
	}

	@Test
	void reduceForNonReader_groupEntry_keepsHomeContextOfTheResource() {
		Model graph = localEntryGraph();
		IRI homeContext = VF.createIRI("http://example.com/store/_contexts/entry/5");
		graph.add(RESOURCE, RDF.TYPE, RepositoryProperties.Group);
		graph.add(RESOURCE, RepositoryProperties.homeContext, homeContext);

		Model reduced = reduce(graph);

		assertEquals(1, reduced.filter(RESOURCE, RepositoryProperties.homeContext, homeContext).size());
		assertEquals(1, reduced.filter(RESOURCE, RDF.TYPE, RepositoryProperties.Group).size());
	}

	@Test
	void reduceForNonReader_keepsNamedGraphOfEachStatement() {
		Model graph = new LinkedHashModel();
		graph.add(ENTRY, RDF.TYPE, RepositoryProperties.Local, ENTRY);

		Model reduced = reduce(graph);

		assertEquals(1, reduced.filter(ENTRY, RDF.TYPE, RepositoryProperties.Local, ENTRY).size());
	}

	private static Model localEntryGraph() {
		Model graph = new LinkedHashModel();
		graph.add(ENTRY, RDF.TYPE, RepositoryProperties.Local);
		graph.add(ENTRY, RepositoryProperties.resource, RESOURCE);
		graph.add(ENTRY, RepositoryProperties.metadata, METADATA);
		graph.add(ENTRY, RepositoryProperties.relation, RELATION);
		graph.add(ENTRY, RepositoryProperties.Created, VF.createLiteral(new Date(1000)));
		graph.add(ENTRY, RepositoryProperties.Modified, VF.createLiteral(new Date(2000)));
		return graph;
	}

	private static Model reduce(Model graph) {
		return EntryInfoUtil.reduceForNonReader(graph, URI.create(ENTRY.stringValue()),
				URI.create(RESOURCE.stringValue()), null);
	}
}
