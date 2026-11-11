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

import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.model.vocabulary.FN;
import org.eclipse.rdf4j.query.algebra.And;
import org.eclipse.rdf4j.query.algebra.BindingSetAssignment;
import org.eclipse.rdf4j.query.algebra.Count;
import org.eclipse.rdf4j.query.algebra.ExtensionElem;
import org.eclipse.rdf4j.query.algebra.Filter;
import org.eclipse.rdf4j.query.algebra.FunctionCall;
import org.eclipse.rdf4j.query.algebra.GroupElem;
import org.eclipse.rdf4j.query.algebra.IsURI;
import org.eclipse.rdf4j.query.algebra.Not;
import org.eclipse.rdf4j.query.algebra.ProjectionElem;
import org.eclipse.rdf4j.query.algebra.StatementPattern;
import org.eclipse.rdf4j.query.algebra.Str;
import org.eclipse.rdf4j.query.algebra.TupleExpr;
import org.eclipse.rdf4j.query.algebra.ValueConstant;
import org.eclipse.rdf4j.query.algebra.ValueExpr;
import org.eclipse.rdf4j.query.algebra.Var;
import org.eclipse.rdf4j.query.algebra.helpers.AbstractQueryModelVisitor;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Restricts the default graph of a public-repository query to the context graphs.
 *
 * <p>The public repository stores each public statement twice: in the graph of its entry's metadata (or resource)
 * and in the graph of its context, {@code {repository URL}{context id}}. RDF4J's default graph without a dataset is
 * the union of all graphs and returns a statement once per graph, so every triple pattern outside a GRAPH clause
 * matches each statement twice and a query with k such patterns repeats each solution up to 2^k times.
 *
 * <p>Every triple pattern outside a GRAPH clause, wherever it is nested (OPTIONAL, UNION, MINUS, EXISTS,
 * subqueries, the step of a {@code p+} or {@code p*} path), reads all named graphs into a fresh anonymous variable
 * and keeps only the statements whose graph is a context graph. The condition sits directly on the pattern, so
 * OPTIONAL and MINUS keep their meaning, and the variable is never projected. A statement then matches once per
 * context that holds it. Patterns inside a GRAPH clause are left unchanged, and so is the zero-length step of
 * {@code p*} and {@code p?} ({@link org.eclipse.rdf4j.query.algebra.ZeroLengthPath}), which RDF4J evaluates
 * without a triple pattern. A query with {@code COUNT(DISTINCT *)} is left unchanged: it compares whole solutions,
 * helper variables included, and already counts each solution once without the rewrite.
 */
final class ContextGraphRewriter {

	private static final String GRAPH_VAR_PREFIX = "_entrystore_graph_";

	private ContextGraphRewriter() {
	}

	/**
	 * Rewrites {@code expr} in place.
	 *
	 * @param expr                the parsed algebra of a query that declares no dataset (no FROM or FROM NAMED)
	 * @param contextGraphPrefix  the repository URL ending with {@code /}; a context graph is this prefix followed
	 *                            by a context id without {@code /}
	 */
	static void restrictDefaultGraph(TupleExpr expr, String contextGraphPrefix) {
		List<StatementPattern> defaultPatterns = new ArrayList<>();
		Set<String> names = new HashSet<>();
		boolean[] countsDistinctSolutions = {false};
		expr.visit(new AbstractQueryModelVisitor<RuntimeException>() {
			@Override
			public void meet(StatementPattern node) {
				if (node.getScope() == StatementPattern.Scope.DEFAULT_CONTEXTS && node.getContextVar() == null) {
					defaultPatterns.add(node);
				}
				super.meet(node);
			}

			@Override
			public void meet(Var node) {
				names.add(node.getName());
			}

			@Override
			public void meet(BindingSetAssignment node) {
				names.addAll(node.getBindingNames());
			}

			@Override
			public void meet(ExtensionElem node) {
				names.add(node.getName());
				super.meet(node);
			}

			@Override
			public void meet(ProjectionElem node) {
				names.add(node.getName());
				node.getProjectionAlias().ifPresent(names::add);
			}

			@Override
			public void meet(GroupElem node) {
				names.add(node.getName());
				super.meet(node);
			}

			@Override
			public void meet(Count node) {
				countsDistinctSolutions[0] |= node.isDistinct() && node.getArg() == null;
				super.meet(node);
			}
		});
		if (countsDistinctSolutions[0]) {
			return;
		}

		int counter = 0;
		for (StatementPattern pattern : defaultPatterns) {
			String name;
			do {
				name = GRAPH_VAR_PREFIX + counter++;
			} while (names.contains(name));
			Var graph = Var.of(name, true);
			StatementPattern allGraphs = new StatementPattern(StatementPattern.Scope.NAMED_CONTEXTS,
					pattern.getSubjectVar().clone(), pattern.getPredicateVar().clone(),
					pattern.getObjectVar().clone(), graph);
			pattern.replaceWith(new Filter(allGraphs, isContextGraph(graph, contextGraphPrefix)));
		}
	}

	/** {@code isIRI(?g) && STRSTARTS(STR(?g), prefix) && !CONTAINS(STRAFTER(STR(?g), prefix), "/")} */
	private static ValueExpr isContextGraph(Var graph, String prefix) {
		var vf = SimpleValueFactory.getInstance();
		ValueConstant prefixLiteral = new ValueConstant(vf.createLiteral(prefix));
		ValueExpr startsWithPrefix = new FunctionCall(FN.STARTS_WITH.stringValue(), new Str(graph.clone()),
				prefixLiteral);
		ValueExpr idWithoutSlash = new Not(new FunctionCall(FN.CONTAINS.stringValue(),
				new FunctionCall(FN.SUBSTRING_AFTER.stringValue(), new Str(graph.clone()), prefixLiteral.clone()),
				new ValueConstant(vf.createLiteral("/"))));
		return new And(new IsURI(graph.clone()), new And(startsWithPrefix, idWithoutSlash));
	}
}
