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

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.query.QueryEvaluationException;
import org.eclipse.rdf4j.query.QueryInterruptedException;
import org.eclipse.rdf4j.query.QueryLanguage;
import org.eclipse.rdf4j.query.TupleQuery;
import org.eclipse.rdf4j.query.TupleQueryResultHandler;
import org.eclipse.rdf4j.query.TupleQueryResultHandlerException;
import org.eclipse.rdf4j.query.impl.SimpleDataset;
import org.eclipse.rdf4j.query.parser.QueryParserUtil;
import org.eclipse.rdf4j.repository.Repository;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.RepositoryException;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.eclipse.rdf4j.repository.sail.SailTupleQuery;
import org.eclipse.rdf4j.sail.memory.MemoryStore;
import org.entrystore.Context;
import org.entrystore.impl.PublicRepository;
import org.entrystore.impl.RepositoryManagerImpl;
import org.entrystore.rest.springboot.model.exception.BadRequestException;
import org.entrystore.rest.springboot.model.exception.CustomResponseException;
import org.entrystore.rest.springboot.model.exception.InternalServerErrorException;
import org.entrystore.rest.springboot.model.exception.NotImplementedException;
import org.entrystore.rest.springboot.util.SparqlResultFormat;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.MalformedURLException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SparqlServiceTest {

	private static final String SELECT_ALL = "SELECT * WHERE { ?s ?p ?o } LIMIT 1";
	private static final String CONTEXT_ID = "ctx-1";
	private static final String CONTEXT_URI = "http://example.org/ctx-1";
	/** The graph of context 1 under the mocked repository URL, which /sparql reads as default graph. */
	private static final String CONTEXT_GRAPH = "http://example.org/store/1";

	private static final int MAX_EXECUTION_TIME = 10;
	private static final long MAX_RESPONSE_BYTES = 64L * 1024L * 1024L;
	private static final int MAX_CONCURRENT_QUERIES = 4;
	private static final Duration REJECT_QUICKLY = Duration.ofMillis(50);
	private static final Duration WAIT_LONG = Duration.ofSeconds(10);
	private static final String GENERIC_INTERNAL_ERROR = "Public SPARQL endpoint encountered an internal error";

	@Mock
	private RepositoryManagerImpl repositoryManager;

	@Mock
	private ContextService contextService;

	@Mock
	private ReservedNamesService reservedNames;

	@Mock
	private PublicRepository publicRepository;

	private static Repository backingRepository;
	private SparqlService service;

	@BeforeAll
	static void initRepository() {
		backingRepository = new SailRepository(new MemoryStore());
		backingRepository.init();
	}

	@AfterAll
	static void shutdownRepository() {
		if (backingRepository != null) {
			backingRepository.shutDown();
		}
	}

	@BeforeEach
	void setUp() throws MalformedURLException {
		lenient().when(repositoryManager.getPublicRepository()).thenReturn(publicRepository);
		lenient().when(publicRepository.getConnection())
				.thenAnswer(_ -> backingRepository.getConnection());
		lenient().when(repositoryManager.getRepositoryURL())
				.thenReturn(java.net.URI.create("http://example.org/store/").toURL());
		lenient().when(reservedNames.isReservedName(anyString())).thenReturn(false);

		service = new SparqlService(repositoryManager, contextService, reservedNames,
				MAX_EXECUTION_TIME, MAX_RESPONSE_BYTES, MAX_CONCURRENT_QUERIES);
	}

	@AfterEach
	void clearRepository() {
		try (RepositoryConnection rc = backingRepository.getConnection()) {
			rc.clear();
		}
	}

	@Test
	void runQuery_publicRepositoryDisabled_throwsNotImplemented() {
		when(repositoryManager.getPublicRepository()).thenReturn(null);

		assertThrows(NotImplementedException.class,
				() -> service.runQuery(SparqlResultFormat.SPARQL_RESULTS_JSON, SELECT_ALL, null, new ByteArrayOutputStream()));
	}

	@Test
	void runQuery_unknownContext_returnsEmptyResultWithoutDisclosingExistence() throws Exception {
		// CWE-204: a 404 here would let an anonymous client enumerate context IDs by observing
		// the status difference between missing and existing-but-private contexts. The service
		// must run the query against a synthesised named graph that has no triples, producing
		// the same 200/empty response as an existing-but-private context would.
		when(contextService.getContext("unknown")).thenReturn(null);

		ByteArrayOutputStream out = new ByteArrayOutputStream();
		service.runQuery(SparqlResultFormat.SPARQL_RESULTS_JSON, SELECT_ALL, "unknown", out);

		// Parse the SPARQL-results JSON instead of substring-matching, since the writer
		// pretty-prints whitespace into the body.
		var json = new JsonMapper().readTree(out.toByteArray());
		assertTrue(json.path("results").path("bindings").isArray(),
				"Expected SPARQL-results 'results.bindings' array, got: " + json);
		assertEquals(0, json.path("results").path("bindings").size(),
				"Expected empty bindings for unknown context, got: " + json);
	}

	@Test
	void runQuery_malformedSparql_throwsBadRequestWithoutLeakingParserMessage() {
		BadRequestException ex = assertThrows(BadRequestException.class,
				() -> service.runQuery(SparqlResultFormat.SPARQL_RESULTS_JSON, "this is not sparql {{{", null, new ByteArrayOutputStream()));

		assertEquals("Malformed SPARQL query", ex.getMessage());
		assertNotNull(ex.getCause(), "Original parse error should be preserved as cause");
	}

	@Test
	void runQuery_validQueryJson_returnsValidJsonResultDocument() {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		service.runQuery(SparqlResultFormat.SPARQL_RESULTS_JSON, SELECT_ALL, null, out);

		byte[] body = out.toByteArray();
		assertTrue(body.length > 0, "Expected non-empty response body");
		String text = new String(body);
		assertTrue(text.contains("\"head\""), "Expected SPARQL JSON 'head' member, got: " + text);
		assertTrue(text.contains("\"results\""), "Expected SPARQL JSON 'results' member, got: " + text);
	}

	@Test
	void runQuery_validQueryXml_returnsValidXmlResultDocument() {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		service.runQuery(SparqlResultFormat.SPARQL_RESULTS_XML, SELECT_ALL, null, out);

		byte[] body = out.toByteArray();
		assertTrue(body.length > 0, "Expected non-empty response body");
		String text = new String(body);
		assertTrue(text.startsWith("<?xml"), "Expected XML declaration prefix, got: " + text);
		assertTrue(text.contains("<sparql"), "Expected '<sparql' root element, got: " + text);
	}

	@Test
	void runQuery_validQueryCsv_returnsCsvHeaderRow() {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		service.runQuery(SparqlResultFormat.CSV, SELECT_ALL, null, out);

		byte[] body = out.toByteArray();
		assertTrue(body.length > 0, "Expected non-empty response body");
		String text = new String(body);
		assertTrue(text.startsWith("s,p,o"), "Expected CSV header row 's,p,o', got: " + text);
	}

	@Test
	void runQuery_csvAlias_returnsCsvResultsWithQuotedFields() {
		String query = """
				SELECT ?iri ?label WHERE {
					VALUES (?iri ?label) { (<http://example.org/item> "Nobel, Prize") }
				}
				""";
		SparqlResultFormat format = SparqlResultFormat.resolve(null, "application/sparql-results+csv");
		ByteArrayOutputStream out = new ByteArrayOutputStream();

		service.runQuery(format, query, null, out);

		assertEquals("iri,label\r\nhttp://example.org/item,\"Nobel, Prize\"\r\n",
				out.toString(StandardCharsets.UTF_8));
	}

	@Test
	void runQuery_validQueryTsv_returnsRdfTermsAndEscapesLiteralDelimiters() {
		String query = """
				SELECT ?iri ?label ?missing WHERE {
					VALUES (?iri ?label ?missing) {
						(<http://example.org/item> "Nobel\\tPrize\\n2026"@en UNDEF)
					}
				}
				""";
		ByteArrayOutputStream out = new ByteArrayOutputStream();

		service.runQuery(SparqlResultFormat.TSV, query, null, out);

		assertEquals("?iri\t?label\t?missing\n<http://example.org/item>\t\"Nobel\\tPrize\\n2026\"@en\t\n",
				out.toString(StandardCharsets.UTF_8));
	}

	@Test
	void runQuery_emptyTsvResult_returnsHeaderOnly() {
		ByteArrayOutputStream out = new ByteArrayOutputStream();

		service.runQuery(SparqlResultFormat.TSV, "SELECT ?s ?p ?o WHERE { ?s ?p ?o }", null, out);

		assertEquals("?s\t?p\t?o\n", out.toString(StandardCharsets.UTF_8));
	}

	@Test
	void runQuery_validQueryBinary_startsWithBrtrMagicHeader() {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		service.runQuery(SparqlResultFormat.BINARY, SELECT_ALL, null, out);

		// RDF4J BinaryQueryResultWriter prefixes every result with the four ASCII bytes "BRTR"
		// (BinaryQueryResultConstants.MAGIC_NUMBER); asserting it proves the binary writer was the
		// one actually invoked, not just "anything that wasn't XML/JSON/CSV".
		byte[] body = out.toByteArray();
		assertTrue(body.length >= 4, "Expected at least 4 magic bytes, got " + body.length);
		assertEquals("BRTR", new String(body, 0, 4, StandardCharsets.US_ASCII));
	}

	@Test
	void runQuery_appliesConfiguredMaxExecutionTimeAndDisablesInferred() {
		int customExecutionTime = 42;
		SparqlService customService = new SparqlService(repositoryManager, contextService, reservedNames,
				customExecutionTime, MAX_RESPONSE_BYTES, MAX_CONCURRENT_QUERIES);

		RepositoryConnection mockConn = mock(RepositoryConnection.class);
		SailTupleQuery mockQuery = mock(SailTupleQuery.class);
		when(publicRepository.getConnection()).thenReturn(mockConn);
		when(mockConn.prepareTupleQuery(eq(QueryLanguage.SPARQL), anyString())).thenReturn(mockQuery);
		when(mockQuery.getParsedQuery()).thenReturn(QueryParserUtil.parseTupleQuery(QueryLanguage.SPARQL, SELECT_ALL, null));

		customService.runQuery(SparqlResultFormat.SPARQL_RESULTS_JSON, SELECT_ALL, null, new ByteArrayOutputStream());

		// InOrder pin: setMaxExecutionTime and setIncludeInferred(false) must both run BEFORE
		// evaluate. A regression placing setIncludeInferred after evaluate would silently expose
		// inferred triples on the anonymous endpoint; placing setMaxExecutionTime after would
		// no-op the timeout. Including both in the InOrder chain catches either reorder.
		InOrder order = inOrder(mockQuery);
		order.verify(mockQuery).setMaxExecutionTime(customExecutionTime);
		order.verify(mockQuery).setIncludeInferred(false);
		order.verify(mockQuery).evaluate(any(TupleQueryResultHandler.class));
		verify(mockQuery, never()).setDataset(any());
	}

	@Test
	void runQuery_publicRepositoryConnectionNull_throwsInternalServerError() {
		when(publicRepository.getConnection()).thenReturn(null);

		InternalServerErrorException ex = assertThrows(InternalServerErrorException.class,
				() -> service.runQuery(SparqlResultFormat.SPARQL_RESULTS_JSON, SELECT_ALL, null, new ByteArrayOutputStream()));
		assertEquals(GENERIC_INTERNAL_ERROR, ex.getMessage());
	}

	@Test
	void runQuery_repositoryException_throwsInternalServerErrorWithoutLeakingMessage() {
		// PublicRepository.getConnection() catches RepositoryException internally and returns null,
		// so the only realistically-reachable path through the RepositoryException catch is when
		// prepareTupleQuery / evaluate / etc. fail mid-query against a live connection.
		RepositoryConnection mockConn = mock(RepositoryConnection.class);
		when(publicRepository.getConnection()).thenReturn(mockConn);
		when(mockConn.prepareTupleQuery(eq(QueryLanguage.SPARQL), anyString()))
				.thenThrow(new RepositoryException("backing store offline; pool exhausted; secret-bucket"));

		InternalServerErrorException ex = assertThrows(InternalServerErrorException.class,
				() -> service.runQuery(SparqlResultFormat.SPARQL_RESULTS_JSON, SELECT_ALL, null, new ByteArrayOutputStream()));
		assertEquals(GENERIC_INTERNAL_ERROR, ex.getMessage());
		assertNotNull(ex.getCause(), "Original RepositoryException should be preserved as cause");
		// Pin the no-leak claim from the test name: the cause-bearing details (pool state, secret-bucket)
		// must not surface in the user-facing message that AppExceptionHandler will render.
		assertFalse(ex.getMessage().contains("secret-bucket"));
		assertFalse(ex.getMessage().contains("backing store"));
	}

	@Test
	void runQuery_serviceClause_throwsBadRequest() {
		String serviceQuery = "SELECT ?s WHERE { SERVICE <http://example.org/sparql> { ?s ?p ?o } }";

		BadRequestException ex = assertThrows(BadRequestException.class,
				() -> service.runQuery(SparqlResultFormat.SPARQL_RESULTS_JSON, serviceQuery, null, new ByteArrayOutputStream()));
		assertTrue(ex.getMessage().contains("SERVICE"),
				"Expected SERVICE-rejection message, got: " + ex.getMessage());
	}

	@Test
	void runQuery_resultExceedsMaxResponseBytes_throws413() {
		SparqlService tinyCapService = new SparqlService(repositoryManager, contextService, reservedNames,
				MAX_EXECUTION_TIME, 8L, MAX_CONCURRENT_QUERIES);

		try (RepositoryConnection rc = backingRepository.getConnection()) {
			ValueFactory vf = rc.getValueFactory();
			rc.add(vf.createIRI("http://example.org/s"),
					vf.createIRI("http://example.org/p"),
					vf.createIRI("http://example.org/o"),
					vf.createIRI(CONTEXT_GRAPH));
		}

		CustomResponseException ex = assertThrows(CustomResponseException.class,
				() -> tinyCapService.runQuery(SparqlResultFormat.SPARQL_RESULTS_JSON, SELECT_ALL, null, new ByteArrayOutputStream()));

		assertEquals(HttpStatus.PAYLOAD_TOO_LARGE, ex.getStatus());
	}

	@Test
	void runQuery_queryEvaluationException_withoutSizeCap_throwsInternalServerError() {
		RepositoryConnection mockConn = mock(RepositoryConnection.class);
		SailTupleQuery mockQuery = mock(SailTupleQuery.class);
		when(publicRepository.getConnection()).thenReturn(mockConn);
		when(mockConn.prepareTupleQuery(eq(QueryLanguage.SPARQL), anyString())).thenReturn(mockQuery);
		when(mockQuery.getParsedQuery()).thenReturn(QueryParserUtil.parseTupleQuery(QueryLanguage.SPARQL, SELECT_ALL, null));
		doThrow(new QueryEvaluationException("backend went away"))
				.when(mockQuery).evaluate(any(TupleQueryResultHandler.class));

		InternalServerErrorException ex = assertThrows(InternalServerErrorException.class,
				() -> service.runQuery(SparqlResultFormat.SPARQL_RESULTS_JSON, SELECT_ALL, null, new ByteArrayOutputStream()));
		assertEquals(GENERIC_INTERNAL_ERROR, ex.getMessage());
	}

	@Test
	void runQuery_queryInterruptedException_throwsGatewayTimeout() {
		RepositoryConnection mockConn = mock(RepositoryConnection.class);
		SailTupleQuery mockQuery = mock(SailTupleQuery.class);
		when(publicRepository.getConnection()).thenReturn(mockConn);
		when(mockConn.prepareTupleQuery(eq(QueryLanguage.SPARQL), anyString())).thenReturn(mockQuery);
		when(mockQuery.getParsedQuery()).thenReturn(QueryParserUtil.parseTupleQuery(QueryLanguage.SPARQL, SELECT_ALL, null));
		doThrow(new QueryInterruptedException("simulated timeout"))
				.when(mockQuery).evaluate(any(TupleQueryResultHandler.class));

		CustomResponseException ex = assertThrows(CustomResponseException.class,
				() -> service.runQuery(SparqlResultFormat.SPARQL_RESULTS_JSON, SELECT_ALL, null, new ByteArrayOutputStream()));
		assertEquals(HttpStatus.GATEWAY_TIMEOUT, ex.getStatus());
		assertEquals("SPARQL query timed out", ex.getMessage());
	}

	@Test
	void runQuery_tupleQueryResultHandlerException_throwsSerializationFailed() {
		RepositoryConnection mockConn = mock(RepositoryConnection.class);
		SailTupleQuery mockQuery = mock(SailTupleQuery.class);
		when(publicRepository.getConnection()).thenReturn(mockConn);
		when(mockConn.prepareTupleQuery(eq(QueryLanguage.SPARQL), anyString())).thenReturn(mockQuery);
		when(mockQuery.getParsedQuery()).thenReturn(QueryParserUtil.parseTupleQuery(QueryLanguage.SPARQL, SELECT_ALL, null));
		doThrow(new TupleQueryResultHandlerException("writer broke"))
				.when(mockQuery).evaluate(any(TupleQueryResultHandler.class));

		InternalServerErrorException ex = assertThrows(InternalServerErrorException.class,
				() -> service.runQuery(SparqlResultFormat.SPARQL_RESULTS_JSON, SELECT_ALL, null, new ByteArrayOutputStream()));
		assertEquals(GENERIC_INTERNAL_ERROR, ex.getMessage());
	}

	@Test
	void runQuery_unrelatedIoExceptionWithSameMessage_doesNotMisclassifyAs413() {
		// findSizeCap matches by exception type (SparqlResultTooLargeException) — not by message text —
		// so an unrelated IOException with a coincidentally similar message must NOT trip the 413 path.
		RepositoryConnection mockConn = mock(RepositoryConnection.class);
		SailTupleQuery mockQuery = mock(SailTupleQuery.class);
		when(publicRepository.getConnection()).thenReturn(mockConn);
		when(mockConn.prepareTupleQuery(eq(QueryLanguage.SPARQL), anyString())).thenReturn(mockQuery);
		when(mockQuery.getParsedQuery()).thenReturn(QueryParserUtil.parseTupleQuery(QueryLanguage.SPARQL, SELECT_ALL, null));
		QueryEvaluationException outer = new QueryEvaluationException("writer aborted");
		outer.addSuppressed(new IOException("SPARQL result exceeds maximum allowed size of 8 bytes"));
		doThrow(outer).when(mockQuery).evaluate(any(TupleQueryResultHandler.class));

		InternalServerErrorException ex = assertThrows(InternalServerErrorException.class,
				() -> service.runQuery(SparqlResultFormat.SPARQL_RESULTS_JSON, SELECT_ALL, null, new ByteArrayOutputStream()));
		assertEquals(GENERIC_INTERNAL_ERROR, ex.getMessage());
	}

	@Test
	void constructor_zeroExecutionTime_throws() {
		assertThrows(IllegalStateException.class,
				() -> new SparqlService(repositoryManager, contextService, reservedNames,
						0, MAX_RESPONSE_BYTES, MAX_CONCURRENT_QUERIES));
	}

	@Test
	void constructor_negativeExecutionTime_throws() {
		assertThrows(IllegalStateException.class,
				() -> new SparqlService(repositoryManager, contextService, reservedNames,
						-1, MAX_RESPONSE_BYTES, MAX_CONCURRENT_QUERIES));
	}

	@Test
	void constructor_zeroResponseBytes_throws() {
		assertThrows(IllegalStateException.class,
				() -> new SparqlService(repositoryManager, contextService, reservedNames,
						MAX_EXECUTION_TIME, 0L, MAX_CONCURRENT_QUERIES));
	}

	@Test
	void constructor_negativeResponseBytes_throws() {
		assertThrows(IllegalStateException.class,
				() -> new SparqlService(repositoryManager, contextService, reservedNames,
						MAX_EXECUTION_TIME, -1L, MAX_CONCURRENT_QUERIES));
	}

	@Test
	void runQuery_nonSailTupleQuery_throwsInternalServerError() {
		// Pins the fail-closed branch in extractTupleExpr: a non-Sail TupleQuery (e.g. one returned
		// by an HTTP- or SPARQL-endpoint repository) cannot expose its parsed algebra in a way the
		// FederatedServiceDetector can inspect, so the SSRF defense must refuse to run rather than
		// silently allow a SERVICE clause through.
		RepositoryConnection mockConn = mock(RepositoryConnection.class);
		TupleQuery nonSailQuery = mock(TupleQuery.class);
		when(publicRepository.getConnection()).thenReturn(mockConn);
		when(mockConn.prepareTupleQuery(eq(QueryLanguage.SPARQL), anyString())).thenReturn(nonSailQuery);

		InternalServerErrorException ex = assertThrows(InternalServerErrorException.class,
				() -> service.runQuery(SparqlResultFormat.SPARQL_RESULTS_JSON, SELECT_ALL, null, new ByteArrayOutputStream()));
		assertEquals(GENERIC_INTERNAL_ERROR, ex.getMessage());
		// Distinguishes the Sail-only fail-closed branch from the RDF4J-catch branches: the
		// extractTupleExpr throw site does not wrap a cause, while the catch-block paths do.
		assertNull(ex.getCause(), "Non-Sail fail-closed branch should not wrap a cause");
	}

	@Test
	void runQuery_withContext_isolatesNamedGraph() {
		Context context = mock(Context.class);
		when(context.getURI()).thenReturn(URI.create(CONTEXT_URI));
		when(contextService.getContext(CONTEXT_ID)).thenReturn(context);

		try (RepositoryConnection rc = backingRepository.getConnection()) {
			ValueFactory vf = rc.getValueFactory();
			IRI inContextGraph = vf.createIRI(CONTEXT_URI);
			IRI otherGraph = vf.createIRI("http://example.org/other-graph");
			rc.add(vf.createIRI("http://example.org/in-context"),
					vf.createIRI("http://example.org/p"),
					vf.createIRI("http://example.org/o"),
					inContextGraph);
			rc.add(vf.createIRI("http://example.org/leaked"),
					vf.createIRI("http://example.org/p"),
					vf.createIRI("http://example.org/o"),
					otherGraph);
		}

		ByteArrayOutputStream out = new ByteArrayOutputStream();
		service.runQuery(SparqlResultFormat.SPARQL_RESULTS_JSON, SELECT_ALL, CONTEXT_ID, out);
		String text = out.toString();
		assertTrue(text.contains("http://example.org/in-context"),
				"Expected the in-context triple to be returned, got: " + text);
		assertFalse(text.contains("http://example.org/leaked"),
				"Expected no leak from other-graph, got: " + text);
	}

	@Test
	void runQuery_reservedContextId_skipsContextServiceLookup() throws Exception {
		// Reserved names ("sparql", "search", etc.) must not be passed to contextService.getContext —
		// that path emits a false-alert ERROR log claiming a routing bug, which the new uniform-200
		// contract would trigger on every reserved-name request. The synthesised named-graph URI is
		// still produced (via the no-context fall-through), so the response shape stays uniform.
		when(reservedNames.isReservedName("sparql")).thenReturn(true);

		ByteArrayOutputStream out = new ByteArrayOutputStream();
		service.runQuery(SparqlResultFormat.SPARQL_RESULTS_JSON, SELECT_ALL, "sparql", out);

		verify(contextService, never()).getContext("sparql");
		var json = new JsonMapper().readTree(out.toByteArray());
		assertEquals(0, json.path("results").path("bindings").size(),
				"Expected empty bindings for reserved-name context, got: " + json);
	}

	@Test
	void runQuery_reservedContextIdMixedCase_normalisesAndSkipsLookup() throws Exception {
		// Pins the Locale.ROOT lowercase normalisation: a request with mixed-case "SPARQL"
		// must hit the reserved-name skip branch the same way "sparql" does. A regression
		// dropping or weakening the .toLowerCase(Locale.ROOT) call would re-emit the false-
		// alert ERROR log for every mixed-case reserved-name request.
		when(reservedNames.isReservedName("sparql")).thenReturn(true);

		ByteArrayOutputStream out = new ByteArrayOutputStream();
		service.runQuery(SparqlResultFormat.SPARQL_RESULTS_JSON, SELECT_ALL, "SPARQL", out);

		verify(contextService, never()).getContext("SPARQL");
		verify(contextService, never()).getContext("sparql");
		var json = new JsonMapper().readTree(out.toByteArray());
		assertEquals(0, json.path("results").path("bindings").size(),
				"Expected empty bindings for mixed-case reserved-name context, got: " + json);
	}

	@Test
	void runQuery_withContext_setsBothDefaultAndNamedGraph() {
		// runQuery_withContext_isolatesNamedGraph above pins behavioural isolation against `?s ?p ?o`
		// queries; this test additionally pins the contract that the SimpleDataset carries the context
		// URI in BOTH the default-graph slot AND the named-graph set, so `GRAPH ?g {...}` queries see
		// the same single-graph dataset. A regression that drops addDefaultGraph would still pass the
		// behavioural test but break GRAPH-scoped queries.
		Context context = mock(Context.class);
		when(context.getURI()).thenReturn(URI.create(CONTEXT_URI));
		when(contextService.getContext(CONTEXT_ID)).thenReturn(context);

		RepositoryConnection mockConn = mock(RepositoryConnection.class);
		SailTupleQuery mockQuery = mock(SailTupleQuery.class);
		when(publicRepository.getConnection()).thenReturn(mockConn);
		when(mockConn.prepareTupleQuery(eq(QueryLanguage.SPARQL), anyString())).thenReturn(mockQuery);
		when(mockQuery.getParsedQuery()).thenReturn(QueryParserUtil.parseTupleQuery(QueryLanguage.SPARQL, SELECT_ALL, null));
		ValueFactory vf = backingRepository.getValueFactory();
		when(mockConn.getValueFactory()).thenReturn(vf);

		ArgumentCaptor<SimpleDataset> datasetCaptor = ArgumentCaptor.forClass(SimpleDataset.class);
		service.runQuery(SparqlResultFormat.SPARQL_RESULTS_JSON, SELECT_ALL, CONTEXT_ID, new ByteArrayOutputStream());

		verify(mockQuery).setDataset(datasetCaptor.capture());
		SimpleDataset dataset = datasetCaptor.getValue();
		IRI expected = vf.createIRI(CONTEXT_URI);
		assertEquals(java.util.Set.of(expected), dataset.getDefaultGraphs(),
				"Default-graph slot must carry the context URI");
		assertEquals(java.util.Set.of(expected), dataset.getNamedGraphs(),
				"Named-graph set must carry the context URI");
	}

	@Test
	void constructor_zeroConcurrentQueries_throws() {
		assertThrows(IllegalStateException.class,
				() -> new SparqlService(repositoryManager, contextService, reservedNames,
						MAX_EXECUTION_TIME, MAX_RESPONSE_BYTES, 0));
	}

	@Test
	void constructor_negativeConcurrentQueries_throws() {
		assertThrows(IllegalStateException.class,
				() -> new SparqlService(repositoryManager, contextService, reservedNames,
						MAX_EXECUTION_TIME, MAX_RESPONSE_BYTES, -1));
	}

	@Test
	void runQuery_allPermitsHeldByStreamingQueries_rejectsWith503AndRetryAfter() throws Exception {
		SparqlService limited = serviceWithConcurrencyLimit(2, REJECT_QUICKLY);
		BlockingOutputStream blocked = new BlockingOutputStream(2);

		try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
			try {
				executor.submit(() -> selectAll(limited, blocked));
				executor.submit(() -> selectAll(limited, blocked));
				blocked.awaitWriters();

				CustomResponseException ex = assertThrows(CustomResponseException.class,
						() -> selectAll(limited, new ByteArrayOutputStream()));

				assertEquals(HttpStatus.SERVICE_UNAVAILABLE, ex.getStatus());
				assertEquals("5", ex.getHeaders().getFirst("Retry-After"));
				assertEquals("Too many concurrent SPARQL queries; retry later", ex.getMessage());
			} finally {
				blocked.release();
			}
		}
	}

	@Test
	void runQuery_permitFreedWhileWaiting_runsTheWaitingQuery() throws Exception {
		SparqlService limited = serviceWithConcurrencyLimit(1, WAIT_LONG);
		BlockingOutputStream holder = new BlockingOutputStream(1);
		ByteArrayOutputStream waiterOut = new ByteArrayOutputStream();
		AtomicReference<Throwable> waiterFailure = new AtomicReference<>();

		try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
			Future<?> held;
			Thread waiter;
			try {
				held = executor.submit(() -> selectAll(limited, holder));
				holder.awaitWriters();
				waiter = startWaiter(() -> selectAll(limited, waiterOut), waiterFailure);
				awaitBlockedOnPermit(waiter);
			} finally {
				holder.release();
			}
			held.get(10, TimeUnit.SECONDS);
			waiter.join(10_000);
		}

		assertNull(waiterFailure.get(), "The waiting query should run once the permit is free");
		assertFixtureRow(waiterOut);
	}

	@Test
	void runQuery_failedAndRejectedQueries_releaseTheirPermitExactlyOnce() throws Exception {
		SparqlService limited = serviceWithConcurrencyLimit(1, REJECT_QUICKLY);
		assertThrows(BadRequestException.class, () -> limited.runQuery(SparqlResultFormat.SPARQL_RESULTS_JSON,
				"not sparql {{{", null, new ByteArrayOutputStream()));
		holdThePermitAndAssertRejection(limited);

		// One permit too many (released by the rejection) would let this query run beside the held one.
		holdThePermitAndAssertRejection(limited);

		// A permit lost by the failed or rejected query would make this one wait and be rejected.
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		selectAll(limited, out);
		assertFixtureRow(out);
	}

	@Test
	void runQuery_interruptedWhileWaitingForPermit_rejectsWith503AndKeepsInterruptFlag() throws Exception {
		SparqlService limited = serviceWithConcurrencyLimit(1, WAIT_LONG);
		BlockingOutputStream holder = new BlockingOutputStream(1);
		AtomicReference<Throwable> waiterFailure = new AtomicReference<>();
		AtomicBoolean interruptKept = new AtomicBoolean();

		try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
			Future<?> held;
			try {
				held = executor.submit(() -> selectAll(limited, holder));
				holder.awaitWriters();
				Thread waiter = startWaiter(() -> {
					try {
						selectAll(limited, new ByteArrayOutputStream());
					} finally {
						interruptKept.set(Thread.currentThread().isInterrupted());
					}
				}, waiterFailure);
				awaitBlockedOnPermit(waiter);
				waiter.interrupt();
				waiter.join(10_000);
			} finally {
				holder.release();
			}
			held.get(10, TimeUnit.SECONDS);
		}

		CustomResponseException ex = assertInstanceOf(CustomResponseException.class, waiterFailure.get());
		assertEquals(HttpStatus.SERVICE_UNAVAILABLE, ex.getStatus());
		assertTrue(interruptKept.get(), "The interrupt must be preserved for the caller");
		// A permit lost by the interrupted waiter would make this query wait 10 s and be rejected.
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		selectAll(limited, out);
		assertFixtureRow(out);
	}

	private static void assertFixtureRow(ByteArrayOutputStream out) {
		var bindings = new JsonMapper().readTree(out.toByteArray()).path("results").path("bindings");
		assertEquals(1, bindings.size(), "Expected the fixture triple once, got: " + bindings);
		assertEquals("http://example.org/s", bindings.get(0).path("s").path("value").asString());
	}

	private static void selectAll(SparqlService service, OutputStream out) {
		service.runQuery(SparqlResultFormat.SPARQL_RESULTS_JSON, SELECT_ALL, null, out);
	}

	private static Thread startWaiter(Runnable query, AtomicReference<Throwable> failure) {
		return Thread.ofPlatform().start(() -> {
			try {
				query.run();
			} catch (Throwable t) {
				failure.set(t);
			}
		});
	}

	/** Before its permit, runQuery only blocks in the timed semaphore wait. */
	private static void awaitBlockedOnPermit(Thread waiter) throws InterruptedException {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
		while (waiter.getState() != Thread.State.TIMED_WAITING) {
			assertTrue(System.nanoTime() < deadline, "The query never waited for a permit");
			Thread.sleep(1);
		}
	}

	private static void holdThePermitAndAssertRejection(SparqlService limited) throws Exception {
		BlockingOutputStream holder = new BlockingOutputStream(1);
		try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
			Future<?> held;
			try {
				held = executor.submit(() -> selectAll(limited, holder));
				holder.awaitWriters();
				CustomResponseException rejected = assertThrows(CustomResponseException.class,
						() -> selectAll(limited, new ByteArrayOutputStream()));
				assertEquals(HttpStatus.SERVICE_UNAVAILABLE, rejected.getStatus());
			} finally {
				holder.release();
			}
			held.get(10, TimeUnit.SECONDS);
		}
	}

	private SparqlService serviceWithConcurrencyLimit(int maxConcurrentQueries, Duration permitWait) {
		try (RepositoryConnection rc = backingRepository.getConnection()) {
			ValueFactory vf = rc.getValueFactory();
			rc.add(vf.createIRI("http://example.org/s"), vf.createIRI("http://example.org/p"), vf.createLiteral("o"),
					vf.createIRI(CONTEXT_GRAPH));
		}
		return new SparqlService(repositoryManager, contextService, reservedNames, MAX_EXECUTION_TIME,
				MAX_RESPONSE_BYTES, maxConcurrentQueries, permitWait);
	}

	/**
	 * Holds every query that writes to it in its first write, which runs inside {@code evaluate}, until released.
	 */
	private static final class BlockingOutputStream extends OutputStream {

		private final CountDownLatch writersExpected;
		private final CountDownLatch released = new CountDownLatch(1);
		private final Set<Thread> writers = ConcurrentHashMap.newKeySet();

		BlockingOutputStream(int expectedWriters) {
			this.writersExpected = new CountDownLatch(expectedWriters);
		}

		@Override
		public void write(int b) throws IOException {
			hold();
		}

		@Override
		public void write(byte[] b, int off, int len) throws IOException {
			hold();
		}

		private void hold() throws IOException {
			if (writers.add(Thread.currentThread())) {
				writersExpected.countDown();
			}
			try {
				if (!released.await(30, TimeUnit.SECONDS)) {
					throw new IOException("Test stream was never released");
				}
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new IOException(e);
			}
		}

		void awaitWriters() throws InterruptedException {
			assertTrue(writersExpected.await(10, TimeUnit.SECONDS), "Queries did not start writing in time");
		}

		void release() {
			released.countDown();
		}
	}
}
