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

import lombok.extern.slf4j.Slf4j;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.query.MalformedQueryException;
import org.eclipse.rdf4j.query.QueryEvaluationException;
import org.eclipse.rdf4j.query.QueryInterruptedException;
import org.eclipse.rdf4j.query.QueryLanguage;
import org.eclipse.rdf4j.query.TupleQuery;
import org.eclipse.rdf4j.query.TupleQueryResultHandler;
import org.eclipse.rdf4j.query.TupleQueryResultHandlerException;
import org.eclipse.rdf4j.query.algebra.TupleExpr;
import org.eclipse.rdf4j.query.algebra.helpers.AbstractQueryModelVisitor;
import org.eclipse.rdf4j.query.impl.SimpleDataset;
import org.eclipse.rdf4j.query.resultio.binary.BinaryQueryResultWriter;
import org.eclipse.rdf4j.query.resultio.sparqljson.SPARQLResultsJSONWriter;
import org.eclipse.rdf4j.query.resultio.sparqlxml.SPARQLResultsXMLWriter;
import org.eclipse.rdf4j.query.resultio.text.csv.SPARQLResultsCSVWriter;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.RepositoryException;
import org.eclipse.rdf4j.repository.sail.SailTupleQuery;
import org.entrystore.Context;
import org.entrystore.impl.PublicRepository;
import org.entrystore.impl.RepositoryManagerImpl;
import org.entrystore.rest.springboot.model.exception.BadRequestException;
import org.entrystore.rest.springboot.model.exception.CustomResponseException;
import org.entrystore.rest.springboot.model.exception.InternalServerErrorException;
import org.entrystore.rest.springboot.model.exception.NotImplementedException;
import org.entrystore.rest.springboot.util.SparqlResultFormat;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

@Slf4j
@Service
public class SparqlService {

	private static final int LOGGED_QUERY_PREFIX_LENGTH = 256;

	// Generic message for all internal failures on the anonymous SPARQL endpoint. Avoids leaking
	// architecture details (repository class, writer state, Sail-vs-non-Sail). Specific cause is
	// preserved in the cause chain for server-side debugging.
	private static final String INTERNAL_ERROR_MESSAGE = "Public SPARQL endpoint encountered an internal error";

	private final RepositoryManagerImpl repositoryManager;
	private final ContextService contextService;
	private final ReservedNamesService reservedNames;
	private final int maxExecutionTime;
	private final long maxResponseBytes;

	public SparqlService(
			RepositoryManagerImpl repositoryManager,
			ContextService contextService,
			ReservedNamesService reservedNames,
			@Value("${entrystore.repository.public.sparql.max-execution-time:10}") int maxExecutionTime,
			@Value("${entrystore.repository.public.sparql.max-response-bytes:67108864}") long maxResponseBytes) {
		// Reject misconfigured limits at construction so the app fails to start rather than silently
		// disabling timeouts/caps on this anonymous endpoint (RDF4J treats setMaxExecutionTime(0) as unlimited).
		if (maxExecutionTime <= 0) {
			throw new IllegalStateException(
					"entrystore.repository.public.sparql.max-execution-time must be > 0, was " + maxExecutionTime);
		}
		if (maxResponseBytes <= 0) {
			throw new IllegalStateException(
					"entrystore.repository.public.sparql.max-response-bytes must be > 0, was " + maxResponseBytes);
		}
		this.repositoryManager = repositoryManager;
		this.contextService = contextService;
		this.reservedNames = reservedNames;
		this.maxExecutionTime = maxExecutionTime;
		this.maxResponseBytes = maxResponseBytes;
		log.debug("Public SPARQL max execution time: {} seconds; max response bytes: {}",
				maxExecutionTime, maxResponseBytes);
	}

