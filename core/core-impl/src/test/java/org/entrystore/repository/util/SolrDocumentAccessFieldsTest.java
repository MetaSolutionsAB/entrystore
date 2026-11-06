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

package org.entrystore.repository.util;

import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.common.SolrInputDocument;
import org.eclipse.rdf4j.model.Model;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.entrystore.Entry;
import org.entrystore.GraphType;
import org.entrystore.PrincipalManager.AccessProperty;
import org.entrystore.impl.AbstractCoreTest;
import org.entrystore.impl.ContextImpl;
import org.entrystore.impl.RepositoryProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * The fields of an entry's Solr document that {@link SolrReadAccessFilter} reads beyond the {@code acl.*} fields:
 * whether the entry has an ACL of its own, and which local entry a reference takes its indexed metadata from.
 */
class SolrDocumentAccessFieldsTest extends AbstractCoreTest {

	private static final ValueFactory vf = SimpleValueFactory.getInstance();

	private SolrSearchIndex index;

	private ContextImpl context;

	@BeforeEach
	@Override
	public void setUp() {
		super.setUp();
		rm.setCheckForAuthorization(false);
		context = newContext();
		index = new SolrSearchIndex(rm, mock(SolrClient.class));
	}

	@AfterEach
	public void shutDownIndex() {
		index.shutdown();
	}

	@Test
	void documentOfAReferenceToALocalEntryNamesThatEntry() {
		Entry target = context.createResource(null, GraphType.None, null, null);
		Entry reference = context.createReference(null, URI.create("https://example.org/resource"),
				target.getLocalMetadataURI(), null);

		SolrInputDocument doc = index.constructSolrInputDocument(reference, false);

		assertEquals(target.getEntryURI().toString(), doc.getFieldValue(SolrSearchIndex.FIELD_REFERENCED_ENTRY));
	}

	@Test
	void documentOfALinkReferenceToALocalEntryNamesThatEntry() {
		Entry target = context.createResource(null, GraphType.None, null, null);
		Entry linkReference = context.createLinkReference(null, URI.create("https://example.org/resource"),
				target.getLocalMetadataURI(), null);

		SolrInputDocument doc = index.constructSolrInputDocument(linkReference, false);

		assertEquals(target.getEntryURI().toString(), doc.getFieldValue(SolrSearchIndex.FIELD_REFERENCED_ENTRY));
	}

	@Test
	void documentOfAReferenceToRemoteMetadataNamesNoEntry() {
		Entry reference = context.createReference(null, URI.create("https://example.org/resource"),
				URI.create("https://example.org/metadata"), null);

		SolrInputDocument doc = index.constructSolrInputDocument(reference, false);

		// the cached remote metadata is governed by the reference's own ACL, so the document is judged by it
		assertNull(doc.getFieldValue(SolrSearchIndex.FIELD_REFERENCED_ENTRY));
	}

	@Test
	void documentOfAnEntryWithAnAclOfItsOwnIsMarkedExplicit() {
		Entry entry = context.createResource(null, GraphType.None, null, null);
		entry.setAllowedPrincipalsFor(AccessProperty.ReadMetadata, Set.of(pm.getGuestUser().getURI()));

		SolrInputDocument doc = index.constructSolrInputDocument(entry, false);

		assertEquals(true, doc.getFieldValue(SolrSearchIndex.FIELD_ACL_EXPLICIT));
	}

	@Test
	void documentOfAnEntryWithoutAnAclOfItsOwnIsMarkedNotExplicit() {
		Entry entry = context.createResource(null, GraphType.None, null, null);

		SolrInputDocument doc = index.constructSolrInputDocument(entry, false);

		assertEquals(false, doc.getFieldValue(SolrSearchIndex.FIELD_ACL_EXPLICIT));
	}

	@Test
	void documentOfAnEntryWhoseAclNamesOnlyAnotherSubjectIsMarkedExplicit() {
		Entry entry = context.createResource(null, GraphType.None, null, null);
		Model entryInfo = entry.getGraph();
		entryInfo.add(vf.createIRI("https://example.org/other-subject"), RepositoryProperties.Read,
				vf.createIRI(pm.getGuestUser().getURI().toString()));
		entry.setGraph(entryInfo);

		SolrInputDocument doc = index.constructSolrInputDocument(entry, false);

		// such an ACL still replaces the context's, so the filter must not let the entry inherit the context's
		assertEquals(true, doc.getFieldValue(SolrSearchIndex.FIELD_ACL_EXPLICIT));
		assertTrue(doc.getFieldNames().stream().noneMatch(name -> name.startsWith("acl.metadata")),
				doc.getFieldNames().toString());
	}
}
