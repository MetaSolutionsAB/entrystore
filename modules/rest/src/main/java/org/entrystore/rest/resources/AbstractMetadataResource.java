/*
 * Copyright (c) 2007-2017 MetaSolutions AB
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

package org.entrystore.rest.resources;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.ImmutableSet;
import org.eclipse.rdf4j.common.iteration.Iterations;
import org.eclipse.rdf4j.model.Model;
import org.eclipse.rdf4j.model.impl.LinkedHashModel;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.query.GraphQuery;
import org.eclipse.rdf4j.query.MalformedQueryException;
import org.eclipse.rdf4j.query.QueryEvaluationException;
import org.eclipse.rdf4j.query.QueryLanguage;
import org.eclipse.rdf4j.repository.Repository;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.RepositoryException;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.eclipse.rdf4j.rio.RDFFormat;
import org.eclipse.rdf4j.sail.memory.MemoryStore;
import org.entrystore.AuthorizationException;
import org.entrystore.Metadata;
import org.entrystore.repository.config.Settings;
import org.entrystore.repository.util.EntryUtil;
import org.entrystore.repository.util.NS;
import org.entrystore.rest.util.GraphUtil;
import org.entrystore.rest.util.JSONErrorMessages;
import org.entrystore.rest.util.Util;
import org.restlet.data.Disposition;
import org.restlet.data.MediaType;
import org.restlet.data.Method;
import org.restlet.data.Status;
import org.restlet.ext.json.JsonRepresentation;
import org.restlet.representation.EmptyRepresentation;
import org.restlet.representation.Representation;
import org.restlet.representation.StringRepresentation;
import org.restlet.resource.Delete;
import org.restlet.resource.Get;
import org.restlet.resource.Put;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;


/**
 * Provides methods to read/write metadata graphs.
 * Subclasses need to implement getMetadata().
 *
 * @author Hannes Ebner
 */
public abstract class AbstractMetadataResource extends BaseResource {

	static Logger log = LoggerFactory.getLogger(AbstractMetadataResource.class);

	List<MediaType> supportedMediaTypes = new ArrayList<>();

	@Override
	public void doInit() {
		supportedMediaTypes.add(MediaType.APPLICATION_RDF_XML);
		supportedMediaTypes.add(MediaType.APPLICATION_JSON);
		supportedMediaTypes.add(MediaType.TEXT_RDF_N3);
		supportedMediaTypes.add(new MediaType(RDFFormat.TURTLE.getDefaultMIMEType()));
		supportedMediaTypes.add(new MediaType(RDFFormat.TRIX.getDefaultMIMEType()));
		supportedMediaTypes.add(new MediaType(RDFFormat.NTRIPLES.getDefaultMIMEType()));
		supportedMediaTypes.add(new MediaType(RDFFormat.TRIG.getDefaultMIMEType()));
		supportedMediaTypes.add(new MediaType(RDFFormat.JSONLD.getDefaultMIMEType()));
		supportedMediaTypes.add(new MediaType("application/rdf+json"));
	}

