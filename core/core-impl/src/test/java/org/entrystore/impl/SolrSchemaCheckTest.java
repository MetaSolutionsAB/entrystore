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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The guard must refuse to start on a verifiable misconfiguration (missing fields, a denied schema read, a core that
 * does not exist), while an unreachable Solr must keep the established startup behaviour and report the schema as
 * unverified.
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
	void requireDynamicFields_reportsTheSchemaVerifiedWhenEveryRequiredFieldIsDeclared() throws Exception {
		SolrClient client = solrDeclaring("metadata.predicate.uri.*", "metadata.predicate.literal_l.*",
				"related.metadata.predicate.literal_l.*");

		assertTrue(SolrSchemaCheck.requireDynamicFields(client, SOLR_URL, REQUIRED));
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
	void requireDynamicFields_letsAnUnreachableSolrThroughUnverified() throws Exception {
		SolrClient client = mock(SolrClient.class);
		when(client.request(any(), any())).thenThrow(new SolrServerException("connection refused"));

		assertFalse(SolrSchemaCheck.requireDynamicFields(client, SOLR_URL, REQUIRED));
	}

	@Test
	void requireDynamicFields_letsASolrStillLoadingTheCoreThroughUnverified() throws Exception {
		SolrClient client = mock(SolrClient.class);
		when(client.request(any(), any()))
				.thenThrow(new RemoteSolrException("solr.example.org", 503, "SolrCore is loading", null));

		assertFalse(SolrSchemaCheck.requireDynamicFields(client, SOLR_URL, REQUIRED));
	}

	@Test
	void requireDynamicFields_refusesWhenTheConfiguredCoreDoesNotExist() throws Exception {
		SolrClient client = mock(SolrClient.class);
		when(client.request(any(), any())).thenThrow(new RemoteSolrException("solr.example.org", 404, "Not Found", null));

		IllegalStateException thrown = assertThrows(IllegalStateException.class,
				() -> SolrSchemaCheck.requireDynamicFields(client, SOLR_URL, REQUIRED));

		assertTrue(thrown.getMessage().contains("entrystore.solr.url"), thrown.getMessage());
		assertTrue(thrown.getMessage().contains("http://host:8983/solr/<core>"), thrown.getMessage());
	}

	@Test
	void requireDynamicFields_refusesWhenSolrDeniesTheSchemaRequest() throws Exception {
		for (int status : new int[] {401, 403}) {
			SolrClient client = mock(SolrClient.class);
			when(client.request(any(), any()))
					.thenThrow(new RemoteSolrException("solr.example.org", status, "Unauthorized", null));

			IllegalStateException thrown = assertThrows(IllegalStateException.class,
					() -> SolrSchemaCheck.requireDynamicFields(client, "http://solr.example.org/entrystore",
							List.of("metadata.predicate.literal_l.*")));

			assertTrue(thrown.getMessage().contains(Integer.toString(status)), thrown.getMessage());
			assertTrue(thrown.getMessage().contains("permission"),
					"the operator needs to be told this is an access problem, not a schema one: " + thrown.getMessage());
		}
	}

	@Test
	void requireDynamicFields_letsAnUnexpectedSolrErrorThrough() throws Exception {
		SolrClient client = mock(SolrClient.class);
		when(client.request(any(), any()))
				.thenThrow(new RemoteSolrException("solr.example.org", 500, "Internal Server Error", null));

		assertFalse(SolrSchemaCheck.requireDynamicFields(client, "http://solr.example.org/entrystore",
				List.of("metadata.predicate.literal_l.*")),
				"a Solr fault is not evidence the schema is wrong, so startup continues, but unverified");
	}

	@Test
	void dynamicFieldNames_listsTheDeclaredNames() throws Exception {
		assertEquals(Set.of("a.*", "b.*"), SolrSchemaCheck.dynamicFieldNames(solrDeclaring("a.*", "b.*")));
	}
}
