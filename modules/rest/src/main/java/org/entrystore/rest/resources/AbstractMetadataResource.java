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
import info.aduna.iteration.Iterations;
import org.entrystore.AuthorizationException;
import org.entrystore.Metadata;
import org.entrystore.impl.converters.ConverterUtil;
import org.entrystore.repository.config.Settings;
import org.entrystore.repository.util.EntryUtil;
import org.entrystore.repository.util.NS;
import org.entrystore.rest.util.GraphUtil;
import org.entrystore.rest.util.JSONErrorMessages;
import org.openrdf.model.Graph;
import org.openrdf.model.Model;
import org.openrdf.model.URI;
import org.openrdf.model.impl.LinkedHashModel;
import org.openrdf.model.impl.URIImpl;
import org.openrdf.query.GraphQuery;
import org.openrdf.query.MalformedQueryException;
import org.openrdf.query.QueryEvaluationException;
import org.openrdf.query.QueryLanguage;
import org.openrdf.repository.Repository;
import org.openrdf.repository.RepositoryConnection;
import org.openrdf.repository.RepositoryException;
import org.openrdf.repository.sail.SailRepository;
import org.openrdf.rio.RDFFormat;
import org.openrdf.sail.memory.MemoryStore;
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
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;


/**
 * Provides methods to read/write metadata graphs.
 *
 * Subclasses need to implement getMetadata().
 * 
 * @author Hannes Ebner
 */
public abstract class AbstractMetadataResource extends BaseResource {

	static Logger log = LoggerFactory.getLogger(AbstractMetadataResource.class);

	List<MediaType> supportedMediaTypes = new ArrayList<MediaType>();
	
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
		supportedMediaTypes.add(new MediaType("application/lom+xml"));
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

