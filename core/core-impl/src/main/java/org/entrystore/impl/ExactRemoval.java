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

import org.eclipse.rdf4j.model.BNode;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.query.BindingSet;
import org.eclipse.rdf4j.query.TupleQuery;
import org.eclipse.rdf4j.query.TupleQueryResult;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.RepositoryResult;
import org.entrystore.repository.util.URISplit;
import org.entrystore.repository.util.URIType;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

import static org.eclipse.rdf4j.model.util.Values.iri;

/**
 * Reads graphs and removes what was read by exact quad, so that a caller can read outside the repository monitor and
 * the transaction holding it finds each statement through a triple index instead of scanning the store. Shared by
 * {@link ContextRemover} and {@link EntryPurge}.
 *
 * <p>Reading one graph scans the whole store unless the store has an index starting with the context; a store pass,
 * see {@link #read(RepositoryConnection, Collection)}, then reads many graphs for the cost of one.
 *
 * <p>Statements of an entry graph or metadata graph whose object is another entry may have a copy in that entry's
 * relations graph, which {@code EntryImpl} and {@code MetadataImpl} maintain. Such a copy is removed with its source,
 * unless a graph that stays, other than a relations graph, still states the triple.
 */
final class ExactRemoval {

	/** The named graphs that state the triple bound to {@code ?s ?p ?o}. */
	private static final String GRAPHS_STATING = "SELECT DISTINCT ?g WHERE { GRAPH ?g { ?s ?p ?o } }";

	/** Read by one store pass above this many graphs, where a graph read scans. */
	private static final int STORE_PASS_ABOVE = 8;

	private final RepositoryManagerImpl repositoryManager;
	private final String base;

	ExactRemoval(RepositoryManagerImpl repositoryManager) {
		this.repositoryManager = repositoryManager;
		this.base = repositoryManager.getRepositoryURL().toString();
	}

	/**
	 * Statements of the graph, at most {@code limit}, each given that graph: a SPARQL store outside quad mode returns
	 * them without it, and a removal without a graph reaches every graph.
	 */
	static List<Statement> read(RepositoryConnection reader, IRI graph, int limit) {
		ValueFactory vf = reader.getValueFactory();
		List<Statement> statements = new ArrayList<>();
		try (RepositoryResult<Statement> result = reader.getStatements(null, null, null, false, graph)) {
			while (statements.size() < limit && result.hasNext()) {
				Statement statement = result.next();
				statements.add(vf.createStatement(statement.getSubject(), statement.getPredicate(),
						statement.getObject(), graph));
			}
		}
		return statements;
	}

	/**
	 * The statements of each graph, each given its graph, in the order of {@code graphs}. Where reading a graph scans
	 * the store, more than {@link #STORE_PASS_ABOVE} graphs are read in one pass over the store instead.
	 */
	Map<IRI, List<Statement>> read(RepositoryConnection reader, Collection<IRI> graphs) {
		Map<IRI, List<Statement>> statements = new LinkedHashMap<>();
		if (!repositoryManager.readingGraphsByScan || graphs.size() <= STORE_PASS_ABOVE) {
			for (IRI graph : graphs) {
				statements.put(graph, read(reader, graph, Integer.MAX_VALUE));
			}
			return statements;
		}
		for (IRI graph : graphs) {
			statements.put(graph, new ArrayList<>());
		}
		try (RepositoryResult<Statement> all = reader.getStatements(null, null, null, false)) {
			for (Statement statement : all) {
				if (statement.getContext() instanceof IRI graph) {
					List<Statement> ofGraph = statements.get(graph);
					if (ofGraph != null) {
						ofGraph.add(statement);
					}
				}
			}
		}
		return statements;
	}

	/**
	 * The copies other entries' relations graphs hold of these statements of one entry or metadata graph, as found
	 * when reading them. A source whose object satisfies {@code removedWithRelations} is skipped, since the relations
	 * graph its copy is in goes too.
	 */
	List<Statement> relationCopies(RepositoryConnection reader, Collection<Statement> sources, boolean fromEntryGraph,
			Predicate<IRI> removedWithRelations) {
		List<Statement> copies = new ArrayList<>();
		for (Statement source : sources) {
			if (!isCopiedToRelations(source, fromEntryGraph) || !(source.getObject() instanceof IRI object)
					|| removedWithRelations.test(object)) {
				continue;
			}
			Optional<IRI> relationsGraph = relationsGraphOf(object);
			if (relationsGraph.isPresent() && reader.hasStatement(source.getSubject(), source.getPredicate(), object,
					false, relationsGraph.get())) {
				copies.add(reader.getValueFactory().createStatement(source.getSubject(), source.getPredicate(), object,
						relationsGraph.get()));
			}
		}
		return copies;
	}

