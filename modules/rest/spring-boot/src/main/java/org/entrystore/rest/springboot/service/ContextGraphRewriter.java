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
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.model.vocabulary.FN;
import org.eclipse.rdf4j.query.Dataset;
import org.eclipse.rdf4j.query.algebra.And;
import org.eclipse.rdf4j.query.algebra.ArbitraryLengthPath;
import org.eclipse.rdf4j.query.algebra.BindingSetAssignment;
import org.eclipse.rdf4j.query.algebra.Count;
import org.eclipse.rdf4j.query.algebra.ExtensionElem;
import org.eclipse.rdf4j.query.algebra.Filter;
import org.eclipse.rdf4j.query.algebra.FunctionCall;
import org.eclipse.rdf4j.query.algebra.Group;
import org.eclipse.rdf4j.query.algebra.GroupElem;
import org.eclipse.rdf4j.query.algebra.IsURI;
import org.eclipse.rdf4j.query.algebra.Not;
import org.eclipse.rdf4j.query.algebra.Projection;
import org.eclipse.rdf4j.query.algebra.ProjectionElem;
import org.eclipse.rdf4j.query.algebra.ProjectionElemList;
import org.eclipse.rdf4j.query.algebra.StatementPattern;
import org.eclipse.rdf4j.query.algebra.Str;
import org.eclipse.rdf4j.query.algebra.TupleExpr;
import org.eclipse.rdf4j.query.algebra.ValueConstant;
import org.eclipse.rdf4j.query.algebra.ValueExpr;
import org.eclipse.rdf4j.query.algebra.Var;
import org.eclipse.rdf4j.query.algebra.ZeroLengthPath;
import org.eclipse.rdf4j.query.algebra.helpers.AbstractQueryModelVisitor;
import org.eclipse.rdf4j.query.impl.SimpleDataset;
import org.eclipse.rdf4j.query.parser.ParsedQuery;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Restricts a public-repository query to the context graphs.
 *
 * <p>The public repository stores each public statement twice: in the graph of its entry's metadata (or resource,
 * or cached external metadata) and in the graph of its context, {@code {repository URL}{context id}}. RDF4J's
 * default graph without a dataset is the union of all graphs and returns a statement once per graph, so every triple
 * pattern outside a GRAPH clause matches each statement twice and a query with k such patterns repeats each solution
 * up to 2^k times.
 *
 * <p>Every triple pattern outside a GRAPH clause, wherever it is nested (OPTIONAL, UNION, MINUS, EXISTS,
 * subqueries, the step of a {@code p+} or {@code p*} path), reads all named graphs into a fresh anonymous variable
 * and keeps only the statements whose graph is a context graph. The condition sits directly on the pattern, so
 * OPTIONAL and MINUS keep their meaning, and the variable is never projected. A statement then matches once per
 * context that holds it. {@code COUNT(DISTINCT *)} compares whole solutions, so the solutions it counts are first
 * projected onto the variables of the query, without the helper variables.
 *
 * <p>{@link #hideEntryGraphs} makes a query see a repository of only the context graphs: patterns and paths inside
 * GRAPH keep only context graphs too, through the same condition on their graph variable or IRI, and a FROM or FROM
 * NAMED list keeps only its context graphs. {@link #restrictDefaultGraph} rewrites only the patterns outside GRAPH of
 * a query without a dataset: GRAPH patterns, including {@code GRAPH ?g}, and FROM and FROM NAMED lists keep their
 * per-graph semantics, so each graph they name or bind contributes its copy.
 *
 * <p>Two forms read no graph and are left as they are: the zero-length step of {@code p*} and {@code p?} outside
 * GRAPH ({@link ZeroLengthPath}), which matches the nodes of all graphs, and RDF 1.2 triple terms
 * ({@link org.eclipse.rdf4j.query.algebra.ReifiedTripleRef}), which match triple terms wherever they occur. Both match
 * the same values in the entry graphs as in the context graphs, which hold copies of the same statements. Inside
 * GRAPH with an unbound graph variable, RDF4J binds a zero-length match only to the first graph that holds the node,
 * usually its entry graph; {@link #hideEntryGraphs} drops that binding, so such a match is missing rather than shown
 * with the context graph.
 */
final class ContextGraphRewriter {

	private static final String GRAPH_VAR_PREFIX = "_entrystore_graph_";

	/**
	 * Stands in for a dataset part that keeps no graph: RDF4J reads a dataset whose default and named graph sets are
	 * both empty as the union of all graphs.
	 */
	private static final IRI NO_GRAPH = SimpleValueFactory.getInstance().createIRI("urn:x-entrystore:no-graph");

	private ContextGraphRewriter() {
	}

	/**
	 * Rewrites the patterns outside GRAPH of a query without a dataset, in place.
	 *
	 * @param expr                the parsed algebra of a query that declares no dataset (no FROM or FROM NAMED)
	 * @param contextGraphPrefix  the repository URL as core prefixes context ids with it; a context graph is this
	 *                            prefix followed by a context id without {@code /}
	 */
	static void restrictDefaultGraph(TupleExpr expr, String contextGraphPrefix) {
		rewrite(expr, contextGraphPrefix, false);
	}

	/**
	 * Restricts {@code query} in place to the context graphs: its FROM and FROM NAMED lists if it declares a dataset,
	 * otherwise every pattern and path, inside GRAPH or not.
	 *
	 * @param query               a parsed query
	 * @param contextGraphPrefix  as for {@link #restrictDefaultGraph}
	 */
	static void hideEntryGraphs(ParsedQuery query, String contextGraphPrefix) {
		Dataset dataset = query.getDataset();
		if (dataset == null) {
			rewrite(query.getTupleExpr(), contextGraphPrefix, true);
		} else {
			query.setDataset(contextGraphsOf(dataset, contextGraphPrefix));
		}
	}

	private static Dataset contextGraphsOf(Dataset dataset, String contextGraphPrefix) {
		SimpleDataset restricted = new SimpleDataset();
		dataset.getDefaultGraphs().stream()
				.filter(graph -> isContextGraph(graph, contextGraphPrefix))
				.forEach(restricted::addDefaultGraph);
		dataset.getNamedGraphs().stream()
				.filter(graph -> isContextGraph(graph, contextGraphPrefix))
				.forEach(restricted::addNamedGraph);
		if (restricted.getDefaultGraphs().isEmpty()) {
			restricted.addDefaultGraph(NO_GRAPH);
		}
		if (restricted.getNamedGraphs().isEmpty()) {
			restricted.addNamedGraph(NO_GRAPH);
		}
		return restricted;
	}

	/** The same test as {@link #isContextGraph(Var, String)}, for a graph named in a dataset. */
	private static boolean isContextGraph(IRI graph, String prefix) {
		String iri = graph.stringValue();
		return iri.startsWith(prefix) && iri.indexOf('/', prefix.length()) < 0;
	}

	private static void rewrite(TupleExpr expr, String contextGraphPrefix, boolean graphPatternsToo) {
		List<StatementPattern> patternsWithoutGraph = new ArrayList<>();
		List<TupleExpr> graphPatterns = new ArrayList<>();
		Set<String> names = new HashSet<>();
		List<Group> distinctSolutionCounts = new ArrayList<>();
		expr.visit(new AbstractQueryModelVisitor<RuntimeException>() {
			@Override
			public void meet(StatementPattern node) {
				if (node.getContextVar() == null) {
					if (graphPatternsToo || node.getScope() == StatementPattern.Scope.DEFAULT_CONTEXTS) {
						patternsWithoutGraph.add(node);
					}
				} else if (graphPatternsToo) {
					graphPatterns.add(node);
				}
				super.meet(node);
			}

			@Override
			public void meet(ArbitraryLengthPath node) {
				if (graphPatternsToo && node.getContextVar() != null) {
					graphPatterns.add(node);
				}
				super.meet(node);
			}

			@Override
			public void meet(ZeroLengthPath node) {
				if (graphPatternsToo && node.getContextVar() != null) {
					graphPatterns.add(node);
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
			public void meet(Group node) {
				names.addAll(node.getGroupBindingNames());
				if (node.getGroupElements().stream().anyMatch(e -> e.getOperator() instanceof Count count
						&& count.isDistinct() && count.getArg() == null)) {
					distinctSolutionCounts.add(node);
				}
				super.meet(node);
			}
		});

		for (TupleExpr graphPattern : graphPatterns) {
			Var graph = switch (graphPattern) {
				case StatementPattern pattern -> pattern.getContextVar();
				case ArbitraryLengthPath path -> path.getContextVar();
				case ZeroLengthPath path -> path.getContextVar();
				default -> throw new IllegalStateException("Unexpected graph pattern " + graphPattern);
			};
			Filter filter = new Filter();
			graphPattern.replaceWith(filter);
			filter.setArg(graphPattern);
			filter.setCondition(isContextGraph(graph, contextGraphPrefix));
		}

		Set<String> helperNames = new HashSet<>();
		int counter = 0;
		for (StatementPattern pattern : patternsWithoutGraph) {
			String name;
			do {
				name = GRAPH_VAR_PREFIX + counter++;
			} while (names.contains(name));
			helperNames.add(name);
			Var graph = Var.of(name, true);
			StatementPattern allGraphs = new StatementPattern(StatementPattern.Scope.NAMED_CONTEXTS,
					pattern.getSubjectVar().clone(), pattern.getPredicateVar().clone(),
					pattern.getObjectVar().clone(), graph);
			pattern.replaceWith(new Filter(allGraphs, isContextGraph(graph, contextGraphPrefix)));
		}

		for (Group group : distinctSolutionCounts) {
			ProjectionElemList queryVariables = new ProjectionElemList();
			group.getArg().getBindingNames().stream()
					.filter(name -> !helperNames.contains(name))
					.forEach(name -> queryVariables.addElement(new ProjectionElem(name)));
			group.setArg(new Projection(group.getArg(), queryVariables, true));
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
