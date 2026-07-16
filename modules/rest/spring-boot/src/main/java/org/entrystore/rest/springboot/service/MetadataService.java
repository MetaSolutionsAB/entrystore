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

import com.google.common.collect.HashMultimap;
import com.google.common.collect.ImmutableSet;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.eclipse.rdf4j.common.iteration.Iterations;
import org.eclipse.rdf4j.model.Model;
import org.eclipse.rdf4j.model.impl.LinkedHashModel;
import org.eclipse.rdf4j.query.GraphQuery;
import org.eclipse.rdf4j.query.MalformedQueryException;
import org.eclipse.rdf4j.query.QueryEvaluationException;
import org.eclipse.rdf4j.query.QueryLanguage;
import org.eclipse.rdf4j.repository.Repository;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.RepositoryException;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.eclipse.rdf4j.sail.memory.MemoryStore;
import org.entrystore.Entity;
import org.entrystore.Entry;
import org.entrystore.EntryType;
import org.entrystore.GraphEntity;
import org.entrystore.Metadata;
import org.entrystore.PrincipalManager;
import org.entrystore.Provenance;
import org.entrystore.ProvenanceType;
import org.entrystore.impl.RepositoryManagerImpl;
import org.entrystore.repository.util.EntryUtil;
import org.entrystore.repository.util.NS;
import org.entrystore.rest.springboot.configuration.TraversalProperties;
import org.entrystore.rest.springboot.model.api.MetadataType;
import org.entrystore.rest.springboot.model.dto.MetadataResult;
import org.entrystore.rest.springboot.model.exception.BadRequestException;
import org.entrystore.rest.springboot.model.exception.EntityNotFoundException;
import org.entrystore.rest.springboot.model.exception.InternalServerErrorException;
import org.entrystore.rest.springboot.model.exception.MethodNotAllowedException;
import org.entrystore.rest.springboot.util.GraphUtil;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class MetadataService {

	private final RepositoryManagerImpl repositoryManager;
	private final TraversalProperties traversalProperties;

	/**
	 * ENTRYSTORE-1087: the conditional-GET fast path answers If-None-Match before loading the
	 * graph, so it must enforce the same authorization the local-metadata load path does. The
	 * thrown core AuthorizationException is mapped by AppExceptionHandler (404 for anonymous,
	 * 403 for authenticated callers).
	 */
	public void checkReadMetadataAuthorization(Entry entry) {
		repositoryManager.getPrincipalManager()
				.checkAuthenticatedUserAuthorized(entry, PrincipalManager.AccessProperty.ReadMetadata);
	}

	public MetadataResult getMetadata(Entry entry, MetadataType metadataType, String format, String graphQuery, Integer depth, String recursive, String scope, String revision) {

		if (recursive != null) {
			Set<URI> predicatesToFollow = resolvePredicates(recursive);
			if (predicatesToFollow.isEmpty()) {
				throw new BadRequestException("Unable to find predicates for parameter 'recursive' value: " + recursive);
			}
			return getRecursiveMetadata(entry, format, graphQuery, depth, recursive, scope, predicatesToFollow);
		}

		Model metadataGraph = switch (metadataType) {

			case LOCAL_METADATA -> {
				Metadata metadata = getEntryLocalMetadata(entry, revision);
				if (metadata == null) {
					throw new EntityNotFoundException("Local Metadata not found");
				}
				yield metadata.getGraph();
			}

			case CACHED_EXTERNAL_METADATA -> {
				Metadata metadata = getEntryCachedExternalMetadata(entry);
				if (metadata == null) {
					throw new EntityNotFoundException("External Metadata not found");
				}
				yield metadata.getGraph();
			}

			case MERGED_METADATA -> entry.getMetadataGraph();

		};

		if (metadataGraph == null) {
			throw new EntityNotFoundException("Metadata graph not found");
		}

		Model graphToSerialize = graphQuery != null ? applyGraphQuery(graphQuery, metadataGraph) : metadataGraph;
		return new MetadataResult(GraphUtil.serializeGraph(graphToSerialize, format), null);
	}

	/**
	 * Sets the metadata of entry to graphString.
	 *
	 * @param entry            Entry
	 * @param metadataType     Metadata type
	 * @param newMetadataGraph New metadata to be set
	 * @param revision         Revision
	 */
	public void setEntryMetadata(Entry entry, MetadataType metadataType, Model newMetadataGraph, String revision) {

		Metadata metadata = switch (metadataType) {
			case LOCAL_METADATA -> getEntryLocalMetadata(entry, revision);
			case CACHED_EXTERNAL_METADATA -> getEntryCachedExternalMetadata(entry);
			case MERGED_METADATA ->
					throw new BadRequestException("Unable to set Merged Metadata on entry: " + entry.getEntryURI());
		};

		if (metadata != null && newMetadataGraph != null) {
			metadata.setGraph(newMetadataGraph);
			return;
		}
		throw new MethodNotAllowedException("Metadata is empty for entry: " + entry.getEntryURI());
	}

	private Metadata getEntryLocalMetadata(Entry entry, String revision) {

		EntryType et = entry.getEntryType();
		if (EntryType.Local.equals(et) || EntryType.Link.equals(et) || EntryType.LinkReference.equals(et)) {
			Provenance provenance = entry.getProvenance();
			if (revision != null && provenance != null) {
				Entity entity = provenance.getEntityFor(revision, ProvenanceType.Metadata);
				if (entity instanceof GraphEntity graphEntity) {
					return graphEntity;
				}
				return null;
			}
			return entry.getLocalMetadata();
		}
		return null;
	}

	private Metadata getEntryCachedExternalMetadata(Entry entry) {
		EntryType et = entry.getEntryType();
		if (EntryType.Reference.equals(et) || EntryType.LinkReference.equals(et)) {
			return entry.getCachedExternalMetadata();
		}
		return null;
	}

	private MetadataResult getRecursiveMetadata(Entry entry, String format, String graphQuery, Integer depth, String recursive,
										String scope, Set<URI> predicatesToFollow) {

		String firstDetectedProfile = getFirstProfile(recursive);

		Map<String, String> blacklist = loadBlacklist(recursive);

		int depthMax = depth;
		int limit = 1000;
		boolean repositoryScope = true;
		if (firstDetectedProfile != null) {
			depthMax = traversalProperties.maxDepth(firstDetectedProfile).orElse(depthMax);
			limit = traversalProperties.limit(firstDetectedProfile).orElse(limit);
			repositoryScope = traversalProperties.repositoryScope(firstDetectedProfile).orElse(repositoryScope);
		}

		if (depth > depthMax) {
			depth = depthMax;
		} else if (depth < 0) {
			depth = 10;
		}

		if (StringUtils.isNotEmpty(scope)) {
			// we allow an override by parameter
			repositoryScope = !"context".equalsIgnoreCase(scope);
		}

		EntryUtil.TraversalResult travResult = traverse(entry, predicatesToFollow, blacklist, repositoryScope, depth, limit);
		Date latestModified = travResult.getLatestModified();
		Model graphToSerialize = graphQuery != null
				? applyGraphQuery(graphQuery, travResult.getGraph())
				: travResult.getGraph();
		return new MetadataResult(GraphUtil.serializeGraph(graphToSerialize, format), latestModified);
	}

	private String getFirstProfile(String predCSV) {
		for (String s : predCSV.split(",")) {
			if (!loadTraversalProfile(s).isEmpty()) {
				return s;
			}
		}
		return null;
	}

	/**
	 * Performs a traversal of the metadata graphs of the entries to
	 * which the first entry links.
	 * A maximum (default) of 10 levels is traversed. Levels can be set with depth parameter.
	 *
	 * @param entry        Starting point for traversal.
	 * @param predToFollow Which predicates should be followed for
	 *                     fetching entries further down the traversal path.
	 * @param blacklist    blacklist A map containing key/value pairs of predicate/object combinations that,
	 *                     if contained in the graph of the currently processed entry,
	 *                     trigger a stop of the traversal excluding the matching entry.
	 * @param repository   Ignore context boundaries.
	 * @param depth        Levels traversed.
	 * @param limit        Max number of results.
	 * @return Returns a Graph consisting of merged metadata graphs. Contains all metadata, including e.g. cached external.
	 */
	private EntryUtil.TraversalResult traverse(Entry entry, Set<URI> predToFollow, Map<String, String> blacklist, boolean repository, int depth, int limit) {
		return EntryUtil.traverseAndLoadEntryMetadata(
				ImmutableSet.of(repositoryManager.getValueFactory().createIRI(entry.getEntryURI().toString())),
				predToFollow,
				blacklist,
				0,
				depth,
				limit,
				HashMultimap.create(),
				repository ? null : entry.getContext(),
				repositoryManager
		);
	}

	private Model applyGraphQuery(String query, Model graph) {
		Date before = new Date();
		MemoryStore ms = new MemoryStore();
		Repository sr = new SailRepository(ms);
		RepositoryConnection rc = null;
		try {
			sr.init();
			rc = sr.getConnection();
			rc.add(graph);
			GraphQuery gq = rc.prepareGraphQuery(QueryLanguage.SPARQL, query);
			gq.setMaxExecutionTime(10); // 10 seconds, TODO: make this configurable
			Model result = Iterations.addAll(gq.evaluate(), new LinkedHashModel());
			log.info("Graph query took {} ms", new Date().getTime() - before.getTime());
			return result;
		} catch (RepositoryException | QueryEvaluationException e) {
			throw new InternalServerErrorException("Graph query evaluation failed", e);
		} catch (MalformedQueryException mfqe) {
			log.warn("Malformed SPARQL graph query: {}", mfqe.getMessage(), mfqe);
			throw new BadRequestException("Malformed SPARQL query");
		} finally {
			if (rc != null) {
				try {
					rc.close();
				} catch (RepositoryException e) {
					log.error("Failed to close repository connection: {}", e.getMessage(), e);
				}
			}
			try {
				sr.shutDown();
			} catch (RepositoryException e) {
				log.error("Failed to shut down repository: {}", e.getMessage(), e);
			}
		}
	}

	/**
	 * Builds a set of predicate URIs.
	 *
	 * @param predCSV A comma-separated list of predicates and/or
	 *                traversal profiles (need to be defined in configuration).
	 * @return Returns a set of URIs. Traversal profile names are resolved
	 * in their member URIs, and namespaces' URIs are expanded.
	 */
	private Set<URI> resolvePredicates(String predCSV) {
		Set<URI> result = new HashSet<>();
		for (String s : predCSV.split(",")) {
			Set<URI> pSet = loadTraversalProfile(s);
			if (pSet.isEmpty()) {
				try {
					URI expanded = NS.expand(s);
					// we add it to the result if it could be expanded
					if (expanded != null && !s.equals(expanded.toString())) {
						result.add(expanded);
					}
				} catch (IllegalArgumentException iae) {
					log.warn("Unable to expand namespace: {}", iae.getMessage());
				}
			} else {
				result.addAll(pSet);
			}
		}
		return result;
	}

	/**
	 * Loads a traversal profile from configuration.
	 *
	 * @param profileName The name of the traversal profile.
	 * @return A set of URIs.
	 */
	private Set<URI> loadTraversalProfile(String profileName) {
		Set<URI> result = new HashSet<>();
		for (String s : traversalProperties.predicates(profileName)) {
			result.add(URI.create(s));
		}
		return result;
	}

	private Map<String, String> loadBlacklist(String traversalParam) {
		Map<String, String> result = new HashMap<>();
		for (String s : traversalParam.split(",")) {
			result.putAll(loadTraversalBlacklistForProfile(s));
		}
		return result;
	}

	/**
	 * Loads a blacklist for a traversal profile from configuration.
	 *
	 * @param profileName The name of the traversal profile.
	 * @return A map containing the tuples of the blacklist.
	 */
	private Map<String, String> loadTraversalBlacklistForProfile(String profileName) {
		Map<String, String> result = new HashMap<>();
		for (String tuple : traversalProperties.blacklistTuples(profileName)) {
			String[] tupleArr = tuple.split(",");
			if (tupleArr.length != 2) {
				log.warn("Invalid blacklist configuration in traversal profile {}: {}", profileName, tuple);
				continue;
			}
			result.put(NS.expand(tupleArr[0]).toString(), NS.expand(tupleArr[1]).toString());
		}
		return result;
	}

}