	/**
	 * Runs the given SPARQL tuple query against the public repository, optionally
	 * restricting the dataset to a context's named graph, and writes the serialised
	 * result for {@code format} directly to {@code out}.
	 *
	 * <p>The result is streamed through a {@code SizeLimitedOutputStream} that caps the
	 * total to {@code maxResponseBytes}. Pre-{@code evaluate} validation (public-repo
	 * enablement, query parse, SERVICE-clause rejection, fail-closed non-Sail check)
	 * happens before any byte is written, so those failures produce clean 4xx/5xx
	 * responses via {@code AppExceptionHandler}. A missing or reserved {@code contextId}
	 * is silently mapped to a synthesised named graph (see {@code resolveNamedGraphUri})
	 * to avoid context-existence disclosure (CWE-204), so unknown contexts produce the
	 * same 200/empty response as existing-but-private ones rather than 404.
	 *
	 * <p>Whether a fault during {@code evaluate} (size cap, evaluator error) reaches the
	 * client as a clean status code or as a mid-stream connection drop depends on whether
	 * the servlet response buffer (Jetty default ~32 KB) has flushed. Below that threshold
	 * — single-row aggregates, narrow projections, per-namespace bindings — the same
	 * fault still produces a clean 413/500 via {@code AppExceptionHandler}. Above it,
	 * the client sees a connection drop instead.
	 *
	 * @param format      the SPARQL tuple-result format
	 * @param queryString the SPARQL query
	 * @param contextId   when non-null, both the default graph and the named-graph set are replaced with the
	 *                    context's resource URI (or a synthesised URI for missing/reserved contexts), so the
	 *                    dataset is exactly that one graph in both positions; when null the query runs against
	 *                    the full public repository
	 * @param out         the stream that the serialised tuple result is written to; the caller owns its
	 *                    lifecycle (the wrapping {@code SizeLimitedOutputStream} does not close it)
	 * @throws NotImplementedException     when the public repository is disabled
	 * @throws InternalServerErrorException 500 with a generic message. For RDF4J evaluator/writer/repository
	 *                                      faults the underlying exception is preserved in the cause chain
	 *                                      (server-side only). For fail-closed paths (connection unavailable,
	 *                                      non-Sail repository) no cause is attached and the failure must be
	 *                                      diagnosed via the operator-facing log line.
	 * @throws BadRequestException         {@code "Malformed SPARQL query"} on parse failure;
	 *                                      {@code "SPARQL SERVICE clauses are not permitted on the public endpoint"}
	 *                                      when a federated SERVICE clause is present
	 * @throws CustomResponseException     504 if the query times out; 413 if the result exceeds
	 *                                      {@code maxResponseBytes}
	 */
	public void runQuery(SparqlResultFormat format, String queryString, String contextId, OutputStream out) {
		PublicRepository publicRepository = repositoryManager.getPublicRepository();
		if (publicRepository == null) {
			throw new NotImplementedException("Public SPARQL endpoint is not enabled");
		}

		// Don't disclose context existence on the anonymous endpoint. Both missing and
		// existing-but-private contexts must produce the same observable response (200 with
		// empty bindings); returning 404 for missing contexts would let a client enumerate
		// context IDs by observing the status difference (CWE-204). For a missing or reserved
		// contextId we synthesise the URI a context with this ID would have so the named-graph
		// filter still applies — to a graph that simply has no triples in the public repo.
		String namedGraphUri = resolveNamedGraphUri(contextId);

		try (RepositoryConnection rc = acquireConnection(publicRepository)) {
			TupleQuery query = rc.prepareTupleQuery(QueryLanguage.SPARQL, queryString);
			rejectFederatedServices(extractTupleExpr(query));
			query.setMaxExecutionTime(maxExecutionTime);
			query.setIncludeInferred(false);

			if (namedGraphUri != null) {
				IRI contextURI = rc.getValueFactory().createIRI(namedGraphUri);
				log.debug("Restricting query to named graph {}", contextURI);
				SimpleDataset ds = new SimpleDataset();
				ds.addDefaultGraph(contextURI);
				ds.addNamedGraph(contextURI);
				query.setDataset(ds);
			}

			TupleQueryResultHandler resultHandler = createWriter(format,
					new SizeLimitedOutputStream(out, maxResponseBytes));

			log.debug("Executing query (first {} chars): {}",
					LOGGED_QUERY_PREFIX_LENGTH, truncate(queryString, LOGGED_QUERY_PREFIX_LENGTH));
			long before = System.currentTimeMillis();
			query.evaluate(resultHandler);
			log.debug("SPARQL query execution took {} ms", System.currentTimeMillis() - before);
		} catch (MalformedQueryException e) {
			throw new BadRequestException("Malformed SPARQL query", e);
		} catch (QueryInterruptedException e) {
			throw new CustomResponseException("SPARQL query timed out", HttpStatus.GATEWAY_TIMEOUT, e);
		} catch (QueryEvaluationException | TupleQueryResultHandlerException | RepositoryException e) {
			// findSizeCap also runs on the RepositoryException path because RDF4J writers can wrap a
			// writer-side IOException into different exception types depending on which writer is in use,
			// so the 413 cap must not rely on JSON-writer-specific wrapping.
			SparqlResultTooLargeException sizeCap = findSizeCap(e);
			if (sizeCap != null) {
				CustomResponseException tooLarge = new CustomResponseException(
						sizeCap.getMessage(), HttpStatus.PAYLOAD_TOO_LARGE, sizeCap);
				tooLarge.addSuppressed(e);
				throw tooLarge;
			}
			// Single generic message for all three caught types. The specific cause class (Repository /
			// TupleQueryResultHandler / QueryEvaluation) is preserved in the cause chain for server-side
			// debugging; the client only needs to know it is a 500.
			throw new InternalServerErrorException(INTERNAL_ERROR_MESSAGE, e);
		}
	}

