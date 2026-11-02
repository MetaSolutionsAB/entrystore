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

import lombok.extern.slf4j.Slf4j;
import org.eclipse.rdf4j.model.BNode;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Resource;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.query.BindingSet;
import org.eclipse.rdf4j.query.TupleQuery;
import org.eclipse.rdf4j.query.TupleQueryResult;
import org.eclipse.rdf4j.repository.Repository;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.RepositoryResult;
import org.eclipse.rdf4j.repository.sparql.SPARQLRepository;
import org.entrystore.Entry;
import org.entrystore.SearchIndex;
import org.entrystore.repository.config.Settings;
import org.entrystore.repository.util.URISplit;
import org.entrystore.repository.util.URIType;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static org.eclipse.rdf4j.model.util.Values.iri;

/**
 * Deletes the graphs, search documents and files of removed contexts in the background.
 *
 * <p>Removing a context only takes its entry out of {@code _contexts} and records a
 * {@link RepositoryProperties#removalPending} marker, in one short transaction. This job then deletes every graph
 * named {@code {base}{contextId}} or starting with {@code {base}{contextId}/}, in chunks of {@link #chunkSize}
 * statements. A chunk is read without a transaction or the repository monitor and removed in a transaction of
 * its own under the monitor, so other writers wait for one chunk at most. Each statement is removed by its whole
 * triple within its graph, which any triple index finds without a scan (on a SPARQL store, with wildcards for its
 * blank nodes); reading a graph scans the store unless the store has an index starting with the context.
 *
 * <p>Statements of the context whose object is an entry of another context may have a copy in that entry's
 * relations graph. The copy is removed with them, unless a graph outside the context, other than a relations
 * graph, still states the triple.
 *
 * <p>The marker survives a restart, after which {@link #resumePending()} continues the job. A job that fails
 * stops and keeps the marker, so the context stays hidden and its id taken until a later start completes it. The
 * search purge alone is best effort: the same purge already ran when the context was removed.
 *
 * <p>Known limit: an update to an entry of the context that was loaded before the removal, and that commits
 * after the job's last pass over the context's graphs, leaves that entry's graph behind.
 */
@Slf4j
final class ContextRemover {

	/** Passes over the context's graphs, or its data folder, before giving up on what keeps appearing in them. */
	private static final int MAX_PASSES = 3;

	/** A live reindex skips a removed context; only the full wipe of a startup reindex reaches its documents. */
	private static final String SEARCH_PURGE_HINT =
			"only a reindex at startup (entrystore.solr.reindex-on-startup with .wait) removes any left";

	/** The named graphs that state the triple bound to {@code ?s ?p ?o}. */
	private static final String GRAPHS_STATING = "SELECT DISTINCT ?g WHERE { GRAPH ?g { ?s ?p ?o } }";

	private final RepositoryManagerImpl repositoryManager;
	private final Repository repository;
	private final IRI contextsIndexGraph;
	private final Set<String> pending = ConcurrentHashMap.newKeySet();
	private final ExecutorService executor =
			Executors.newSingleThreadExecutor(Thread.ofPlatform().name("context-removal").daemon().factory());

	/** Set by {@link #shutdown()}. Package-private so a test can tell when the job will stop. */
	volatile boolean stopping;

	/** Statements removed per transaction. Package-private and non-final so tests can force several chunks. */
	volatile int chunkSize = 10_000;

	/** Graphs listed at a time, which bounds the heap a large context takes. Package-private for tests. */
	volatile int graphsPerListing = 10_000;

	/**
	 * Whether to remove a statement holding blank nodes by a pattern with wildcards in their place: a SPARQL store
	 * cannot match blank nodes it returned, while a pattern on a store without a context-first index can scan.
	 * Package-private for tests.
	 */
	volatile boolean blankNodesByPattern;

	/** Supplies the search index to purge, or null without Solr. Package-private for tests. */
	volatile Supplier<SearchIndex> searchIndex;

	/** Run on the job thread before each chunk is read; for tests. */
	volatile Runnable beforeChunk = () -> {};

	/** Run on the job thread after each chunk has been committed and the monitor released; for tests. */
	volatile Runnable afterChunk = () -> {};

