/*
 * Copyright (c) 2007-2014 MetaSolutions AB
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

import org.entrystore.AuthorizationException;
import org.entrystore.EntryType;
import org.entrystore.Metadata;
import org.entrystore.impl.converters.ConverterUtil;
import org.entrystore.rest.util.GraphUtil;
import org.entrystore.rest.util.JSONErrorMessages;
import org.entrystore.rest.util.Util;
import org.openrdf.model.Graph;
import org.openrdf.model.impl.GraphImpl;
import org.openrdf.rio.RDFFormat;
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

import java.io.IOException;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;


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

		Util.handleIfUnmodifiedSince(entry, getRequest());
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
				return new JsonRepresentation(JSONErrorMessages.errorCantNotFindEntry);
			}

			if (getMetadata() == null) {
				getResponse().setStatus(Status.CLIENT_ERROR_NOT_FOUND);
				return null;
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
				result = getRepresentation(getMetadata(), (format != null) ? format : preferredMediaType);
			} else {
				result = new EmptyRepresentation();
			}
			Date lastMod = entry.getModifiedDate();
			if (lastMod != null) {
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
				getResponse().setEntity(new JsonRepresentation(JSONErrorMessages.errorCantNotFindEntry));
				return;
			}

			if (getMetadata() == null) {
				getResponse().setStatus(Status.CLIENT_ERROR_NOT_FOUND);
				return;
			}

			MediaType mt = (format != null) ? format : getRequestEntity().getMediaType();
			copyRepresentationToMetadata(r, getMetadata(), mt);
		} catch (AuthorizationException e) {
			unauthorizedPUT();
		}
	}

	@Post
	public void acceptRepresentation(Representation r) {
		try {
			if (entry == null) {
				getResponse().setStatus(Status.CLIENT_ERROR_NOT_FOUND);
				getResponse().setEntity(new JsonRepresentation(JSONErrorMessages.errorCantNotFindEntry));
				return;
			}

			if (getMetadata() == null) {
				getResponse().setStatus(Status.CLIENT_ERROR_NOT_FOUND);
				return;
			}

			if (parameters.containsKey("method")) {
				if ("delete".equalsIgnoreCase(parameters.get("method"))) {
					removeRepresentations();
				} else if ("put".equalsIgnoreCase(parameters.get("method"))) {
					storeRepresentation(r);
				}
			}
		} catch (AuthorizationException e) {
			unauthorizedPOST();
		}
	}

	@Delete
	public void removeRepresentations() {
		try {
			if (entry == null) {
				getResponse().setStatus(Status.CLIENT_ERROR_NOT_FOUND);
				getResponse().setEntity(new JsonRepresentation(JSONErrorMessages.errorCantNotFindEntry));
				return;
			}

			if (getMetadata() == null) {
				getResponse().setStatus(Status.CLIENT_ERROR_NOT_FOUND);
				return;
			}

			getMetadata().setGraph(new GraphImpl());
		} catch (AuthorizationException e) {
			unauthorizedDELETE();
		}
	}

	/**
	 * @return Metadata in the requested format.
	 */
	private Representation getRepresentation(Metadata metadata, MediaType mediaType) throws AuthorizationException {
		EntryType locType = entry.getEntryType();
		if (metadata != null) {
			Graph graph = metadata.getGraph();
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
	 * @return the relevant metadata graph. May be null.
	 */
	protected abstract Metadata getMetadata();

}