	private static TupleExpr extractTupleExpr(TupleQuery query) {
		// SailTupleQuery exposes the already-parsed algebra so the federated-service detector
		// runs against exactly what the executor will run. A non-Sail backend (e.g. an HTTP-
		// or SPARQL-endpoint repository) could produce a different algebra at prepare time,
		// weakening the SERVICE-clause prohibition — fail closed so a future config change
		// cannot silently undermine the SSRF defense.
		if (query instanceof SailTupleQuery sailQuery) {
			return sailQuery.getParsedQuery().getTupleExpr();
		}
		// Generic client message; log the actual class name so an operator can diagnose
		// without source-diving.
		log.error("Public repository TupleQuery is not Sail-backed (got {}); refusing to run query "
						+ "because the federated-service detector cannot inspect its algebra",
				query.getClass().getName());
		throw new InternalServerErrorException(INTERNAL_ERROR_MESSAGE);
	}

	private String resolveNamedGraphUri(String contextId) {
		if (contextId == null) {
			return null;
		}
		// Reserved names skip contextService.getContext to avoid the false-alert ERROR log it
		// emits for them (the routing-error message it logs does not apply — SparqlController
		// is a legitimate caller). The synthesised URI below produces the same uniform-200
		// empty-bindings response the context-existence non-disclosure contract requires.
		if (!reservedNames.isReservedName(contextId.toLowerCase(Locale.ROOT))) {
			Context context = contextService.getContext(contextId);
			if (context != null) {
				return context.getURI().toString();
			}
		}
		// Synthesise the URI a real context with this id would have ({baseUrl}/{contextId} per
		// the project's URI conventions) so a missing or reserved context produces the same
		// shape of named-graph filter as an existing one — just against a graph with no triples.
		String base = repositoryManager.getRepositoryURL().toString();
		if (!base.endsWith("/")) {
			base += "/";
		}
		return base + contextId;
	}

	private static void rejectFederatedServices(TupleExpr expr) {
		FederatedServiceDetector detector = new FederatedServiceDetector();
		expr.visit(detector);
		if (detector.found) {
			throw new BadRequestException("SPARQL SERVICE clauses are not permitted on the public endpoint");
		}
	}

	private static SparqlResultTooLargeException findSizeCap(Throwable root) {
		// DFS over getCause()/getSuppressed() with a seen-set guards against:
		//  - the size-cap exception being attached as the outer throwable itself,
		//  - it being wrapped multiple levels deep,
		//  - it appearing in a suppressed branch instead of a cause chain,
		//  - cyclic exception graphs (e.g. a Throwable transitively suppressed by itself, which
		//    initCause forbids but addSuppressed does not) that would otherwise loop indefinitely,
		//  - shared sub-graphs reachable via multiple paths that would otherwise be re-traversed.
		Set<Throwable> seen = new HashSet<>();
		Deque<Throwable> stack = new ArrayDeque<>();
		stack.push(root);
		while (!stack.isEmpty()) {
			Throwable t = stack.pop();
			if (t == null || !seen.add(t)) {
				continue;
			}
			if (t instanceof SparqlResultTooLargeException sizeCap) {
				return sizeCap;
			}
			if (t.getCause() != null) {
				stack.push(t.getCause());
			}
			for (Throwable s : t.getSuppressed()) {
				stack.push(s);
			}
		}
		return null;
	}