	/** Run on the job thread before each file or folder of a data folder is deleted; for tests. */
	volatile Consumer<Path> beforeDelete = path -> {};

	ContextRemover(RepositoryManagerImpl repositoryManager, IRI contextsIndexGraph) {
		this(repositoryManager, repositoryManager.getRepository(), contextsIndexGraph);
	}

	/** Works on {@code repository} in place of the repository manager's; for tests. */
	ContextRemover(RepositoryManagerImpl repositoryManager, Repository repository, IRI contextsIndexGraph) {
		this.repositoryManager = repositoryManager;
		this.repository = repository;
		this.contextsIndexGraph = contextsIndexGraph;
		this.searchIndex = repositoryManager::getIndex;
		this.blankNodesByPattern = repository instanceof SPARQLRepository;
	}

	boolean isPending(String contextId) {
		return contextId != null && pending.contains(contextId);
	}

	/**
	 * Takes the id of a context whose marker has just been committed out of use and starts deleting its graphs.
	 * Does not throw: the removal of the context already stands, and a job that cannot be started is resumed on
	 * the next start.
	 */
	void schedule(String contextId) {
		pending.add(contextId);
		submit(contextId);
	}

	/**
	 * Takes the ids of the contexts whose removal had not finished out of use and resumes deleting their graphs.
	 * A context already pending is skipped, so calling this again does not start a second job.
	 */
	void resumePending() {
		List<String> contextIds = new ArrayList<>();
		String base = repositoryManager.getRepositoryURL().toString();
		try (RepositoryConnection rc = repository.getConnection();
			 RepositoryResult<Statement> markers = rc.getStatements(
					 null, RepositoryProperties.removalPending, null, false, contextsIndexGraph)) {
			for (Statement marker : markers) {
				String contextId = URISplit.getLastSegment(marker.getSubject().stringValue());
				// the id names graphs and a folder to delete, so a marker that names no context is not acted on
				if (contextId.isEmpty() || !marker.getSubject().stringValue().equals(base + contextId)) {
					log.error("Removal marker {} names no context of this repository; ignoring it", marker);
					continue;
				}
				contextIds.add(contextId);
			}
		}
		for (String contextId : contextIds) {
			if (pending.add(contextId)) {
				log.info("Resuming the removal of context {}", contextId);
				submit(contextId);
			}
		}
	}