	/**
	 * Removes statements read by {@link #read}, each within its graph. With {@code blankNodesByPattern}, a blank node
	 * is matched by a wildcard, which only widens the removal within a graph the caller deletes anyway. Refuses a
	 * statement without a graph, whose removal would reach every graph.
	 *
	 * @param owner names what the statements belong to, for the refusal
	 */
	static void removeOwn(RepositoryConnection rc, Collection<Statement> statements, boolean blankNodesByPattern,
			String owner) {
		if (statements.stream().anyMatch(statement -> statement.getContext() == null)) {
			throw new IllegalStateException("Statements of " + owner + " lack their graph");
		}
		if (!blankNodesByPattern) {
			rc.remove(statements);
			return;
		}
		for (Statement statement : statements) {
			rc.remove(statement.getSubject() instanceof BNode ? null : statement.getSubject(),
					statement.getPredicate(), statement.getObject() instanceof BNode ? null : statement.getObject(),
					statement.getContext());
		}
	}

	/**
	 * Removes the copies that no graph still states, other than a graph satisfying {@code removed} or a relations
	 * graph. Checked in the removing transaction, since a remaining entry may have stated the same triple after the
	 * copies were read.
	 */
	void removeCopies(RepositoryConnection rc, Collection<Statement> copies, Predicate<IRI> removed) {
		if (copies.isEmpty()) {
			return;
		}
		TupleQuery graphsStating = rc.prepareTupleQuery(GRAPHS_STATING);
		for (Statement copy : copies) {
			if (!isStatedOutside(graphsStating, copy, removed)) {
				rc.remove(copy);
			}
		}
	}

	/** The relations graph of the entry an IRI belongs to, if it belongs to an entry of this repository. */
	Optional<IRI> relationsGraphOf(IRI iri) {
		try {
			return split(iri).filter(uriSplit -> uriSplit.getUriType() != URIType.Unknown)
					.map(uriSplit -> iri(uriSplit.getRelationURI().toString()));
		} catch (IllegalArgumentException e) {
			return Optional.empty();
		}
	}

	/** The resources of the lists that hold the entry, read through the posc index from its relations graph. */
	static Set<IRI> listsHolding(RepositoryConnection reader, IRI entry, IRI relations) {
		Set<IRI> lists = new HashSet<>();
		try (RepositoryResult<Statement> holding = reader.getStatements(null, RepositoryProperties.hasListMember,
				entry, false, relations)) {
			for (Statement statement : holding) {
				if (statement.getSubject() instanceof IRI list) {
					lists.add(list);
				}
			}
		}
		return lists;
	}

	private boolean isRelationsGraph(IRI graph) {
		return split(graph).filter(uriSplit -> RepositoryProperties.RELATION.equals(uriSplit.getPath())).isPresent();
	}

	/**
	 * Whether {@code EntryImpl} or {@code MetadataImpl} copies a statement of an entry or metadata graph into the
	 * relations graph of the entry its object belongs to.
	 */
	private boolean isCopiedToRelations(Statement statement, boolean fromEntryGraph) {
		if (fromEntryGraph) {
			return EntryImpl.isStatementInvRelationCandidate(statement, base);
		}
		return statement.getObject() instanceof IRI object && object.stringValue().startsWith(base)
				&& statement.getSubject().stringValue().startsWith(base);
	}

	/**
	 * Whether a graph not satisfying {@code removed}, other than a relations graph, states the triple of a copy. Asked
	 * by graph, since a SPARQL store outside quad mode returns statements without theirs.
	 */
	private boolean isStatedOutside(TupleQuery graphsStating, Statement copy, Predicate<IRI> removed) {
		graphsStating.setBinding("s", copy.getSubject());
		graphsStating.setBinding("p", copy.getPredicate());
		graphsStating.setBinding("o", copy.getObject());
		try (TupleQueryResult holders = graphsStating.evaluate()) {
			for (BindingSet holder : holders) {
				if (holder.getValue("g") instanceof IRI graph && !removed.test(graph) && !isRelationsGraph(graph)) {
					return true;
				}
			}
		}
		return false;
	}

	private Optional<URISplit> split(IRI iri) {
		try {
			return URISplit.of(URI.create(iri.stringValue()), repositoryManager.getRepositoryURL());
		} catch (IllegalArgumentException e) {
			return Optional.empty();
		}
	}
}
