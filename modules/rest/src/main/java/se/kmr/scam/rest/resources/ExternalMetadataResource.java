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

import java.net.URI;
import java.util.HashMap;

import org.openrdf.model.Graph;
import org.openrdf.rio.RDFFormat;
import org.openrdf.rio.n3.N3Writer;
import org.openrdf.rio.ntriples.NTriplesWriter;
import org.openrdf.rio.rdfxml.util.RDFXMLPrettyWriter;
import org.openrdf.rio.trig.TriGWriter;
import org.openrdf.rio.trix.TriXWriter;
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
 * This class is the resource for entries.
 * 
 * @author Eric Johansson (eric.johansson@educ.umu.se)
 * @author Hannes Ebner
 * @see BaseResource
 */
public class ExternalMetadataResource extends BaseResource {

	/** Logger. */
	Logger log = LoggerFactory.getLogger(ExternalMetadataResource.class);

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
	
	MediaType format;

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
	public ExternalMetadataResource(Context context, Request request, Response response) {
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
	}

	@Override
	public boolean allowPut() {
		return false;
	}

	@Override
	public boolean allowPost() {
		return false;
	}

	@Override
	public boolean allowDelete() {
		return false;
	}

	/**
	 * GET
	 * 
	 * From the REST API:
	 * 
	 * <pre>
	 * GET {baseURI}/{portfolio-id}/cached-external-metadata/{entry-id}
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

			return getCachedExternalMetadata((format != null) ? format : variant.getMediaType());
		} catch (AuthorizationException e) {
			log.error("unauthorizedGET");
			return unauthorizedGET();
		}
	}

	private Representation getCachedExternalMetadata(MediaType mediaType) {		
		LocationType locType = entry.getLocationType();
		if (LocationType.Reference.equals(locType) || LocationType.LinkReference.equals(locType)) {
			Metadata extMetadata = entry.getCachedExternalMetadata();
			if (extMetadata != null) {
				Graph graph = extMetadata.getGraph();
				if (graph != null) {
					String serializedGraph = null;
					if (mediaType.equals(MediaType.APPLICATION_JSON)) {
						serializedGraph = RDFJSON.graphToRdfJson(graph);
					} else if (mediaType.equals(MediaType.APPLICATION_RDF_XML) || mediaType.equals(MediaType.ALL)) {
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
					}

					if (serializedGraph != null) {
						return new StringRepresentation(serializedGraph, mediaType);
					}
				}
			}
		}

		log.error("Can not find the cached external metadata.");
		getResponse().setStatus(Status.CLIENT_ERROR_BAD_REQUEST);
		return new JsonRepresentation(JDILErrorMessages.errorCantFindCachedMetadata);
	}

}