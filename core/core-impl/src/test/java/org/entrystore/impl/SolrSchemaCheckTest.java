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
	private static final List<String> REQUIRED = List.of("acl.explicit", "referencedEntry",
			"metadata.predicate.literal_l.*", "related.metadata.predicate.literal_l.*");

	/**
	 * A client whose Schema API answers declare exactly the given fields and dynamic fields, in the shape SolrJ
	 * parses; both requests get the same answer, and each reads only its own key.
	 */
	private static SolrClient solrDeclaring(List<String> fieldNames, List<String> dynamicFieldNames) throws Exception {
		NamedList<Object> response = new NamedList<>();
		response.add("responseHeader", new SimpleOrderedMap<>());
		response.add("fields", namedFields(fieldNames));
		response.add("dynamicFields", namedFields(dynamicFieldNames));
		SolrClient client = mock(SolrClient.class);
		when(client.request(any(), any())).thenReturn(response);
		return client;
	}

	private static List<SimpleOrderedMap<Object>> namedFields(List<String> names) {
		List<SimpleOrderedMap<Object>> fields = new ArrayList<>();
		for (String name : names) {
			SimpleOrderedMap<Object> field = new SimpleOrderedMap<>();
			field.add("name", name);
			field.add("type", "string");
			fields.add(field);
		}
		return fields;
	}

	@Test
	void requireFields_reportsTheSchemaVerifiedWhenEveryRequiredFieldIsDeclared() throws Exception {
		SolrClient client = solrDeclaring(List.of("uri", "acl.explicit", "referencedEntry"),
				List.of("metadata.predicate.uri.*", "metadata.predicate.literal_l.*",
						"related.metadata.predicate.literal_l.*"));

		assertTrue(SolrSchemaCheck.requireFields(client, SOLR_URL, REQUIRED));
	}

	@Test
	void requireFields_refusesWhenARequiredDynamicFieldIsMissing() throws Exception {
		SolrClient client = solrDeclaring(List.of("acl.explicit", "referencedEntry"),
				List.of("metadata.predicate.uri.*", "metadata.predicate.literal_l.*"));

		IllegalStateException ex = assertThrows(IllegalStateException.class,
				() -> SolrSchemaCheck.requireFields(client, SOLR_URL, REQUIRED));

		assertTrue(ex.getMessage().contains("related.metadata.predicate.literal_l.*"), ex.getMessage());
		assertTrue(ex.getMessage().contains(SOLR_URL), ex.getMessage());
	}

	@Test
	void requireFields_refusesWhenARequiredFieldIsMissing() throws Exception {
		// a schema from before the read-access filter: every dynamic field, but not the fields the filter reads
		SolrClient client = solrDeclaring(List.of("uri", "acl.admin"),
				List.of("metadata.predicate.literal_l.*", "related.metadata.predicate.literal_l.*"));

		IllegalStateException ex = assertThrows(IllegalStateException.class,
				() -> SolrSchemaCheck.requireFields(client, SOLR_URL, REQUIRED));

		assertTrue(ex.getMessage().contains("acl.explicit"), ex.getMessage());
		assertTrue(ex.getMessage().contains("referencedEntry"), ex.getMessage());
	}

	@Test
	void requireFields_letsAnUnreachableSolrThroughUnverified() throws Exception {
		SolrClient client = mock(SolrClient.class);
		when(client.request(any(), any())).thenThrow(new SolrServerException("connection refused"));

		assertFalse(SolrSchemaCheck.requireFields(client, SOLR_URL, REQUIRED));
	}

	@Test
	void requireFields_letsASolrStillLoadingTheCoreThroughUnverified() throws Exception {
		SolrClient client = mock(SolrClient.class);
		when(client.request(any(), any()))
				.thenThrow(new RemoteSolrException("solr.example.org", 503, "SolrCore is loading", null));

		assertFalse(SolrSchemaCheck.requireFields(client, SOLR_URL, REQUIRED));
	}

	@Test
	void requireFields_refusesWhenTheConfiguredCoreDoesNotExist() throws Exception {
		SolrClient client = mock(SolrClient.class);
		when(client.request(any(), any())).thenThrow(new RemoteSolrException("solr.example.org", 404, "Not Found", null));

		IllegalStateException thrown = assertThrows(IllegalStateException.class,
				() -> SolrSchemaCheck.requireFields(client, SOLR_URL, REQUIRED));

		assertTrue(thrown.getMessage().contains("entrystore.solr.url"), thrown.getMessage());
		assertTrue(thrown.getMessage().contains("http://host:8983/solr/<core>"), thrown.getMessage());
	}

	@Test
	void requireFields_refusesWhenSolrDeniesTheSchemaRequest() throws Exception {
		for (int status : new int[] {401, 403}) {
			SolrClient client = mock(SolrClient.class);
			when(client.request(any(), any()))
					.thenThrow(new RemoteSolrException("solr.example.org", status, "Unauthorized", null));

			IllegalStateException thrown = assertThrows(IllegalStateException.class,
					() -> SolrSchemaCheck.requireFields(client, SOLR_URL, REQUIRED));

			assertTrue(thrown.getMessage().contains(Integer.toString(status)), thrown.getMessage());
			assertTrue(thrown.getMessage().contains("permission"),
					"the operator needs to be told this is an access problem, not a schema one: " + thrown.getMessage());
		}
	}

	@Test
	void requireFields_letsAnUnexpectedSolrErrorThrough() throws Exception {
		SolrClient client = mock(SolrClient.class);
		when(client.request(any(), any()))
				.thenThrow(new RemoteSolrException("solr.example.org", 500, "Internal Server Error", null));

		assertFalse(SolrSchemaCheck.requireFields(client, SOLR_URL, REQUIRED),
				"a Solr fault is not evidence the schema is wrong, so startup continues, but unverified");
	}

	@Test
	void dynamicFieldNames_listsTheDeclaredNames() throws Exception {
		assertEquals(Set.of("a.*", "b.*"),
				SolrSchemaCheck.dynamicFieldNames(solrDeclaring(List.of("c"), List.of("a.*", "b.*"))));
	}

	@Test
	void fieldNames_listsTheDeclaredNames() throws Exception {
		assertEquals(Set.of("a", "b"), SolrSchemaCheck.fieldNames(solrDeclaring(List.of("a", "b"), List.of("c.*"))));
	}
}
