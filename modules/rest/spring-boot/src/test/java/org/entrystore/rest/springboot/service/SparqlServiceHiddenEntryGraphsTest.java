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

import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.query.BindingSet;
import org.eclipse.rdf4j.query.QueryLanguage;
import org.eclipse.rdf4j.query.TupleQueryResult;
import org.eclipse.rdf4j.query.impl.SimpleDataset;
import org.eclipse.rdf4j.query.resultio.QueryResultIO;
import org.eclipse.rdf4j.query.resultio.TupleQueryResultFormat;
import org.eclipse.rdf4j.query.resultio.helpers.QueryResultCollector;
import org.eclipse.rdf4j.repository.Repository;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.entrystore.Context;
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
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.entrystore.rest.springboot.service.PublicRepositoryFixture.BASE;
import static org.entrystore.rest.springboot.service.PublicRepositoryFixture.CONTEXT_GRAPHS;
import static org.entrystore.rest.springboot.service.PublicRepositoryFixture.METADATA_GRAPH;
import static org.entrystore.rest.springboot.service.PublicRepositoryFixture.PREFIXES;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * With the entry graphs hidden, /sparql must answer every query as a repository that holds only the context graphs
 * would. The oracle is such a repository: a copy of the fixture without the entry graphs, queried unmodified.
 * Results are compared as multisets, together with their binding names.
 */
@ExtendWith(MockitoExtension.class)
class SparqlServiceHiddenEntryGraphsTest {

	private static final String RESOURCE_GRAPH = BASE + "1/resource/4";

	private static Repository repository;
	private static Repository contextGraphsOnly;

	@Mock
	private RepositoryManagerImpl repositoryManager;

	@Mock
	private ContextService contextService;

	@Mock
	private ReservedNamesService reservedNames;

	@Mock
	private PublicRepository publicRepository;

	private SparqlService service;

	@BeforeAll
	static void fillRepositories() throws IOException {
		repository = PublicRepositoryFixture.create(false);
		contextGraphsOnly = PublicRepositoryFixture.create(true);
	}

	@AfterAll
	static void shutDown() {
		repository.shutDown();
		contextGraphsOnly.shutDown();
	}

	@BeforeEach
	void setUp() throws Exception {
		lenient().when(repositoryManager.getPublicRepository()).thenReturn(publicRepository);
		lenient().when(publicRepository.getConnection()).thenAnswer(_ -> repository.getConnection());
		lenient().when(repositoryManager.getRepositoryURL()).thenReturn(URI.create(BASE).toURL());
		service = new SparqlService(repositoryManager, contextService, reservedNames, 10, 64L * 1024 * 1024, 4, false,
				1);
	}