	/**
	 * <pre>
	 * GET {baseURI}/{context-id}/{metadata}/{entry-id}
	 * </pre>
	 *
	 * @return the metadata representation
	 */
	@Get
	public Representation represent() {
		try {
			if (entry == null) {
				getResponse().setStatus(Status.CLIENT_ERROR_NOT_FOUND);
				return new JsonRepresentation(JSONErrorMessages.errorEntryNotFound);
			}

			Representation result;
			if (Method.GET.equals(getRequest().getMethod())) {
				MediaType preferredMediaType = getRequest().getClientInfo().getPreferredMediaType(supportedMediaTypes);
				if (preferredMediaType == null) {
					preferredMediaType = MediaType.APPLICATION_RDF_XML;
				}
				MediaType prefFormat = (format != null) ? format : preferredMediaType;

				String graphQuery = null;
				if (parameters.containsKey("graphQuery")) {
					graphQuery = parameters.get("graphQuery");
				}

				if (parameters.containsKey("recursive")) {
					String traversalParam = parameters.get("recursive");
					Set<URI> predicatesToFollow = resolvePredicates(traversalParam);
					if (predicatesToFollow.isEmpty()) {
						getResponse().setStatus(Status.CLIENT_ERROR_BAD_REQUEST);
						return null;
					}

					String firstDetectedProfile = getFirstProfile(traversalParam);

					Map<String, String> blacklist = loadBlacklist(traversalParam);

					int depth = 10; // default
					int depthMax = firstDetectedProfile != null ? getRM().getConfiguration().getInt(traversalSetting(Settings.TRAVERSAL_PROFILE_MAX_DEPTH, firstDetectedProfile), depth) : depth;
					if (depthMax < depth) {
						depth = depthMax;
					}

					try {
						if (parameters.containsKey("depth")) {
							int depthParam = Integer.parseInt(parameters.get("depth"));
							if (depthParam > 0 && depthParam <= depth) { // cannot be higher then config maxDepth
								depth = depthParam;
							}
						}
					} catch (NumberFormatException e) {
						getResponse().setStatus(Status.CLIENT_ERROR_BAD_REQUEST);
						return new StringRepresentation("Error parsing depth parameter: " + e.getMessage());
					}

					int limit = 1000; // default
					limit = firstDetectedProfile != null ? getRM().getConfiguration().getInt(traversalSetting(Settings.TRAVERSAL_PROFILE_LIMIT, firstDetectedProfile), limit) : limit;
					boolean repositoryScope = true; // default
					repositoryScope = firstDetectedProfile != null ? getRM().getConfiguration().getBoolean(traversalSetting(Settings.TRAVERSAL_PROFILE_REPOSITORY_SCOPE, firstDetectedProfile), repositoryScope) : repositoryScope;
					if (parameters.containsKey("scope")) {
						// we allow an override by parameter
						repositoryScope = !"context".equalsIgnoreCase(parameters.get("scope"));
					}

					EntryUtil.TraversalResult travResult = traverse(entry.getEntryURI(), predicatesToFollow, blacklist, repositoryScope, depth, limit);
					if (graphQuery != null) {
						Model graphQueryResult = applyGraphQuery(graphQuery, travResult.getGraph());
						if (graphQueryResult != null) {
							result = getRepresentation(graphQueryResult, prefFormat);
						} else {
							getResponse().setStatus(Status.CLIENT_ERROR_BAD_REQUEST);
							return null;
						}
					} else {
						result = getRepresentation(travResult.getGraph(), prefFormat);
					}
					if (travResult.getLatestModified() != null) {
						result.setModificationDate(travResult.getLatestModified());
					}
				} else {
					// MergedMetadataResource does not implement getMetadata()
					if (getMetadata() == null && getMetadataGraph() == null) {
						getResponse().setStatus(Status.CLIENT_ERROR_NOT_FOUND);
						return null;
					}

					if (graphQuery != null) {
						Model graphQueryResult = applyGraphQuery(graphQuery, getMetadataGraph());
						if (graphQueryResult != null) {
							result = getRepresentation(graphQueryResult, prefFormat);
						} else {
							getResponse().setStatus(Status.CLIENT_ERROR_BAD_REQUEST);
							return null;
						}
					} else {
						result = getRepresentation(getMetadataGraph(), prefFormat);
					}
				}

				// set file name
				String fileName = entry.getFilename();
				if (fileName == null) {
					fileName = entry.getId();
				}
				fileName += "." + getFileExtensionForMediaType(prefFormat);

				// offer download in case the client requested this
				Disposition disp = new Disposition();
				disp.setFilename(fileName);
				if (parameters.containsKey("download")) {
					disp.setType(Disposition.TYPE_ATTACHMENT);
				} else {
					disp.setType(Disposition.TYPE_INLINE);
				}
				result.setDisposition(disp);
			} else {
				// for HEAD requests
				result = new EmptyRepresentation();
			}

			// set a modification date only in case it has not been
			// set before (e.g., when handling recursive-requests)
			Date lastMod = getModificationDate();
			if (lastMod != null && result.getModificationDate() == null) {
				result.setModificationDate(lastMod);
				result.setTag(Util.createTag(lastMod));
			}

			return result;
		} catch (AuthorizationException e) {
			return unauthorizedGET();
		}
	}

