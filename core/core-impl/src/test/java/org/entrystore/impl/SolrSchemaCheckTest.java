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

import org.apache.solr.client.solrj.RemoteSolrException;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.common.util.NamedList;
import org.apache.solr.common.util.SimpleOrderedMap;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The guard must refuse to start only on a verifiable schema mismatch: a schema that lacks the fields is a fatal
 * misconfiguration, while an unreachable Solr must keep the established startup behaviour.
 */
class SolrSchemaCheckTest {

	private static final String SOLR_URL = "http://solr.example.org/solr/entrystore-core";
	private static final List<String> REQUIRED =
			List.of("metadata.predicate.literal_l.*", "related.metadata.predicate.literal_l.*");

	/** A client whose Schema API answer declares exactly the given dynamic fields, in the shape SolrJ parses. */
	private static SolrClient solrDeclaring(String... dynamicFieldNames) throws Exception {
		List<SimpleOrderedMap<Object>> fields = new ArrayList<>();
		for (String name : dynamicFieldNames) {
			SimpleOrderedMap<Object> field = new SimpleOrderedMap<>();
			field.add("name", name);
			field.add("type", "string");
			fields.add(field);
		}
		NamedList<Object> response = new NamedList<>();
		response.add("responseHeader", new SimpleOrderedMap<>());
		response.add("dynamicFields", fields);
		SolrClient client = mock(SolrClient.class);
		when(client.request(any(), any())).thenReturn(response);
		return client;
	}

	@Test
	void requireDynamicFields_passesWhenEveryRequiredFieldIsDeclared() throws Exception {
		SolrClient client = solrDeclaring("metadata.predicate.uri.*", "metadata.predicate.literal_l.*",
				"related.metadata.predicate.literal_l.*");

		assertDoesNotThrow(() -> SolrSchemaCheck.requireDynamicFields(client, SOLR_URL, REQUIRED));
	}

	@Test
	void requireDynamicFields_refusesWhenARequiredFieldIsMissing() throws Exception {
		SolrClient client = solrDeclaring("metadata.predicate.uri.*", "metadata.predicate.literal_l.*");

		IllegalStateException ex = assertThrows(IllegalStateException.class,
				() -> SolrSchemaCheck.requireDynamicFields(client, SOLR_URL, REQUIRED));

		assertTrue(ex.getMessage().contains("related.metadata.predicate.literal_l.*"), ex.getMessage());
		assertTrue(ex.getMessage().contains(SOLR_URL), ex.getMessage());
	}

	@Test
	void requireDynamicFields_letsAnUnreachableSolrThrough() throws Exception {
		SolrClient client = mock(SolrClient.class);
		when(client.request(any(), any())).thenThrow(new SolrServerException("connection refused"));

		assertDoesNotThrow(() -> SolrSchemaCheck.requireDynamicFields(client, SOLR_URL, REQUIRED));
	}

	@Test
	void requireDynamicFields_letsASolrWithoutTheSchemaApiThrough() throws Exception {
		SolrClient client = mock(SolrClient.class);
		when(client.request(any(), any())).thenThrow(new RemoteSolrException("solr.example.org", 404, "Not Found", null));

		assertDoesNotThrow(() -> SolrSchemaCheck.requireDynamicFields(client, SOLR_URL, REQUIRED));
	}

	@Test
	void dynamicFieldNames_listsTheDeclaredNames() throws Exception {
		assertEquals(Set.of("a.*", "b.*"), SolrSchemaCheck.dynamicFieldNames(solrDeclaring("a.*", "b.*")));
	}
}
