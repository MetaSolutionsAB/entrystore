/**
 * Copyright (c) 2007-2010
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

import java.io.IOException;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import com.github.jsonldjava.impl.SesameJSONLDParser;
import com.github.jsonldjava.impl.SesameJSONLDWriter;
import org.entrystore.repository.EntryType;
import org.entrystore.repository.Metadata;
import org.entrystore.repository.impl.converters.ConverterUtil;
import org.entrystore.repository.security.AuthorizationException;
import org.entrystore.rest.util.JSONErrorMessages;
import org.entrystore.rest.util.RDFJSON;
import org.entrystore.rest.util.Util;
import org.openrdf.model.Graph;
import org.openrdf.model.impl.GraphImpl;
import org.openrdf.rio.RDFFormat;
import org.openrdf.rio.n3.N3ParserFactory;
import org.openrdf.rio.n3.N3Writer;
import org.openrdf.rio.ntriples.NTriplesParser;
import org.openrdf.rio.ntriples.NTriplesWriter;
import org.openrdf.rio.rdfxml.RDFXMLParser;
import org.openrdf.rio.rdfxml.util.RDFXMLPrettyWriter;
import org.openrdf.rio.trig.TriGParser;
import org.openrdf.rio.trig.TriGWriter;
import org.openrdf.rio.trix.TriXParser;
import org.openrdf.rio.trix.TriXWriter;
import org.openrdf.rio.turtle.TurtleParser;
import org.openrdf.rio.turtle.TurtleWriter;
import org.restlet.data.MediaType;
import org.restlet.data.Status;
import org.restlet.ext.json.JsonRepresentation;
import org.restlet.representation.EmptyRepresentation;
import org.restlet.representation.Representation;
import org.restlet.representation.StringRepresentation;
import org.restlet.resource.Delete;
import org.restlet.resource.Get;
import org.restlet.resource.Post;
import org.restlet.resource.Put;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * Provides access to the metadata graphs describing a resource.
 * 
 * @author Hannes Ebner
 * @author Eric Johansson
 */
public class MetadataResource extends BaseResource {

	static Logger log = LoggerFactory.getLogger(MetadataResource.class);

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