	@Put
	public void storeRepresentation(Representation r) {
		try {
			if (entry == null) {
				getResponse().setStatus(Status.CLIENT_ERROR_NOT_FOUND);
				getResponse().setEntity(new JsonRepresentation(JSONErrorMessages.errorEntryNotFound));
				return;
			}

			if (getMetadata() == null) {
				getResponse().setStatus(Status.CLIENT_ERROR_METHOD_NOT_ALLOWED);
				return;
			}

			MediaType mt = (format != null) ? format : getRequestEntity().getMediaType();
			copyRepresentationToMetadata(r, getMetadata(), mt);
			getResponse().setEntity(createEmptyRepresentationWithLastModified(getModificationDate()));
		} catch (AuthorizationException e) {
			unauthorizedPUT();
		}
	}

	@Delete
	public void removeRepresentations() {
		try {
			if (entry == null) {
				getResponse().setStatus(Status.CLIENT_ERROR_NOT_FOUND);
				getResponse().setEntity(new JsonRepresentation(JSONErrorMessages.errorEntryNotFound));
				return;
			}

			if (getMetadata() == null) {
				getResponse().setStatus(Status.CLIENT_ERROR_METHOD_NOT_ALLOWED);
				return;
			}

			getMetadata().setGraph(new LinkedHashModel());
		} catch (AuthorizationException e) {
			unauthorizedDELETE();
		}
	}

	/**
	 * @return Metadata in the requested format.
	 */
	private Representation getRepresentation(Model graph, MediaType mediaType) throws AuthorizationException {
		if (graph != null) {
			String serializedGraph = GraphUtil.serializeGraph(graph, mediaType);
			if (serializedGraph != null) {
				getResponse().setStatus(Status.SUCCESS_OK);
				return new StringRepresentation(serializedGraph, mediaType);
			}
		}

		getResponse().setStatus(Status.CLIENT_ERROR_BAD_REQUEST);
		return new EmptyRepresentation();
	}

	/**
	 * Sets the metadata, uses getMetadata() as input.
	 *
	 * @param metadata the Metadata object of which the content should be replaced.
	 */
	private void copyRepresentationToMetadata(Representation representation, Metadata metadata, MediaType mediaType) throws AuthorizationException {
		String graphString = null;
		try {
			graphString = representation.getText();
		} catch (IOException e) {
			log.error(e.getMessage());
		}

		if (metadata != null && graphString != null) {
			Model deserializedGraph = GraphUtil.deserializeGraph(graphString, mediaType);
			if (deserializedGraph != null) {
				getResponse().setStatus(Status.SUCCESS_OK);
				metadata.setGraph(deserializedGraph);
				return;
			}
		}

		getResponse().setStatus(Status.CLIENT_ERROR_BAD_REQUEST);
	}

	/**
	 * @return Returns the metadata object. Can be null.
	 */
	protected abstract Metadata getMetadata();

	/**
	 * @return Returns the metadata graph. Can be null.
	 */
	protected Model getMetadataGraph() {
		if (getMetadata() != null) {
			return getMetadata().getGraph();
		}
		return null;
	}

	/**
	 * @return Returns the modification date of the metadata graph. Only external metadata has its own date;
	 * local metadata has the same modification date as the entry itself
	 */
	protected abstract Date getModificationDate();

