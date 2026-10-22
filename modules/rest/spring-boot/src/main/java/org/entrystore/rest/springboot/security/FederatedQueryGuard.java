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

import org.eclipse.rdf4j.query.Query;
import org.eclipse.rdf4j.query.QueryEvaluationException;
import org.eclipse.rdf4j.query.algebra.Service;
import org.eclipse.rdf4j.query.algebra.evaluation.federation.FederatedServiceResolver;
import org.eclipse.rdf4j.query.algebra.helpers.AbstractQueryModelVisitor;
import org.eclipse.rdf4j.repository.sail.SailQuery;
import org.entrystore.rest.springboot.model.exception.BadRequestException;
import org.entrystore.rest.springboot.model.exception.InternalServerErrorException;

/**
 * Keeps user-supplied SPARQL from making EntryStore send requests to other hosts through SERVICE clauses (SSRF).
 */
public final class FederatedQueryGuard {

	/**
	 * Resolver that refuses every SERVICE endpoint, for in-memory stores that evaluate user-supplied queries. Without
	 * it, a {@code MemoryStore} creates a {@code SPARQLServiceResolver} that sends the request.
	 */
	public static final FederatedServiceResolver DENY_ALL = serviceUrl -> {
		throw new QueryEvaluationException("SPARQL SERVICE clauses are not permitted");
	};

	private FederatedQueryGuard() {
	}

	/**
	 * Rejects a prepared query whose parsed algebra contains a SERVICE clause, at any depth (subqueries, OPTIONAL,
	 * UNION, FILTER EXISTS) and with a constant or variable endpoint. Must be called before the query is evaluated.
	 *
	 * @throws BadRequestException          with {@code rejectionMessage} when a SERVICE clause is present
	 * @throws InternalServerErrorException when the query is not Sail-backed: its algebra cannot be inspected, so it
	 *                                      is refused rather than run unchecked
	 */
	public static void rejectServiceClauses(Query query, String rejectionMessage) {
		if (!(query instanceof SailQuery sailQuery)) {
			throw new InternalServerErrorException("SPARQL query is not Sail-backed (got "
					+ query.getClass().getName() + "); its SERVICE clauses cannot be inspected");
		}
		ServiceDetector detector = new ServiceDetector();
		sailQuery.getParsedQuery().getTupleExpr().visit(detector);
		if (detector.found) {
			throw new BadRequestException(rejectionMessage);
		}
	}

	private static final class ServiceDetector extends AbstractQueryModelVisitor<RuntimeException> {
		boolean found;

		@Override
		public void meet(Service node) {
			found = true;
		}
	}
}