		Util.handleIfUnmodifiedSince(entry, getRequest());
	}
	
	/**
	 * GET
	 * 
	 * From the REST API:
	 * 
	 * <pre>
	 * GET {baseURI}/{context-id}/metadata/{entry-id}
	 * </pre>
	 * 
	 * @return The Representation as JSON
	 */
	@Get
	public Representation represent() {
		try {
			if (entry == null) {
				log.error("Cannot find an entry with that id.");
				getResponse().setStatus(Status.CLIENT_ERROR_BAD_REQUEST);
				return new JsonRepresentation(JSONErrorMessages.errorCantNotFindEntry);
			}
			
			MediaType preferredMediaType = getRequest().getClientInfo().getPreferredMediaType(supportedMediaTypes);
			if (preferredMediaType == null) {
				preferredMediaType = MediaType.APPLICATION_RDF_XML;
			}
			Representation result = null;
			// the check for resource safety is necessary to avoid an implicit
			// getMetadata() in the case of a PUT on (not yet) existant metadata
			// - this is e.g. the case if conditional requests are issued 
			if (getRequest().getMethod().isSafe()) {
				result = getMetadata((format != null) ? format : preferredMediaType);
			} else {
				result = new EmptyRepresentation();
			}
			Date lastMod = entry.getModifiedDate();
			if (lastMod != null) {
				result.setModificationDate(lastMod);
			}
			return result;
		} catch (AuthorizationException e) {
			log.error("unauthorizedGET");
			return unauthorizedGET();
		}
	}

	@Put
	public void storeRepresentation(Representation r) {
		try {
			if (entry != null && context != null) {
				// we convert from Reference to LinkReference, otherwise we
				// can't store any (local) metadata
				if (EntryType.Reference.equals(entry.getLocationType())) {
					entry.setLocationType(EntryType.LinkReference);
				}

				MediaType mt = (format != null) ? format : getRequestEntity().getMediaType();
				modifyMetadata(mt);
			} else {
				log.error("PUT request failed, entry or context not found");
				getResponse().setStatus(Status.CLIENT_ERROR_NOT_FOUND);
				getResponse().setEntity(new JsonRepresentation(JSONErrorMessages.errorCantNotFindEntry));
			}
		} catch (AuthorizationException e) {
			unauthorizedPUT();
		}
	}

	@Post
	public void acceptRepresentation(Representation r) {
		try {
			if (entry != null && context != null) {
				if (parameters.containsKey("method")) {
					if ("delete".equalsIgnoreCase(parameters.get("method"))) {
						removeRepresentations();	
					} else if ("put".equalsIgnoreCase(parameters.get("method"))) {
						storeRepresentation(r);
					}
				}
			} else {
				log.error("POST request failed, entry or context not found");
				getResponse().setStatus(Status.CLIENT_ERROR_NOT_FOUND);
				getResponse().setEntity(new JsonRepresentation(JSONErrorMessages.errorCantNotFindEntry));
			}
		} catch (AuthorizationException e) {
			unauthorizedPOST();
		}
	}

	@Delete
	public void removeRepresentations() {
		try {
			if (entry != null && context != null) {
				entry.getLocalMetadata().setGraph(new GraphImpl());
				//getResponse().setEntity(new JsonRepresentation("{\"OK\":\"200\"}"));
			}
		} catch (AuthorizationException e) {
			unauthorizedDELETE();
		}
	}

	/**
	 * Gets the metadata JSON
	 * 
	 * @return JSON representation
	 */
	private Representation getMetadata(MediaType mediaType) throws AuthorizationException {
		EntryType locType = entry.getLocationType();
		if (EntryType.Local.equals(locType) || EntryType.Link.equals(locType)	|| EntryType.LinkReference.equals(locType)) {
			Metadata metadata = entry.getLocalMetadata();
			if (metadata != null) {
				Graph graph = metadata.getGraph();
				if (graph != null) {
					String serializedGraph = null;
					if (mediaType.equals(MediaType.APPLICATION_JSON)) {
						serializedGraph = RDFJSON.graphToRdfJson(graph);
					} else if (mediaType.equals(MediaType.APPLICATION_RDF_XML)) {
						serializedGraph = ConverterUtil.serializeGraph(graph, RDFXMLPrettyWriter.class);
					} else if (mediaType.equals(MediaType.ALL)) {
						mediaType = MediaType.APPLICATION_RDF_XML;
						serializedGraph = ConverterUtil.serializeGraph(graph, RDFXMLPrettyWriter.class);
					} else if (mediaType.equals(MediaType.TEXT_RDF_N3)) {
						serializedGraph = ConverterUtil.serializeGraph(graph, N3Writer.class);
					} else if (mediaType.getName().equals(RDFFormat.TURTLE.getDefaultMIMEType())) {
						serializedGraph = ConverterUtil.serializeGraph(graph, TurtleWriter.class);
					} else if (mediaType.getName().equals(RDFFormat.TRIX.getDefaultMIMEType())) {
						serializedGraph = ConverterUtil.serializeGraph(graph, TriXWriter.class);
					} else if (mediaType.getName().equals(RDFFormat.NTRIPLES.getDefaultMIMEType())) {
						serializedGraph = ConverterUtil.serializeGraph(graph, NTriplesWriter.class);
					} else if (mediaType.getName().equals(RDFFormat.TRIG.getDefaultMIMEType())) {
						serializedGraph = ConverterUtil.serializeGraph(graph, TriGWriter.class);
					} else if (mediaType.getName().equals(RDFFormat.JSONLD.getDefaultMIMEType())) {
						serializedGraph = ConverterUtil.serializeGraph(graph, SesameJSONLDWriter.class);
					} else if (mediaType.getName().equals("application/lom+xml")) {
						serializedGraph = ConverterUtil.convertGraphToLOM(graph, graph.getValueFactory().createURI(entry.getResourceURI().toString()));
					} else {
						getResponse().setStatus(Status.CLIENT_ERROR_NOT_ACCEPTABLE);
						return new JsonRepresentation(JSONErrorMessages.errorUnknownFormat);
					}

					if (serializedGraph != null) {
						getResponse().setStatus(Status.SUCCESS_OK);
						return new StringRepresentation(serializedGraph, mediaType);
					}
				}
			}
		}

		log.error("Can not find the metadata.");
		getResponse().setStatus(Status.CLIENT_ERROR_NOT_FOUND);
		return new JsonRepresentation(JSONErrorMessages.errorCantFindMetadata);
	}

	/**
	 * Set metadata to an entry.
	 */
	private void modifyMetadata(MediaType mediaType) throws AuthorizationException {
		Metadata metadata = entry.getLocalMetadata();
		String graphString = null;
		try {
			graphString = getRequest().getEntity().getText();
		} catch (IOException e) {
			log.error(e.getMessage());
		}
		
		if (metadata != null && graphString != null) {
			Graph deserializedGraph = null;
			if (mediaType.equals(MediaType.APPLICATION_JSON)) {
				deserializedGraph = RDFJSON.rdfJsonToGraph(graphString);
			} else if (mediaType.equals(MediaType.APPLICATION_RDF_XML)) {
				deserializedGraph = ConverterUtil.deserializeGraph(graphString, new RDFXMLParser());
			} else if (mediaType.equals(MediaType.TEXT_RDF_N3)) {
				deserializedGraph = ConverterUtil.deserializeGraph(graphString, new N3ParserFactory().getParser());
			} else if (mediaType.getName().equals(RDFFormat.TURTLE.getDefaultMIMEType())) {
				deserializedGraph = ConverterUtil.deserializeGraph(graphString, new TurtleParser());
			} else if (mediaType.getName().equals(RDFFormat.TRIX.getDefaultMIMEType())) {
				deserializedGraph = ConverterUtil.deserializeGraph(graphString, new TriXParser());
			} else if (mediaType.getName().equals(RDFFormat.NTRIPLES.getDefaultMIMEType())) {
				deserializedGraph = ConverterUtil.deserializeGraph(graphString, new NTriplesParser());
			} else if (mediaType.getName().equals(RDFFormat.TRIG.getDefaultMIMEType())) {
				deserializedGraph = ConverterUtil.deserializeGraph(graphString, new TriGParser());
			} else if (mediaType.getName().equals(RDFFormat.JSONLD.getDefaultMIMEType())) {
				deserializedGraph = ConverterUtil.deserializeGraph(graphString, new SesameJSONLDParser());
			} else if (mediaType.getName().equals("application/lom+xml")) {
				deserializedGraph = ConverterUtil.convertLOMtoGraph(graphString, entry.getResourceURI());
			} else {
				getResponse().setStatus(Status.CLIENT_ERROR_NOT_ACCEPTABLE);
				return;
			}
			
			if (deserializedGraph != null) {
				getResponse().setStatus(Status.SUCCESS_OK);
				entry.getLocalMetadata().setGraph(deserializedGraph);
				return;
			}
		}

		getResponse().setStatus(Status.CLIENT_ERROR_BAD_REQUEST);
	}

}