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

package org.entrystore.rest.springboot.security;

import org.apache.commons.lang3.exception.ExceptionUtils;
import org.eclipse.rdf4j.query.GraphQuery;
import org.eclipse.rdf4j.query.QueryEvaluationException;
import org.eclipse.rdf4j.query.QueryLanguage;
import org.eclipse.rdf4j.query.QueryResults;
import org.eclipse.rdf4j.query.TupleQuery;
import org.eclipse.rdf4j.repository.Repository;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.eclipse.rdf4j.sail.memory.MemoryStore;
import org.entrystore.rest.springboot.model.exception.BadRequestException;
import org.entrystore.rest.springboot.model.exception.InternalServerErrorException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class FederatedQueryGuardTest {

	private static final String REJECTED = "SERVICE rejected";
	// A closed port: a SERVICE request that is really sent fails with a connection error, not the deny message.
	private static final String ENDPOINT = "<http://127.0.0.1:9/sparql>";

	private static Repository repository;

	@BeforeAll
	static void initRepository() {
		MemoryStore store = new MemoryStore();
		store.setFederatedServiceResolver(FederatedQueryGuard.DENY_ALL);
		repository = new SailRepository(store);
		repository.init();
	}

	@AfterAll
	static void shutdownRepository() {
		repository.shutDown();
	}

	private static String construct(String pattern) {
		return "CONSTRUCT { ?s ?p ?o } WHERE { " + pattern.replace("ENDPOINT", ENDPOINT) + " }";
	}

	@ParameterizedTest(name = "{0}")
	@CsvSource(delimiter = '|', textBlock = """
			plain                | SERVICE ENDPOINT { ?s ?p ?o }
			SILENT               | SERVICE SILENT ENDPOINT { ?s ?p ?o }
			variable endpoint    | VALUES ?endpoint { ENDPOINT } SERVICE ?endpoint { ?s ?p ?o }
			in OPTIONAL          | ?s ?p ?o OPTIONAL { SERVICE ENDPOINT { ?s ?p ?x } }
			in UNION             | { ?s ?p ?o } UNION { SERVICE ENDPOINT { ?s ?p ?o } }
			in MINUS             | ?s ?p ?o MINUS { SERVICE ENDPOINT { ?s ?p ?o } }
			in subselect         | { SELECT ?s ?p ?o WHERE { SERVICE ENDPOINT { ?s ?p ?o } } }
			in FILTER EXISTS     | ?s ?p ?o FILTER EXISTS { SERVICE ENDPOINT { ?s ?p ?x } }
			in FILTER NOT EXISTS | ?s ?p ?o FILTER NOT EXISTS { SERVICE ENDPOINT { ?s ?p ?x } }
			in BIND(EXISTS)      | ?s ?p ?o BIND(EXISTS { SERVICE ENDPOINT { ?s ?p ?x } } AS ?found)
			""")
	void rejectServiceClauses_graphQueryWithService_throwsBadRequest(String description, String pattern) {
		try (RepositoryConnection rc = repository.getConnection()) {
			GraphQuery query = rc.prepareGraphQuery(QueryLanguage.SPARQL, construct(pattern));

			BadRequestException ex = assertThrows(BadRequestException.class,
					() -> FederatedQueryGuard.rejectServiceClauses(query, REJECTED));
			assertEquals(REJECTED, ex.getMessage());
		}
	}

	@Test
	void rejectServiceClauses_tupleQueryWithService_throwsBadRequest() {
		try (RepositoryConnection rc = repository.getConnection()) {
			TupleQuery query = rc.prepareTupleQuery(QueryLanguage.SPARQL,
					"SELECT ?s WHERE { SERVICE " + ENDPOINT + " { ?s ?p ?o } }");

			BadRequestException ex = assertThrows(BadRequestException.class,
					() -> FederatedQueryGuard.rejectServiceClauses(query, REJECTED));
			assertEquals(REJECTED, ex.getMessage());
		}
	}

	@Test
	void rejectServiceClauses_serviceOnlyInLiteralAndIri_passes() {
		// The check runs on the parsed algebra, so the word SERVICE in a term is not a SERVICE clause.
		try (RepositoryConnection rc = repository.getConnection()) {
			GraphQuery query = rc.prepareGraphQuery(QueryLanguage.SPARQL,
					"CONSTRUCT { ?s <http://example.org/SERVICE> ?o } WHERE { ?s ?p ?o "
							+ "FILTER(?o != \"SERVICE " + ENDPOINT + " { ?s ?p ?o }\") }");

			assertDoesNotThrow(() -> FederatedQueryGuard.rejectServiceClauses(query, REJECTED));
		}
	}

	@Test
	void rejectServiceClauses_nonSailQuery_failsClosedNamingTheQueryClass() {
		GraphQuery nonSailQuery = mock(GraphQuery.class);

		InternalServerErrorException ex = assertThrows(InternalServerErrorException.class,
				() -> FederatedQueryGuard.rejectServiceClauses(nonSailQuery, REJECTED));
		// AppExceptionHandler logs this message but answers only "Internal Server Error".
		assertTrue(ex.getMessage().contains(nonSailQuery.getClass().getName()), ex.getMessage());
		assertNull(ex.getCause());
	}

	@Test
	void denyAll_serviceQueryEvaluatedWithoutGuard_isRefusedByResolver() {
		try (RepositoryConnection rc = repository.getConnection()) {
			GraphQuery query = rc.prepareGraphQuery(QueryLanguage.SPARQL,
					construct("SERVICE ENDPOINT { ?s ?p ?o }"));

			QueryEvaluationException ex = assertThrows(QueryEvaluationException.class,
					() -> QueryResults.asModel(query.evaluate()));
			assertTrue(ExceptionUtils.getThrowableList(ex).stream()
							.map(Throwable::getMessage)
							.anyMatch("SPARQL SERVICE clauses are not permitted"::equals),
					"Expected the DENY_ALL refusal, got: " + ex);
		}
	}
}