	/** Stops the job between two chunks; the markers stay, so the next start resumes it. */
	void shutdown() {
		stopping = true;
		executor.shutdown();
		try {
			if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
				log.warn("Context removal did not stop within 30 seconds");
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	/** Waits until every job scheduled so far has ended; for tests. */
	void awaitIdle() throws InterruptedException, ExecutionException, TimeoutException {
		executor.submit(() -> {}).get(60, TimeUnit.SECONDS);
	}

	private void submit(String contextId) {
		try {
			executor.execute(() -> remove(contextId));
		} catch (RejectedExecutionException e) {
			log.error("Could not start removing context {}, the removal resumes on the next start", contextId, e);
		}
	}

	private void remove(String contextId) {
		Removal removal = new Removal(contextId);
		long start = System.currentTimeMillis();
		log.info("Removing context {}", contextId);
		try {
			removal.removeGraphs();
			removeSearchDocuments(contextId, removal.contextURI);
			deleteDataFolder(contextId);
			// sweeps what an entry loaded before the removal has written meanwhile
			removal.removeGraphs();
			complete(contextId, removal.contextURI);
			log.info("Removed context {} ({} statements) in {} ms", contextId, removal.removed,
					System.currentTimeMillis() - start);
		} catch (StoppedException e) {
			log.info("Stopped removing context {} after {} statements, the removal resumes on the next start",
					contextId, removal.removed);
		} catch (Throwable e) {
			log.error("Removing context {} failed after {} statements; it stays hidden and the removal resumes on "
					+ "the next start", contextId, removal.removed, e);
			// logged first: the executor would otherwise report it on System.err only
			if (e instanceof Error error) {
				throw error;
			}
		}
	}

	/** Up to {@link #graphsPerListing} graphs of the context: its index graph and the graphs under its URI. */
	private List<IRI> listGraphs(String contextURI) {
		int limit = graphsPerListing;
		List<IRI> graphs = new ArrayList<>();
		try (RepositoryConnection rc = repository.getConnection();
			 RepositoryResult<Resource> namedGraphs = rc.getContextIDs()) {
			while (graphs.size() < limit && namedGraphs.hasNext()) {
				if (namedGraphs.next() instanceof IRI graph && isOfContext(graph, contextURI)) {
					graphs.add(graph);
				}
			}
		}
		return graphs;
	}

	private static boolean isOfContext(IRI iri, String contextURI) {
		return iri.stringValue().equals(contextURI) || iri.stringValue().startsWith(contextURI + "/");
	}

	/** Best effort, see the class Javadoc: a failure is logged and the job goes on. */
	private void removeSearchDocuments(String contextId, String contextURI) {
		SearchIndex index = searchIndex.get();
		if (index == null) {
			return;
		}
		try {
			if (!index.removeContext(URI.create(contextURI))) {
				log.error("Could not remove the search documents of removed context {}; {}", contextId,
						SEARCH_PURGE_HINT);
			}
		} catch (RuntimeException e) {
			log.error("Could not remove the search documents of removed context {}; {}: {}", contextId,
					SEARCH_PURGE_HINT, e.getMessage(), e);
		}
	}

	/**
	 * Deletes the context's folder in the data folder. Refuses anything but a direct child of the data folder,
	 * such as a symbolic link or a folder an odd id resolves to, and does not follow links inside it. An upload in
	 * flight when the context was removed may still add or delete its staging files, so a file that vanishes is
	 * skipped and a folder that gained one is deleted again.
	 */
	private void deleteDataFolder(String contextId) throws IOException {
		String configured = repositoryManager.getConfiguration().getString(Settings.DATA_FOLDER);
		if (configured == null) {
			return;
		}
		File root = DataImpl.dataFolder(configured).getCanonicalFile();
		File folder = new File(root, contextId);
		if (!folder.exists()) {
			return;
		}
		if (!root.equals(folder.getCanonicalFile().getParentFile())) {
			throw new IOException("Refusing to delete " + folder + ", which is not a folder in the data folder "
					+ root);
		}
		for (int pass = 1; pass < MAX_PASSES; pass++) {
			try {
				deleteTree(folder.toPath());
				return;
			} catch (DirectoryNotEmptyException e) {
				log.info("A file appeared in {} while it was being deleted, deleting it again", e.getFile());
			}
		}
		deleteTree(folder.toPath());
	}

	/** Deletes a folder and what it holds without following links, skipping a file that vanishes meanwhile. */
	private void deleteTree(Path folder) throws IOException {
		Files.walkFileTree(folder, new SimpleFileVisitor<>() {
			@Override
			public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
				beforeDelete.accept(file);
				Files.deleteIfExists(file);
				return FileVisitResult.CONTINUE;
			}

			@Override
			public FileVisitResult visitFileFailed(Path file, IOException e) throws IOException {
				if (e instanceof NoSuchFileException) {
					return FileVisitResult.CONTINUE;
				}
				throw e;
			}

			@Override
			public FileVisitResult postVisitDirectory(Path dir, IOException e) throws IOException {
				if (e != null && !(e instanceof NoSuchFileException)) {
					throw e;
				}
				beforeDelete.accept(dir);
				Files.deleteIfExists(dir);
				return FileVisitResult.CONTINUE;
			}
		});
	}

	/**
	 * Evicts the context's entries from the cache, which the pending id still hides, then removes the marker and
	 * frees the id. The evicted entries, and the context instances they hold, are flagged deleted first: a thread
	 * may still hold them after the id is freed.
	 */
	private void complete(String contextId, String contextURI) {
		for (Entry entry : repositoryManager.getSoftCache().removeByEntryURIPrefix(contextURI + "/")) {
			if (entry instanceof EntryImpl entryImpl) {
				entryImpl.markDeleted();
			}
			if (entry.getContext() instanceof ContextImpl context) {
				context.markDeleted();
			}
		}
		synchronized (repository) {
			try (RepositoryConnection rc = repository.getConnection()) {
				rc.remove(iri(contextURI), RepositoryProperties.removalPending, null, contextsIndexGraph);
			}
			pending.remove(contextId);
		}
	}

	/**
	 * Whether {@code EntryImpl} or {@code MetadataImpl} copies a statement of an entry or metadata graph into the
	 * relations graph of the entry its object belongs to.
	 */
	private static boolean isCopiedToRelations(Statement statement, boolean fromEntryGraph, String base) {
		if (fromEntryGraph) {
			return EntryImpl.isStatementInvRelationCandidate(statement, base);
		}
		return statement.getObject() instanceof IRI object && object.stringValue().startsWith(base)
				&& statement.getSubject().stringValue().startsWith(base);
	}

	/** The relations graph of the entry an IRI belongs to, if it belongs to an entry of this repository. */
	private Optional<IRI> relationsGraphOf(IRI iri) {
		try {
			return split(iri).filter(uriSplit -> uriSplit.getUriType() != URIType.Unknown)
					.map(uriSplit -> iri(uriSplit.getRelationURI().toString()));
		} catch (IllegalArgumentException e) {
			return Optional.empty();
		}
	}

	private boolean isRelationsGraph(IRI graph) {
		return split(graph).filter(uriSplit -> RepositoryProperties.RELATION.equals(uriSplit.getPath())).isPresent();
	}

	private Optional<URISplit> split(IRI iri) {
		try {
			return URISplit.of(URI.create(iri.stringValue()), repositoryManager.getRepositoryURL());
		} catch (IllegalArgumentException e) {
			return Optional.empty();
		}
	}

	/** Thrown between two chunks once {@link #shutdown()} has been called. */
	private static final class StoppedException extends Exception {
	}

	/** One run of the job over one context, counting its passes and the statements it removed. */
	private final class Removal {

		private final String contextId;
		private final String contextURI;
		private final String base;
		private int pass;
		private long removed;

		private Removal(String contextId) {
			this.contextId = contextId;
			this.base = repositoryManager.getRepositoryURL().toString();
			// concatenated as URISplit.createURI does, but without its parsing, which could throw outside the try
			this.contextURI = base + contextId;
		}

		/**
		 * Removes the context's graphs pass by pass, until a listing finds none. A listing cut off at
		 * {@link #graphsPerListing} continues the pass rather than starting a new one, unless it lists a graph the
		 * previous listing removed: graphs that survive their removal would otherwise fill every listing forever.
		 */
		private void removeGraphs() throws StoppedException {
			Set<IRI> previous = Set.of();
			for (List<IRI> graphs = listGraphs(contextURI); !graphs.isEmpty(); graphs = listGraphs(contextURI)) {
				boolean newPass = graphs.size() < graphsPerListing || graphs.stream().anyMatch(previous::contains);
				if (newPass && ++pass > MAX_PASSES) {
					throw new IllegalStateException(graphs.size() + " graphs remain after " + MAX_PASSES + " passes");
				}
				removeGraphs(graphs);
				previous = Set.copyOf(graphs);
			}
		}

		/**
		 * Removes the statements of the graphs chunk by chunk. A graph larger than the room left in a chunk is
		 * read again after the chunk is committed, which then returns the statements not yet removed.
		 */
		private void removeGraphs(List<IRI> graphs) throws StoppedException {
			List<Statement> chunk = new ArrayList<>();
			// a set: sources of one triple in several graphs share its copy, which is then checked once
			Set<Statement> copies = new LinkedHashSet<>();
			try (RepositoryConnection reader = repository.getConnection()) {
				startChunk();
				for (IRI graph : graphs) {
					String path = pathOf(graph);
					boolean fromEntryGraph = path.equals(RepositoryProperties.ENTRY_PATH);
					boolean relationSource = fromEntryGraph || path.equals(RepositoryProperties.MD_PATH)
							|| path.equals(RepositoryProperties.EXTERNAL_MD_PATH);
					List<Statement> removedOfGraph = List.of();
					while (true) {
						List<Statement> read = read(reader, graph, chunkSize - chunk.size());
						if (!read.isEmpty() && removedOfGraph.contains(read.getFirst())) {
							throw new IllegalStateException("Statements of graph " + graph + " were not removed");
						}
						chunk.addAll(read);
						if (relationSource) {
							copies.addAll(relationCopies(reader, read, fromEntryGraph));
						}
						if (chunk.size() < chunkSize) {
							break;
						}
						apply(chunk, copies);
						removedOfGraph = read;
						chunk.clear();
						copies.clear();
						startChunk();
					}
				}
			}
			if (!chunk.isEmpty()) {
				apply(chunk, copies);
			}
		}

		private void startChunk() throws StoppedException {
			if (stopping) {
				throw new StoppedException();
			}
			beforeChunk.run();
		}

		/** The path segment of one of the context's graphs, such as {@code metadata}, or empty for its index graph. */
		private String pathOf(IRI graph) {
			String rest = graph.stringValue().substring(contextURI.length());
			int end = rest.indexOf('/', 1);
			return rest.isEmpty() ? "" : rest.substring(1, end < 0 ? rest.length() : end);
		}

		/**
		 * Statements of the graph, each given that graph: a SPARQL store outside quad mode returns them without it,
		 * and a removal without a graph reaches every graph.
		 */
		private static List<Statement> read(RepositoryConnection reader, IRI graph, int limit) {
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

		/** The copies other entries' relations graphs hold of these statements, as found when reading them. */
		private List<Statement> relationCopies(RepositoryConnection reader, List<Statement> sources,
				boolean fromEntryGraph) {
			List<Statement> copies = new ArrayList<>();
			for (Statement source : sources) {
				if (!isCopiedToRelations(source, fromEntryGraph, base)
						|| !(source.getObject() instanceof IRI object) || isOfContext(object, contextURI)) {
					continue;
				}
				Optional<IRI> relationsGraph = relationsGraphOf(object);
				if (relationsGraph.isPresent() && reader.hasStatement(source.getSubject(), source.getPredicate(),
						object, false, relationsGraph.get())) {
					copies.add(reader.getValueFactory().createStatement(source.getSubject(), source.getPredicate(),
							object, relationsGraph.get()));
				}
			}
			return copies;
		}

		/**
		 * Removes the chunk in one transaction under the monitor. Whether a copy is still stated outside the context
		 * is checked there, since a surviving entry may have stated the same triple after the chunk was read.
		 */
		private void apply(List<Statement> statements, Collection<Statement> copies) {
			synchronized (repository) {
				try (RepositoryConnection rc = repository.getConnection()) {
					rc.begin();
					try {
						removeOwnStatements(rc, statements);
						TupleQuery graphsStating = rc.prepareTupleQuery(GRAPHS_STATING);
						for (Statement copy : copies) {
							if (!isStatedOutside(graphsStating, copy)) {
								rc.remove(copy);
							}
						}
						rc.commit();
					} catch (RuntimeException e) {
						try {
							rc.rollback();
						} catch (RuntimeException rollbackFailure) {
							e.addSuppressed(rollbackFailure);
						}
						throw e;
					}
				}
			}
			removed += statements.size();
			log.info("Removing context {}: {} statements removed (pass {})", contextId, removed, pass);
			afterChunk.run();
		}

		/**
		 * Removes statements of the context's own graphs. With {@link #blankNodesByPattern}, a blank node is matched
		 * by a wildcard, which can only widen the removal within the graph, and that graph is deleted anyway. Refuses
		 * a statement without a graph, whose removal would reach every graph.
		 */
		private void removeOwnStatements(RepositoryConnection rc, List<Statement> statements) {
			if (statements.stream().anyMatch(statement -> statement.getContext() == null)) {
				throw new IllegalStateException("Statements of context " + contextId + " lack their graph");
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
		 * Whether a graph outside the context, other than a relations graph, states the triple of a copy. Asked by
		 * graph, since a SPARQL store outside quad mode returns statements without theirs.
		 */
		private boolean isStatedOutside(TupleQuery graphsStating, Statement copy) {
			graphsStating.setBinding("s", copy.getSubject());
			graphsStating.setBinding("p", copy.getPredicate());
			graphsStating.setBinding("o", copy.getObject());
			try (TupleQueryResult holders = graphsStating.evaluate()) {
				for (BindingSet holder : holders) {
					if (holder.getValue("g") instanceof IRI graph && !isOfContext(graph, contextURI)
							&& !isRelationsGraph(graph)) {
						return true;
					}
				}
			}
			return false;
		}
	}
}
