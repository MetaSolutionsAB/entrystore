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

package org.entrystore.rest.springboot.service;

import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Model;
import org.eclipse.rdf4j.model.Resource;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.query.BindingSet;
import org.eclipse.rdf4j.query.QueryLanguage;
import org.eclipse.rdf4j.query.TupleQuery;
import org.eclipse.rdf4j.query.TupleQueryResult;
import org.eclipse.rdf4j.query.impl.SimpleDataset;
import org.eclipse.rdf4j.query.resultio.QueryResultIO;
import org.eclipse.rdf4j.query.resultio.TupleQueryResultFormat;
import org.eclipse.rdf4j.query.resultio.helpers.QueryResultCollector;
import org.eclipse.rdf4j.repository.Repository;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.RepositoryResult;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.eclipse.rdf4j.rio.RDFFormat;
import org.eclipse.rdf4j.rio.Rio;
import org.eclipse.rdf4j.sail.memory.MemoryStore;
import org.entrystore.impl.PublicRepository;
import org.entrystore.impl.RepositoryManagerImpl;
import org.entrystore.rest.springboot.util.SparqlResultFormat;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.StringReader;
import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;

/**
 * Compares {@code /sparql} results with a reference: the same query evaluated with the context graphs as its
 * default graph. Results are compared as multisets, since only the number of repetitions is meant to change.
 */
@ExtendWith(MockitoExtension.class)
class SparqlServiceContextGraphTest {

	private static final String BASE = "http://example.org/store/";
	private static final String PREFIXES = "PREFIX ex: <http://example.org/ns#> ";
	private static final List<String> CONTEXT_GRAPHS = List.of(BASE + "1", BASE + "2", BASE + "3");

	private static Repository repository;

	@Mock
	private RepositoryManagerImpl repositoryManager;

	@Mock
	private ContextService contextService;

	@Mock
	private ReservedNamesService reservedNames;

	@Mock
	private PublicRepository publicRepository;

	private SparqlService service;

	/** Filled as PublicRepository fills it: each entry's metadata in its own graph and in its context's graph. */
	@BeforeAll
	static void fillRepository() throws IOException {
		repository = new SailRepository(new MemoryStore());
		repository.init();
		try (RepositoryConnection rc = repository.getConnection()) {
			addEntry(rc, "1", "metadata/1", """
					ex:a1 a ex:Award ; ex:laureate ex:p1 ; ex:year 1901 ; ex:label "A1"@en, "A1"@sv ; ex:next ex:a2 .
					""");
			addEntry(rc, "1", "metadata/2", """
					ex:a2 a ex:Award ; ex:laureate ex:p2 ; ex:year 1902 ; ex:label "A2"@en ; ex:next ex:a3 .
					""");
			addEntry(rc, "1", "metadata/3", "ex:p1 ex:name \"P1\" .");
			addEntry(rc, "1", "resource/4", "ex:r1 ex:label \"R1\" .");
			addEntry(rc, "2", "metadata/1", "ex:a3 a ex:Award ; ex:laureate ex:p1 ; ex:year 1903 .");
			addEntry(rc, "2", "metadata/2", "ex:p2 ex:name \"P2\" .");
			addEntry(rc, "2", "metadata/3", "ex:shared ex:name \"Shared\" .");
			addEntry(rc, "3", "metadata/1", "ex:shared ex:name \"Shared\" .");
			addEntry(rc, "3", "cached-external-metadata/2", "ex:p3 ex:name \"P3\" ; ex:next ex:p3 .");
		}
	}

	private static void addEntry(RepositoryConnection rc, String contextId, String graphPath, String turtle)
			throws IOException {
		ValueFactory vf = rc.getValueFactory();
		Model metadata = Rio.parse(new StringReader("@prefix ex: <http://example.org/ns#> . " + turtle),
				RDFFormat.TURTLE);
		rc.add(metadata, vf.createIRI(BASE + contextId + "/" + graphPath), vf.createIRI(BASE + contextId));
	}

	@AfterAll
	static void shutDown() {
		repository.shutDown();
	}

	@BeforeEach
	void setUp() throws Exception {
		lenient().when(repositoryManager.getPublicRepository()).thenReturn(publicRepository);
		lenient().when(publicRepository.getConnection()).thenAnswer(_ -> repository.getConnection());
		lenient().when(repositoryManager.getRepositoryURL()).thenReturn(URI.create(BASE).toURL());
		service = new SparqlService(repositoryManager, contextService, reservedNames, 10, 64L * 1024 * 1024, 4);
	}

