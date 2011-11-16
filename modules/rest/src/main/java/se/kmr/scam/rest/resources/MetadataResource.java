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

package se.kmr.scam.rest.resources;

import java.io.IOException;
import java.net.URI;
import java.util.Date;
import java.util.HashMap;

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
import org.restlet.Context;
import org.restlet.data.MediaType;
import org.restlet.data.Request;
import org.restlet.data.Response;
import org.restlet.data.Status;
import org.restlet.ext.json.JsonRepresentation;
import org.restlet.resource.Representation;
import org.restlet.resource.StringRepresentation;
import org.restlet.resource.Variant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import se.kmr.scam.jdil.JDILErrorMessages;
import se.kmr.scam.repository.AuthorizationException;
import se.kmr.scam.repository.Entry;
import se.kmr.scam.repository.LocationType;
import se.kmr.scam.repository.Metadata;
import se.kmr.scam.repository.impl.converters.ConverterUtil;
import se.kmr.scam.rest.util.RDFJSON;
import se.kmr.scam.rest.util.Util;

/**
 * Provides access to the metadata graphs describing a resource.
 * 
 * @author Hannes Ebner
 * @author Eric Johansson
 * @see BaseResource
 */
public class MetadataResource extends BaseResource {

	/** Logger. */
	Logger log = LoggerFactory.getLogger(MetadataResource.class);

	/** The given entry from the URL. */
	Entry entry = null;

	/** The entrys ID. */
	String entryId = null;

	/** The contexts ID. */
	String contextId = null;

	/** The context object for the context */
	se.kmr.scam.repository.Context context = null;

	/** Parameters from the URL. Example: ?scam=umu&shame=kth */
	HashMap<String, String> parameters = null;
	
	private MediaType format;
	
	/**
	 * Constructor
	 * 
	 * @param context
	 *            The parent context
	 * @param request
	 *            The Request from the HTTP connection
	 * @param response
	 *            The Response which will be sent back.
	 */
	public MetadataResource(Context context, Request request, Response response) {
		super(context, request, response);

		this.contextId = (String) getRequest().getAttributes().get("context-id");
		this.entryId = (String) getRequest().getAttributes().get("entry-id");

		String remainingPart = request.getResourceRef().getRemainingPart();

		parameters = Util.parseRequest(remainingPart);

		getVariants().add(new Variant(MediaType.ALL));
		getVariants().add(new Variant(MediaType.APPLICATION_JSON));
		getVariants().add(new Variant(MediaType.APPLICATION_RDF_XML));
		getVariants().add(new Variant(MediaType.TEXT_RDF_N3));
		getVariants().add(new Variant(new MediaType(RDFFormat.TURTLE.getDefaultMIMEType())));
		getVariants().add(new Variant(new MediaType(RDFFormat.TRIX.getDefaultMIMEType())));
		getVariants().add(new Variant(new MediaType(RDFFormat.NTRIPLES.getDefaultMIMEType())));
		getVariants().add(new Variant(new MediaType(RDFFormat.TRIG.getDefaultMIMEType())));
		getVariants().add(new Variant(new MediaType("application/lom+xml")));

		if (getCM() != null) {
			try {
				this.context = getCM().getContext(contextId);
			} catch (NullPointerException e) {
				// not a context
				this.context = null;
			}
		}

		if (this.context != null) {
			entry = this.context.get(entryId);
		}
		
		if (parameters.containsKey("format")) {
			String format = parameters.get("format");
			if (format != null) {
				this.format = new MediaType(format);
			}
		}
		
		Util.handleIfUnmodifiedSince(entry, getRequest());
	}
	
	@Override
	public boolean allowPut() {
		return true;
	}

	@Override
	public boolean allowPost() {
		return true;
	}

	@Override
	public boolean allowDelete() {
		return true;
	}

	/**
	 * GET
	 * 
	 * From the REST API:
	 * 
	 * <pre>
	 * GET {baseURI}/{portfolio-id}/metadata/{entry-id}
	 * </pre>
	 * 
	 * @param variant
	 *            Descriptor for available representations of a resource.
	 * @return The Representation as JSON
	 */
	public Representation represent(Variant variant) {
		try {
			if (entry == null) {
				log.error("Cannot find an entry with that id.");
				getResponse().setStatus(Status.CLIENT_ERROR_BAD_REQUEST);
				return new JsonRepresentation(JDILErrorMessages.errorCantNotFindEntry);
			}

			Representation result = getMetadata((format != null) ? format : variant.getMediaType());
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

	/**
	 * PUT
	 */
	public void storeRepresentation(Representation representation) {
		log.info("PUT");
		try {
			if (entry != null && context != null) {
				// we convert from Reference to LinkReference, otherwise we
				// can't store any (local) metadata
				if (LocationType.Reference.equals(entry.getLocationType())) {
					entry.setLocationType(LocationType.LinkReference);
				}

				MediaType mt = (format != null) ? format : representation.getMediaType();
				modifyMetadata(mt);
			} else {
				log.error("PUT request failed, entry or context not found");
				getResponse().setStatus(Status.CLIENT_ERROR_NOT_FOUND);
				getResponse().setEntity(new JsonRepresentation(JDILErrorMessages.errorCantNotFindEntry));
			}
		} catch (AuthorizationException e) {
			unauthorizedPUT();
		}
	}

	/**
	 * POST
	 */
	public void acceptRepresentation(Representation representation) {
		log.info("POST");
		try {
			if (entry != null && context != null) {
				if (parameters.containsKey("method")) {
					if ("delete".equalsIgnoreCase(parameters.get("method"))) {
						removeRepresentations();	
					} else if ("put".equalsIgnoreCase(parameters.get("method"))) {
						storeRepresentation(representation);
					}
				}
				return;
			}

			log.error("POST request failed, entry or context not found");
			getResponse().setStatus(Status.CLIENT_ERROR_NOT_FOUND);
			getResponse().setEntity(new JsonRepresentation(JDILErrorMessages.errorCantNotFindEntry));
		} catch (AuthorizationException e) {
			unauthorizedPOST();
		}
	}

	/**
	 * DELETE
	 */
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
		LocationType locType = entry.getLocationType();
		if (LocationType.Local.equals(locType) || LocationType.Link.equals(locType)	|| LocationType.LinkReference.equals(locType)) {
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
					} else if (mediaType.getName().equals("application/lom+xml")) {
						URI resURI = entry.getResourceURI();
						if (resURI != null) {
							serializedGraph = ConverterUtil.convertGraphToLOM(graph, graph.getValueFactory().createURI(resURI.toString()));
						}
					} else {
						getResponse().setStatus(Status.CLIENT_ERROR_NOT_ACCEPTABLE);
						return new JsonRepresentation(JDILErrorMessages.errorUnknownFormat);
					}

					if (serializedGraph != null) {
						getResponse().setStatus(Status.SUCCESS_OK);
						return new StringRepresentation(serializedGraph, mediaType);
					}
				}
			}
		}

		log.error("Can not find the metadata.");
		getResponse().setStatus(Status.CLIENT_ERROR_BAD_REQUEST);
		return new JsonRepresentation(JDILErrorMessages.errorCantFindMetadata);
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
			} else if (mediaType.getName().equals("application/lom+xml")) {
				URI resURI = entry.getResourceURI();
				if (resURI != null) {
					deserializedGraph = ConverterUtil.convertLOMtoGraph(graphString, entry.getResourceURI());
				}
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