			Representation result = null;
			if (Method.GET.equals(getRequest().getMethod())) {
				MediaType preferredMediaType = getRequest().getClientInfo().getPreferredMediaType(supportedMediaTypes);
				if (preferredMediaType == null) {
					preferredMediaType = MediaType.APPLICATION_RDF_XML;
				}
				MediaType prefFormat = (format != null) ? format : preferredMediaType;

				String graphQuery = null;
				if (parameters.containsKey("graphQuery")) {
					try {
						graphQuery = URLDecoder.decode(parameters.get("graphQuery"), "UTF-8");
					} catch (UnsupportedEncodingException e) {
						log.error(e.getMessage());
					}
				}

				if (parameters.containsKey("recursive")) {
					String traversalParam = null;
					try {
						traversalParam = URLDecoder.decode(parameters.get("recursive"), "UTF-8");
					} catch (UnsupportedEncodingException e) {
						log.error(e.getMessage());
					}
					Set<java.net.URI> predicatesToFollow = resolvePredicates(traversalParam);
					if (predicatesToFollow.isEmpty()) {
						getResponse().setStatus(Status.CLIENT_ERROR_BAD_REQUEST);
						return null;
					}
					Map<String, String> blacklist = loadBlacklist(traversalParam);
					EntryUtil.TraversalResult travResult = traverse(entry.getEntryURI(), predicatesToFollow, blacklist, parameters.containsKey("repository"));
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
					if (getMetadata() == null) {
						getResponse().setStatus(Status.CLIENT_ERROR_NOT_FOUND);
						return null;
					}

					if (graphQuery != null) {
						Model graphQueryResult = applyGraphQuery(graphQuery, getMetadata().getGraph());
						if (graphQueryResult != null) {
							result = getRepresentation(graphQueryResult, prefFormat);
						} else {
							getResponse().setStatus(Status.CLIENT_ERROR_BAD_REQUEST);
							return null;
						}
					} else {
						result = getRepresentation(getMetadata().getGraph(), prefFormat);
					}
				}

				// set file name
				String fileName = entry.getFilename();
				if (fileName == null) {
					fileName = entry.getId();
				}
				fileName += ".rdf";

				// offer download in case client requested this
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

			// set modification date only in case it has not been
			// set before (e.g. when handling recursive-requests)
			Date lastMod = getModificationDate();
			if (lastMod != null && result.getModificationDate() == null) {
				result.setModificationDate(lastMod);
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
				getResponse().setStatus(Status.CLIENT_ERROR_NOT_FOUND);
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
				getResponse().setStatus(Status.CLIENT_ERROR_NOT_FOUND);
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
	private Representation getRepresentation(Graph graph, MediaType mediaType) throws AuthorizationException {
		if (graph != null) {
			String serializedGraph = null;
			if (mediaType.getName().equals("application/lom+xml")) {
				serializedGraph = ConverterUtil.convertGraphToLOM(graph, graph.getValueFactory().createURI(entry.getResourceURI().toString()));
			} else {
				serializedGraph = GraphUtil.serializeGraph(graph, mediaType);
			}

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
			Graph deserializedGraph = null;
			if (mediaType.getName().equals("application/lom+xml")) {
				deserializedGraph = ConverterUtil.convertLOMtoGraph(graphString, entry.getResourceURI());
			} else {
				deserializedGraph = GraphUtil.deserializeGraph(graphString, mediaType);
			}

			if (deserializedGraph != null) {
				getResponse().setStatus(Status.SUCCESS_OK);
				metadata.setGraph(deserializedGraph);
				return;
			}
		}

		getResponse().setStatus(Status.CLIENT_ERROR_BAD_REQUEST);
	}

	/**
	 * @return Returns the relevant metadata graph. May be null.
	 */
	protected abstract Metadata getMetadata();

	/**
	 * @return Returns the modification date of the metadata graph. Only external metadata has its own date;
	 * local metadata has the same modification date as the entry itself
	 */
	protected abstract Date getModificationDate();

	/**
	 * Performs a traversal of the metadata graphs of the entries to
	 * which the first entry links.
	 *
	 * A maximum of 10 levels is traversed. If other settings are needed
	 * the method loadEntryMetadata should be called instead.
	 *
	 * @param entryURI Starting point for traversal.
	 * @param predToFollow Which predicates should be followed for
	 *                     fetching entries further down the traversal path.
	 * @return Returns a Graph consisting of merged metadata graphs. Contains
	 * 			all metadata, including e.g. cached external.
	 */
	private EntryUtil.TraversalResult traverse(java.net.URI entryURI, Set<java.net.URI> predToFollow, Map<String, String> blacklist, boolean repository) {
		return EntryUtil.traverseAndLoadEntryMetadata(
				ImmutableSet.of((URI) new URIImpl(entryURI.toString())),
				predToFollow,
				blacklist,
				0,
				10,
				HashMultimap.<URI, Integer>create(),
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
	 * in their member URIs and namespaces URIs are expanded.
	 */
	private Set<java.net.URI> resolvePredicates(String predCSV) {
		Set<java.net.URI> result = new HashSet<>();
		for (String s : predCSV.split(",")) {
			Set<java.net.URI> pSet = loadTraversalProfile(s);
			if (pSet.isEmpty()) {
				java.net.URI expanded = NS.expand(s);
				// we add it to the result if it could be expanded
				if (!s.equals(expanded.toString())) {
					result.add(java.net.URI.create(NS.expand(s).toString()));
				}
			} else {
				result.addAll(pSet);
			}
		}
		return result;
	}

	private Map<String, String> loadBlacklist(String traversalParam) {
		Map<String, String> result = new HashMap();
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
	private Set<java.net.URI> loadTraversalProfile(String profileName) {
		List<String> predicates = getRM().getConfiguration().getStringList(Settings.TRAVERSAL_PROFILE + "." + profileName, new ArrayList<String>());
		Set<java.net.URI> result = new HashSet<>();
		for (String s : predicates) {
			result.add(java.net.URI.create(s));
		}
		return result;
	}

	/**
	 * Loads a blacklist for a traversal profile from configuration.
	 * @param profileName The name of the traversal profile.
	 * @return A map containing the tuples of the blacklist.
	 */
	private Map<String, String> loadTraversalBlacklistForProfile(String profileName) {
		List<String> blacklist = getRM().getConfiguration().getStringList(Settings.TRAVERSAL_PROFILE + "." + profileName + ".blacklist", new ArrayList<>());
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

	private Model applyGraphQuery(String query, Graph graph) {
		Date before = new Date();
		MemoryStore ms = new MemoryStore();
		Repository sr = new SailRepository(ms);
		Model result = null;
		RepositoryConnection rc = null;
		try {
			sr.initialize();
			rc = sr.getConnection();
			rc.add(graph);
			GraphQuery gq = rc.prepareGraphQuery(QueryLanguage.SPARQL, query);
			gq.setMaxQueryTime(10); // 10 seconds, TODO: make this configurable
			result = Iterations.addAll(gq.evaluate(), new LinkedHashModel());
			log.info("Graph query took " + (new Date().getTime() - before.getTime()) + " ms");
		} catch (RepositoryException e) {
			log.error(e.getMessage());
		} catch (MalformedQueryException mfqe) {
			log.debug(mfqe.getMessage());
		} catch (QueryEvaluationException qee) {
			log.error(qee.getMessage());
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

}