	private static String truncate(String value, int max) {
		// Strip control chars and Unicode line/paragraph separators so a query containing CR/LF
		// or U+2028/U+2029 cannot forge log lines (CWE-117). \p{Cntrl} only catches Cc; U+2028
		// (LINE SEPARATOR, Zl) and U+2029 (PARAGRAPH SEPARATOR, Zp) are line terminators in JS
		// log viewers and BufferedReader.readLine but slip through Cc-only regexes.
		String visible = value.replaceAll("[\\p{Cntrl}\\u2028\\u2029]", "?");
		return visible.length() <= max ? visible : visible.substring(0, max) + "…";
	}

	private static RepositoryConnection acquireConnection(PublicRepository publicRepository) {
		RepositoryConnection rc = publicRepository.getConnection();
		if (rc == null) {
			throw new InternalServerErrorException(INTERNAL_ERROR_MESSAGE);
		}
		return rc;
	}

	private static TupleQueryResultHandler createWriter(SparqlResultFormat format, OutputStream out) {
		return switch (format) {
			case SPARQL_RESULTS_JSON -> new SPARQLResultsJSONWriter(out);
			case SPARQL_RESULTS_XML -> new SPARQLResultsXMLWriter(out);
			case CSV -> new SPARQLResultsCSVWriter(out);
			case BINARY -> new BinaryQueryResultWriter(out);
		};
	}

	private static final class FederatedServiceDetector extends AbstractQueryModelVisitor<RuntimeException> {
		boolean found;

		@Override
		public void meet(org.eclipse.rdf4j.query.algebra.Service node) {
			found = true;
		}
	}

	private static final class SparqlResultTooLargeException extends IOException {
		SparqlResultTooLargeException(long maxBytes) {
			super("SPARQL result exceeds maximum allowed size of " + maxBytes + " bytes");
		}
	}

	private static final class SizeLimitedOutputStream extends OutputStream {

		private final OutputStream delegate;
		private final long maxBytes;
		private long written;

		SizeLimitedOutputStream(OutputStream delegate, long maxBytes) {
			// Defense-in-depth on the cross-class invariant: the outer SparqlService constructor
			// already rejects maxBytes <= 0, but a future caller that bypasses it would otherwise
			// turn every successful query into a 413 (or risk underflow on long-running streams).
			if (maxBytes <= 0) {
				throw new IllegalArgumentException("maxBytes must be positive, got " + maxBytes);
			}
			this.delegate = Objects.requireNonNull(delegate, "delegate");
			this.maxBytes = maxBytes;
		}

		@Override
		public void write(int b) throws IOException {
			ensureCapacity(1);
			delegate.write(b);
			written++;
		}

		@Override
		public void write(byte[] b, int off, int len) throws IOException {
			// Without the bounds check, a negative len would slip past ensureCapacity
			// (negative > maxBytes - written is always false) and reach the delegate
			// with the cap silently violated. Objects.checkFromIndexSize also gives us
			// NPE-on-null via b.length.
			Objects.checkFromIndexSize(off, len, b.length);
			ensureCapacity(len);
			delegate.write(b, off, len);
			written += len;
		}

		@Override
		public void flush() throws IOException {
			delegate.flush();
		}

		@Override
		public void close() throws IOException {
			// flush()-only: the wrapper does not own the delegate's lifecycle. Important
			// because the delegate is the response output stream — closing it here would
			// terminate the response prematurely if an RDF4J writer calls close() on the
			// handler.
			delegate.flush();
		}

		private void ensureCapacity(long inc) throws IOException {
			if (inc > maxBytes - written) {
				throw new SparqlResultTooLargeException(maxBytes);
			}
		}
	}
}