	static Stream<Arguments> queriesWithoutGraphClause() {
		return Stream.of(
				Arguments.of("BGP", "SELECT ?a ?p WHERE { ?a a ex:Award ; ex:laureate ?p }"),
				Arguments.of("OPTIONAL", "SELECT ?a ?l WHERE { ?a a ex:Award OPTIONAL { ?a ex:label ?l } }"),
				Arguments.of("UNION", "SELECT ?x WHERE { { ?x a ex:Award } UNION { ?x ex:name ?n } }"),
				Arguments.of("MINUS", "SELECT ?p ?n WHERE { ?p ex:name ?n MINUS { ?a ex:laureate ?p } }"),
				Arguments.of("FILTER EXISTS", "SELECT ?p WHERE { ?p ex:name ?n FILTER EXISTS { ?a ex:laureate ?p } }"),
				Arguments.of("FILTER NOT EXISTS",
						"SELECT ?p WHERE { ?p ex:name ?n FILTER NOT EXISTS { ?a ex:laureate ?p } }"),
				Arguments.of("subselect",
						"SELECT ?a ?n WHERE { ?a ex:laureate ?p { SELECT ?p ?n WHERE { ?p ex:name ?n } } }"),
				Arguments.of("VALUES", "SELECT ?a ?y WHERE { VALUES ?y { 1901 1903 } ?a ex:year ?y }"),
				Arguments.of("BIND", "SELECT ?a ?next WHERE { ?a ex:year ?y BIND(?y + 1 AS ?next) }"),
				Arguments.of("sequence path a/b", "SELECT ?a ?n WHERE { ?a ex:laureate/ex:name ?n }"),
				Arguments.of("path p+", "SELECT ?a ?b WHERE { ?a ex:next+ ?b }"),
				Arguments.of("COUNT with GROUP BY",
						"SELECT ?p (COUNT(*) AS ?c) WHERE { ?a ex:laureate ?p } GROUP BY ?p"),
				Arguments.of("ORDER BY with LIMIT", "SELECT ?a ?l WHERE { ?a ex:label ?l } ORDER BY ?a ?l LIMIT 3"),
				Arguments.of("SELECT *", "SELECT * WHERE { ?a ex:laureate ?p OPTIONAL { ?p ex:name ?n } }"),
				Arguments.of("several OPTIONALs with DISTINCT and ORDER BY", """
						SELECT DISTINCT ?a ?p ?y ?l ?n WHERE {
						  ?a a ex:Award ; ex:laureate ?p ; ex:year ?y .
						  OPTIONAL { ?a ex:label ?l } OPTIONAL { ?p ex:name ?n }
						  FILTER(?y >= 1901 && ?y <= 1902)
						} ORDER BY ?y ?l
						"""),
				Arguments.of("triple in two contexts", "SELECT ?n WHERE { ex:shared ex:name ?n }"),
				Arguments.of("resource and cached external metadata graphs",
						"SELECT ?s ?o WHERE { ?s ex:label|ex:name ?o FILTER(?s IN (ex:r1, ex:p3)) }"),
				Arguments.of("COUNT(DISTINCT *)",
						"SELECT (COUNT(DISTINCT *) AS ?c) WHERE { { ?s ex:name ?n } UNION { ?s ex:name ?n } }"),
				Arguments.of("helper-like name in VALUES",
						"SELECT ?a ?_entrystore_graph_0 WHERE { VALUES ?_entrystore_graph_0 { 1 } ?a a ex:Award }"),
				Arguments.of("helper-like name in BIND",
						"SELECT ?a ?_entrystore_graph_0 WHERE { ?a a ex:Award BIND(1 AS ?_entrystore_graph_0) }"),
				Arguments.of("helper-like name projected but unbound",
						"SELECT ?a ?_entrystore_graph_0 WHERE { ?a a ex:Award }"));
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("queriesWithoutGraphClause")
	void runQuery_withoutDataset_matchesTheContextGraphsAsDefaultGraph(String name, String query) throws Exception {
		Result expected = evaluate(query, contextGraphsDataset(false));

		Result actual = runQuery(query);

		assertEquals(expected.bindingNames(), actual.bindingNames(), "No helper variable may be projected");
		assertEquals(expected.rows(), actual.rows());
	}

	@Test
	void runQuery_withoutDataset_noLongerRepeatsSolutionsForTheMetadataGraphCopy() throws Exception {
		String query = "SELECT ?a ?p WHERE { ?a a ex:Award ; ex:laureate ?p }";

		Result today = evaluate(query, null);
		Result actual = runQuery(query);

		assertEquals(3, total(actual), "Each award once");
		assertEquals(4 * total(actual), total(today), "Without the rewrite each of the 2 patterns matches twice");
	}

	@Test
	void runQuery_graphClauseOnly_isEvaluatedAsWithoutRewrite() throws Exception {
		String query = "SELECT ?g ?a WHERE { GRAPH ?g { ?a a ex:Award } }";

		assertEquals(evaluate(query, null), runQuery(query));
	}

	@Test
	void runQuery_graphClauseWithDefaultGraphPattern_readsAllNamedGraphsAndTheContextGraphsAsDefault()
			throws Exception {
		String query = "SELECT ?g ?a ?p WHERE { GRAPH ?g { ?a a ex:Award } ?a ex:laureate ?p }";

		Result expected = evaluate(query, contextGraphsDataset(true));
		Result actual = runQuery(query);

		assertEquals(expected, actual);
		assertNotEquals(evaluate(query, null), actual, "The default-graph pattern no longer matches twice");
	}

	@Test
	void runQuery_from_isEvaluatedAsWithoutRewrite() throws Exception {
		String query = "SELECT ?a ?p FROM <" + BASE + "1/metadata/1> WHERE { ?a a ex:Award ; ex:laureate ?p }";

		Result actual = runQuery(query);

		assertEquals(evaluate(query, null), actual);
		assertEquals(1, total(actual));
	}

	@Test
	void runQuery_fromNamed_isEvaluatedAsWithoutRewrite() throws Exception {
		// The default graph of this dataset is empty, so ?p stays unbound unless the pattern were rewritten.
		String query = "SELECT ?g ?a ?p FROM NAMED <" + BASE + "1/metadata/1> FROM NAMED <" + BASE + "1> "
				+ "WHERE { GRAPH ?g { ?a a ex:Award } OPTIONAL { ?a ex:laureate ?p } }";

		Result actual = runQuery(query);

		assertEquals(evaluate(query, null), actual);
		assertEquals(3, total(actual));
		assertTrue(actual.rows().keySet().stream().noneMatch(row -> row.containsKey("p")));
	}

	/** The zero-length step is evaluated without a triple pattern, so it is not rewritten. */
	@Test
	void runQuery_zeroOrMorePath_keepsTheZeroLengthStepAsWithoutRewrite() throws Exception {
		String zeroLengthOnly = "SELECT ?x WHERE { ?x ex:unused* ?y FILTER(?x = ?y) }";

		assertEquals(evaluate(zeroLengthOnly, null), runQuery(zeroLengthOnly));
	}

	@Test
	void runQuery_zeroOrOnePath_matchesWithoutRewrite() throws Exception {
		// RDF4J evaluates p? as DISTINCT, so neither variant repeats a solution.
		String query = "SELECT ?a ?b WHERE { ?a ex:next? ?b }";

		assertEquals(evaluate(query, null), runQuery(query));
	}

	private Result runQuery(String query) throws Exception {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		service.runQuery(SparqlResultFormat.SPARQL_RESULTS_JSON, PREFIXES + query, null, out);
		QueryResultCollector collector = new QueryResultCollector();
		QueryResultIO.parseTuple(new ByteArrayInputStream(out.toByteArray()), TupleQueryResultFormat.JSON, collector,
				SimpleValueFactory.getInstance());
		return Result.of(collector.getBindingNames(), collector.getBindingSets());
	}

	private static Result evaluate(String query, SimpleDataset dataset) {
		try (RepositoryConnection rc = repository.getConnection()) {
			TupleQuery tupleQuery = rc.prepareTupleQuery(QueryLanguage.SPARQL, PREFIXES + query);
			if (dataset != null) {
				tupleQuery.setDataset(dataset);
			}
			try (TupleQueryResult result = tupleQuery.evaluate()) {
				return Result.of(result.getBindingNames(), result.stream().toList());
			}
		}
	}

	/** The context graphs as default graph; with {@code allNamedGraphs}, every graph as a named graph. */
	private static SimpleDataset contextGraphsDataset(boolean allNamedGraphs) {
		SimpleDataset dataset = new SimpleDataset();
		ValueFactory vf = SimpleValueFactory.getInstance();
		CONTEXT_GRAPHS.forEach(graph -> dataset.addDefaultGraph(vf.createIRI(graph)));
		if (allNamedGraphs) {
			try (RepositoryConnection rc = repository.getConnection();
				 RepositoryResult<Resource> graphs = rc.getContextIDs()) {
				graphs.forEach(graph -> dataset.addNamedGraph((IRI) graph));
			}
		}
		return dataset;
	}

	private static long total(Result result) {
		return result.rows().values().stream().mapToLong(Long::longValue).sum();
	}

	/** Binding names and rows, each row with how often it occurs. */
	private record Result(List<String> bindingNames, Map<Map<String, Value>, Long> rows) {

		static Result of(List<String> bindingNames, List<BindingSet> bindingSets) {
			Map<Map<String, Value>, Long> rows = new HashMap<>();
			for (BindingSet bindingSet : bindingSets) {
				Map<String, Value> row = new HashMap<>();
				bindingSet.forEach(binding -> row.put(binding.getName(), binding.getValue()));
				rows.merge(row, 1L, Long::sum);
			}
			return new Result(List.copyOf(bindingNames), rows);
		}
	}
}