	static Stream<Arguments> graphAndDatasetQueries() {
		return Stream.of(
				Arguments.of("GRAPH ?g projected", "SELECT ?g ?a WHERE { GRAPH ?g { ?a a ex:Award } }"),
				Arguments.of("GRAPH ?g not projected",
						"SELECT ?a ?p WHERE { GRAPH ?g { ?a a ex:Award ; ex:laureate ?p } }"),
				Arguments.of("GRAPH <context>", "SELECT ?a WHERE { GRAPH <" + BASE + "1> { ?a a ex:Award } }"),
				Arguments.of("GRAPH <metadata graph>",
						"SELECT ?s ?p ?o WHERE { GRAPH <" + METADATA_GRAPH + "> { ?s ?p ?o } }"),
				Arguments.of("GRAPH <resource graph>",
						"SELECT ?s ?p ?o WHERE { GRAPH <" + RESOURCE_GRAPH + "> { ?s ?p ?o } }"),
				Arguments.of("FROM <context>",
						"SELECT ?a ?p FROM <" + BASE + "1> WHERE { ?a a ex:Award ; ex:laureate ?p }"),
				Arguments.of("FROM <metadata graph> only",
						"SELECT ?s ?p ?o FROM <" + METADATA_GRAPH + "> WHERE { ?s ?p ?o }"),
				Arguments.of("FROM <metadata graph> only with GRAPH ?g",
						"SELECT ?g ?s FROM <" + METADATA_GRAPH + "> WHERE { GRAPH ?g { ?s ?p ?o } }"),
				Arguments.of("FROM NAMED <metadata graph> only", "SELECT ?g ?s ?n FROM NAMED <" + METADATA_GRAPH
						+ "> WHERE { GRAPH ?g { ?s ?p ?o } OPTIONAL { ?s ex:name ?n } }"),
				Arguments.of("FROM NAMED with metadata and context graphs", "SELECT ?g ?a FROM NAMED <"
						+ METADATA_GRAPH + "> FROM NAMED <" + BASE + "1> FROM NAMED <" + BASE + "2> "
						+ "WHERE { GRAPH ?g { ?a a ex:Award } }"),
				Arguments.of("FROM <context> and FROM NAMED <metadata graph>", "SELECT ?a ?g ?l FROM <" + BASE
						+ "1> FROM NAMED <" + METADATA_GRAPH + "> "
						+ "WHERE { ?a a ex:Award OPTIONAL { GRAPH ?g { ?a ex:label ?l } } }"),
				Arguments.of("FROM <metadata graph> and FROM NAMED <context>", "SELECT ?a ?g FROM <"
						+ METADATA_GRAPH + "> FROM NAMED <" + BASE + "1> "
						+ "WHERE { { ?a a ex:Award } UNION { GRAPH ?g { ?a a ex:Award } } }"),
				Arguments.of("GRAPH ?g with patterns outside GRAPH",
						"SELECT ?g ?a ?p WHERE { GRAPH ?g { ?a a ex:Award } ?a ex:laureate ?p }"),
				Arguments.of("VALUES ?g { metadata graph }",
						"SELECT ?a WHERE { VALUES ?g { <" + METADATA_GRAPH + "> } GRAPH ?g { ?a ?p ?o } }"),
				Arguments.of("VALUES ?g { metadata graph, context }", "SELECT ?g ?a WHERE { VALUES ?g { <"
						+ METADATA_GRAPH + "> <" + BASE + "1> } GRAPH ?g { ?a a ex:Award } }"),
				Arguments.of("BIND(metadata graph AS ?g)",
						"SELECT ?a WHERE { BIND(<" + METADATA_GRAPH + "> AS ?g) GRAPH ?g { ?a ?p ?o } }"),
				Arguments.of("FILTER on ?g", "SELECT ?g WHERE { GRAPH ?g { ?a a ex:Award } "
						+ "FILTER(CONTAINS(STR(?g), \"metadata\")) }"),
				Arguments.of("nested GRAPH",
						"SELECT ?g ?h ?a WHERE { GRAPH ?g { ?a a ex:Award GRAPH ?h { ?a ex:laureate ?p } } }"),
				Arguments.of("GRAPH in a subselect",
						"SELECT ?g ?a WHERE { { SELECT ?g ?a WHERE { GRAPH ?g { ?a a ex:Award } } } }"),
				Arguments.of("GRAPH <metadata graph> in EXISTS", "SELECT ?a WHERE { ?a a ex:Award "
						+ "FILTER EXISTS { GRAPH <" + METADATA_GRAPH + "> { ?a ?p ?o } } }"),
				Arguments.of("GRAPH ?g in NOT EXISTS", "SELECT ?a WHERE { ?a a ex:Award "
						+ "FILTER NOT EXISTS { GRAPH ?g { ?a ex:label ?l } } }"),
				Arguments.of("GRAPH ?g in OPTIONAL",
						"SELECT ?a ?g ?l WHERE { ?a a ex:Award OPTIONAL { GRAPH ?g { ?a ex:label ?l } } }"),
				Arguments.of("GRAPH <metadata graph> in MINUS", "SELECT ?a WHERE { ?a a ex:Award "
						+ "MINUS { GRAPH <" + METADATA_GRAPH + "> { ?a ?p ?o } } }"),
				Arguments.of("path p+ in GRAPH", "SELECT ?g ?a ?b WHERE { GRAPH ?g { ?a ex:next+ ?b } }"),
				Arguments.of("path p* in GRAPH <context>",
						"SELECT ?a ?b WHERE { GRAPH <" + BASE + "1> { ?a ex:next* ?b } }"),
				Arguments.of("path p? in GRAPH <metadata graph>",
						"SELECT ?a ?b WHERE { GRAPH <" + METADATA_GRAPH + "> { ?a ex:next? ?b } }"),
				Arguments.of("COUNT(DISTINCT *) with GRAPH ?g",
						"SELECT (COUNT(DISTINCT *) AS ?c) WHERE { GRAPH ?g { ?s ex:name ?n } }"),
				Arguments.of("COUNT(*) with GRAPH ?g", "SELECT (COUNT(*) AS ?c) WHERE { GRAPH ?g { ?s ?p ?o } }"),
				Arguments.of("graph names", "SELECT DISTINCT ?g WHERE { GRAPH ?g { ?s ?p ?o } }"),
				Arguments.of("triple term in GRAPH",
						"SELECT ?g ?s WHERE { GRAPH ?g { ?a ex:cites <<( ?s ?p ?o )>> } }"),
				Arguments.of("reified triple in GRAPH",
						"SELECT ?g ?s WHERE { GRAPH ?g { << ?s ex:year ?y >> ex:source ?src } }"),
				Arguments.of("empty GRAPH ?g", "SELECT ?g WHERE { GRAPH ?g { } }"),
				Arguments.of("inverse path in GRAPH", "SELECT ?g ?x ?y WHERE { GRAPH ?g { ?x ^ex:laureate ?y } }"),
				Arguments.of("negated property set in GRAPH",
						"SELECT ?g ?x ?y WHERE { GRAPH ?g { ?x !(ex:laureate|^ex:next) ?y } }"),
				Arguments.of("alternative path in GRAPH",
						"SELECT ?g ?x ?y WHERE { GRAPH ?g { ?x (ex:next|ex:laureate) ?y } }"),
				Arguments.of("?g bound by a subselect", "SELECT ?g ?a WHERE { { SELECT ?g WHERE { VALUES ?g { <"
						+ METADATA_GRAPH + "> <" + BASE + "1> } } } GRAPH ?g { ?a a ex:Award } }"),
				Arguments.of("?g bound in OPTIONAL", "SELECT ?g ?a ?p WHERE { OPTIONAL { BIND(<" + METADATA_GRAPH
						+ "> AS ?g) } GRAPH ?g { ?a ex:laureate ?p } }"),
				Arguments.of("FROM with a path", "SELECT ?a ?b FROM <" + BASE + "1> FROM <" + METADATA_GRAPH
						+ "> WHERE { ?a ex:next+ ?b }"),
				Arguments.of("FROM and FROM NAMED listing graphs twice", "SELECT ?g ?a FROM <" + BASE + "1> FROM <"
						+ BASE + "1> FROM <" + METADATA_GRAPH + "> FROM <" + METADATA_GRAPH + "> FROM NAMED <" + BASE
						+ "2> FROM NAMED <" + BASE + "2> "
						+ "WHERE { { ?a a ex:Award } UNION { GRAPH ?g { ?a a ex:Award } } }"));
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource({"graphAndDatasetQueries",
			"org.entrystore.rest.springboot.service.SparqlServiceContextGraphTest#queriesWithoutGraphClause"})
	void runQuery_entryGraphsHidden_answersAsARepositoryOfOnlyTheContextGraphs(String name, String query)
			throws Exception {
		Result expected = evaluate(contextGraphsOnly, query, null);

		Result actual = runQuery(query, null);

		assertEquals(expected, actual);
	}

	/**
	 * RDF4J binds an unbound graph variable of a zero-length step to the first graph that holds the node, which in the
	 * public repository is usually the entry graph. That binding is dropped, so the zero-length match is missing; an
	 * entry graph is never bound. Matches of one step or more are those of the context graphs.
	 */
	@ParameterizedTest(name = "{0}")
	@CsvSource(delimiter = '|', value = {
			"p* | SELECT ?g ?a ?b WHERE { GRAPH ?g { ?a ex:next* ?b } }",
			"p? | SELECT ?g ?a ?b WHERE { GRAPH ?g { ?a ex:next? ?b } }",
			"zero length only | SELECT ?g ?a ?b WHERE { GRAPH ?g { ?a ex:unused* ?b } }"})
	void runQuery_zeroLengthStepInGraphVariable_neverBindsAnEntryGraph(String name, String query) throws Exception {
		Result actual = runQuery(query, null);

		assertTrue(actual.rows().keySet().stream()
				.allMatch(row -> CONTEXT_GRAPHS.contains(row.get("g").stringValue())));
		assertEquals(withAtLeastOneStep(evaluate(contextGraphsOnly, query, null)), withAtLeastOneStep(actual));
	}

	private static Map<Map<String, Value>, Long> withAtLeastOneStep(Result result) {
		return result.rows().entrySet().stream()
				.filter(row -> !row.getKey().get("a").equals(row.getKey().get("b")))
				.collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
	}

	@Test
	void runQuery_fromOnlyEntryGraphs_matchesNothingInsteadOfAllGraphs() throws Exception {
		String query = "SELECT ?s ?p ?o FROM <" + METADATA_GRAPH + "> FROM NAMED <" + RESOURCE_GRAPH + "> "
				+ "WHERE { { ?s ?p ?o } UNION { GRAPH ?g { ?s ?p ?o } } }";

		assertEquals(Map.of(), runQuery(query, null).rows());
	}

	@Test
	void runQuery_graphVariable_bindsOnlyContextGraphs() throws Exception {
		Result actual = runQuery("SELECT DISTINCT ?g WHERE { GRAPH ?g { ?s ?p ?o } }", null);

		Set<String> graphs = actual.rows().keySet().stream()
				.map(row -> row.get("g").stringValue())
				.collect(Collectors.toSet());
		assertEquals(Set.copyOf(CONTEXT_GRAPHS), graphs);
	}

	@Test
	void runQuery_contextEndpoint_readsThatContextsGraphAsBefore() throws Exception {
		Context context = mock(Context.class);
		when(context.getURI()).thenReturn(URI.create(BASE + "1"));
		when(contextService.getContext("1")).thenReturn(context);
		String query = "SELECT ?g ?a WHERE { { ?a a ex:Award } UNION { GRAPH ?g { ?a a ex:Award } } }";
		SimpleDataset contextGraph = new SimpleDataset();
		contextGraph.addDefaultGraph(SimpleValueFactory.getInstance().createIRI(BASE + "1"));
		contextGraph.addNamedGraph(SimpleValueFactory.getInstance().createIRI(BASE + "1"));

		assertEquals(evaluate(repository, query, contextGraph), runQuery(query, "1"));
	}

	private Result runQuery(String query, String contextId) throws Exception {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		service.runQuery(SparqlResultFormat.SPARQL_RESULTS_JSON, PREFIXES + query, contextId, out);
		QueryResultCollector collector = new QueryResultCollector();
		QueryResultIO.parseTuple(new ByteArrayInputStream(out.toByteArray()), TupleQueryResultFormat.JSON, collector,
				SimpleValueFactory.getInstance());
		return Result.of(collector.getBindingNames(), collector.getBindingSets());
	}

	private static Result evaluate(Repository store, String query, SimpleDataset dataset) {
		try (RepositoryConnection rc = store.getConnection()) {
			var tupleQuery = rc.prepareTupleQuery(QueryLanguage.SPARQL, PREFIXES + query);
			if (dataset != null) {
				tupleQuery.setDataset(dataset);
			}
			try (TupleQueryResult result = tupleQuery.evaluate()) {
				return Result.of(result.getBindingNames(), result.stream().toList());
			}
		}
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
