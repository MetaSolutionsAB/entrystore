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
import org.entrystore.rest.springboot.model.exception.EntityNotFoundException;
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
import java.util.Objects;
import java.util.Set;

@Slf4j
@Service
public class SparqlService {

	private static final int LOGGED_QUERY_PREFIX_LENGTH = 256;

	private final RepositoryManagerImpl repositoryManager;
	private final ContextService contextService;
	private final int maxExecutionTime;
	private final long maxResponseBytes;

	public SparqlService(
			RepositoryManagerImpl repositoryManager,
			ContextService contextService,
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
	 * <p>The result is streamed: bytes are produced incrementally and written through a
	 * {@code SizeLimitedOutputStream} that caps the total to {@code maxResponseBytes}.
	 * All validation (public-repo enablement, context lookup, query parse, SERVICE-clause
	 * rejection) happens before any byte is written, so those failures still produce
	 * clean 4xx/5xx responses through {@code AppExceptionHandler}. Failures during
	 * {@code evaluate} (size cap exceeded, evaluator faults) propagate as exceptions; if
	 * the response buffer has not yet flushed, the handler still produces a clean status
	 * code, otherwise the connection drops mid-stream.
	 *
	 * @param format      the SPARQL tuple-result format
	 * @param queryString the SPARQL query
	 * @param contextId   when non-null, both the default graph and the named-graph set are replaced with the
	 *                    context's resource URI, so the dataset is exactly that one graph in both positions;
	 *                    when null the query runs against the full public repository
	 * @param out         the stream that the serialised tuple result is written to; the caller owns its
	 *                    lifecycle (the wrapping {@code SizeLimitedOutputStream} does not close it)
	 * @throws NotImplementedException     {@code "Public SPARQL endpoint is not enabled"} when the public
	 *                                      repository is disabled
	 * @throws InternalServerErrorException {@code "Public SPARQL endpoint connection unavailable"} when a
	 *                                      connection cannot be acquired (null return);
	 *                                      {@code "Public SPARQL endpoint requires a Sail-backed repository..."}
	 *                                      when the repository is not Sail-backed (fail-closed SSRF defense);
	 *                                      {@code "SPARQL repository error"} on backing-store failure;
	 *                                      {@code "SPARQL query evaluation failed"} on RDF4J evaluation failure;
	 *                                      {@code "SPARQL result serialization failed"} on writer failure
	 * @throws BadRequestException         {@code "Malformed SPARQL query"} on parse failure;
	 *                                      {@code "SPARQL SERVICE clauses are not permitted on the public endpoint"}
	 *                                      when a federated SERVICE clause is present
	 * @throws EntityNotFoundException     {@code "Context with id '<id>' does not exist"} when {@code contextId}
	 *                                      is non-null and no such context exists
	 * @throws CustomResponseException     504 if the query times out; 413 if the result exceeds
	 *                                      {@code maxResponseBytes}
	 */
	public void runQuery(SparqlResultFormat format, String queryString, String contextId, OutputStream out) {
		PublicRepository publicRepository = repositoryManager.getPublicRepository();
		if (publicRepository == null) {
			throw new NotImplementedException("Public SPARQL endpoint is not enabled");
		}

		Context context = (contextId != null) ? contextService.getContextOrThrow(contextId) : null;

		try (RepositoryConnection rc = acquireConnection(publicRepository)) {
			TupleQuery query = rc.prepareTupleQuery(QueryLanguage.SPARQL, queryString);
			rejectFederatedServices(extractTupleExpr(query));
			query.setMaxExecutionTime(maxExecutionTime);
			query.setIncludeInferred(false);

			if (context != null) {
				IRI contextURI = rc.getValueFactory().createIRI(context.getURI().toString());
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
			throw switch (e) {
				case RepositoryException re -> new InternalServerErrorException("SPARQL repository error", re);
				case TupleQueryResultHandlerException he -> new InternalServerErrorException("SPARQL result serialization failed", he);
				default -> new InternalServerErrorException("SPARQL query evaluation failed", e);
			};
		}
	}

	private static TupleExpr extractTupleExpr(TupleQuery query) {
		// SailTupleQuery exposes the already-parsed algebra so the federated-service detector runs
		// against exactly what the executor will run. A non-Sail backend (e.g. an HTTP- or SPARQL-
		// endpoint repository) could produce a different algebra at prepare time, weakening the
		// SERVICE-clause prohibition — fail closed here so a future config change cannot silently
		// undermine the SSRF defense.
		if (query instanceof SailTupleQuery sailQuery) {
			return sailQuery.getParsedQuery().getTupleExpr();
		}
		throw new InternalServerErrorException(
				"Public SPARQL endpoint requires a Sail-backed repository to enforce SERVICE-clause prohibition");
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
		// Strip control chars so a query containing CR/LF cannot forge log lines.
		String visible = value.replaceAll("\\p{Cntrl}", "?");
		return visible.length() <= max ? visible : visible.substring(0, max) + "…";
	}

	private static RepositoryConnection acquireConnection(PublicRepository publicRepository) {
		RepositoryConnection rc = publicRepository.getConnection();
		if (rc == null) {
			throw new InternalServerErrorException("Public SPARQL endpoint connection unavailable");
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
			this.delegate = delegate;
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