	/**
	 * Performs a traversal of the metadata graphs of the entries to
	 * which the first entry links.
	 * A maximum (default) of 10 levels is traversed. Levels can be set with depth parameter.
	 *
	 * @param entryURI     Starting point for traversal.
	 * @param predToFollow Which predicates should be followed for
	 *                     fetching entries further down the traversal path.
	 * @param blacklist    blacklist A map containing key/value pairs of predicate/object combinations that,
	 *                     if contained in the graph of the currently processed entry,
	 *                     trigger a stop of the traversal excluding the matching entry.
	 * @param repository   Ignore context boundaries.
	 * @param depth        Levels traversed.
	 * @return Returns a Graph consisting of merged metadata graphs. Contains all metadata, including e.g. cached external.
	 */
	private EntryUtil.TraversalResult traverse(URI entryURI, Set<URI> predToFollow, Map<String, String> blacklist, boolean repository, int depth, int limit) {
		return EntryUtil.traverseAndLoadEntryMetadata(
			ImmutableSet.of(getRM().getValueFactory().createIRI(entryURI.toString())),
			predToFollow,
			blacklist,
			0,
			depth,
			limit,
			HashMultimap.create(),
			repository ? null : context,
			getRM()
		);
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
					if (!s.equals(expanded.toString())) {
						result.add(URI.create(NS.expand(s).toString()));
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

	private String getFirstProfile(String predCSV) {
		for (String s : predCSV.split(",")) {
			if (!loadTraversalProfile(s).isEmpty()) {
				return s;
			}
		}
		return null;
	}

	private Map<String, String> loadBlacklist(String traversalParam) {
		Map<String, String> result = new HashMap<>();
		for (String s : traversalParam.split(",")) {
			result.putAll(loadTraversalBlacklistForProfile(s));
		}
		return result;
	}

	/**
	 * Loads a traversal profile from configuration.
	 * @param profileName The name of the traversal profile.
	 * @return A set of URIs.
	 */
	private Set<URI> loadTraversalProfile(String profileName) {
		List<String> predicates = getRM().getConfiguration().getStringList(traversalSetting(Settings.TRAVERSAL_PROFILE, profileName), new ArrayList<>());
		Set<URI> result = new HashSet<>();
		for (String s : predicates) {
			result.add(URI.create(s));
		}
		return result;
	}

	/**
	 * Loads a blacklist for a traversal profile from configuration.
	 * @param profileName The name of the traversal profile.
	 * @return A map containing the tuples of the blacklist.
	 */
	private Map<String, String> loadTraversalBlacklistForProfile(String profileName) {
		List<String> blacklist = getRM().getConfiguration().getStringList(traversalSetting(Settings.TRAVERSAL_PROFILE_BLACKLIST, profileName), new ArrayList<>());
		Map<String, String> result = new HashMap<>();
		for (String tuple : blacklist) {
			String[] tupleArr = tuple.split(",");
			if (tupleArr.length != 2) {
				log.warn("Invalid blacklist configuration in traversal profile " + profileName + ": " + tuple);
				continue;
			}
			result.put(NS.expand(tupleArr[0]).toString(), NS.expand(tupleArr[1]).toString());
		}
		return result;
	}

	private Model applyGraphQuery(String query, Model graph) {
		Date before = new Date();
		MemoryStore ms = new MemoryStore();
		Repository sr = new SailRepository(ms);
		Model result = null;
		RepositoryConnection rc = null;
		try {
			sr.init();
			rc = sr.getConnection();
			rc.add(graph);
			GraphQuery gq = rc.prepareGraphQuery(QueryLanguage.SPARQL, query);
			gq.setMaxExecutionTime(10); // 10 seconds, TODO: make this configurable
			result = Iterations.addAll(gq.evaluate(), new LinkedHashModel());
			log.info("Graph query took " + (new Date().getTime() - before.getTime()) + " ms");
		} catch (RepositoryException | QueryEvaluationException e) {
			log.error(e.getMessage());
		} catch (MalformedQueryException mfqe) {
			log.debug(mfqe.getMessage());
		} finally {
			if (rc != null) {
				try {
					rc.close();
				} catch (RepositoryException e) {
					log.error(e.getMessage());
				}
			}
			try {
				sr.shutDown();
			} catch (RepositoryException e) {
				log.error(e.getMessage());
			}
		}
		return result;
	}

	private static final RDFFormat RDFJSON_WITH_APPLICATION_JSON
		= new RDFFormat("RDF/JSON", List.of("application/json"), StandardCharsets.UTF_8, List.of("json"), SimpleValueFactory.getInstance().createIRI("http://www.w3.org/ns/formats/RDF_JSON"), false, true, false);

	protected static String getFileExtensionForMediaType(MediaType mt) {
		Optional<RDFFormat> rdfFormat = RDFFormat.matchMIMEType(mt.getName(), Arrays.asList(
				RDFFormat.RDFXML,
				RDFFormat.NTRIPLES,
				RDFFormat.TURTLE,
				RDFFormat.N3,
				RDFFormat.TRIX,
				RDFFormat.TRIG,
				RDFFormat.BINARY,
				RDFFormat.NQUADS,
				RDFFormat.JSONLD,
				RDFFormat.RDFJSON,
				RDFFormat.RDFA,
				RDFJSON_WITH_APPLICATION_JSON)
		);
		if (rdfFormat.isPresent() && rdfFormat.get().getDefaultFileExtension() != null) {
			return rdfFormat.get().getDefaultFileExtension();
		}
		return "rdf";
	}

	private String traversalSetting(String configKey, String profile) {
		return String.format(configKey, profile);
	}

}
