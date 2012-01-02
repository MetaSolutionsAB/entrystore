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
import java.util.ArrayList;
import java.util.List;

import org.openrdf.model.Graph;
import org.openrdf.rio.RDFFormat;
import org.openrdf.rio.n3.N3Writer;
import org.openrdf.rio.ntriples.NTriplesWriter;
import org.openrdf.rio.rdfxml.util.RDFXMLPrettyWriter;
import org.openrdf.rio.trig.TriGWriter;
import org.openrdf.rio.trix.TriXWriter;
import org.openrdf.rio.turtle.TurtleWriter;
import org.restlet.data.MediaType;
import org.restlet.data.Status;
import org.restlet.ext.json.JsonRepresentation;
import org.restlet.representation.Representation;
import org.restlet.representation.StringRepresentation;
import org.restlet.representation.Variant;
import org.restlet.resource.Get;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import se.kmr.scam.jdil.JDILErrorMessages;
import se.kmr.scam.repository.AuthorizationException;
import se.kmr.scam.repository.LocationType;
import se.kmr.scam.repository.Metadata;
import se.kmr.scam.repository.impl.converters.ConverterUtil;
import se.kmr.scam.rest.util.RDFJSON;

/**
 * Handles cached external metadata.
 * 
 * @author Hannes Ebner
 */
public class ExternalMetadataResource extends BaseResource {

	static Logger log = LoggerFactory.getLogger(ExternalMetadataResource.class);
	
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
		supportedMediaTypes.add(new MediaType("application/lom+xml"));
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
	@Get
	public Representation represent() {
		try {
			if (entry == null) {
				log.error("Cannot find an entry with that id.");
				getResponse().setStatus(Status.CLIENT_ERROR_BAD_REQUEST);
				return new JsonRepresentation(JDILErrorMessages.errorCantNotFindEntry);
			}

			MediaType preferredMediaType = getRequest().getClientInfo().getPreferredMediaType(supportedMediaTypes);
			if (preferredMediaType == null) {
				preferredMediaType = MediaType.APPLICATION_RDF_XML;
			}
			return getCachedExternalMetadata((format != null) ? format : preferredMediaType);
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
						serializedGraph = ConverterUtil.serializeGraph(graph, RDFXMLPrettyWriter.class);
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