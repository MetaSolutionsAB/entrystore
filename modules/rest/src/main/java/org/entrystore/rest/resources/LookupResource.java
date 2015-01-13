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

import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Set;

import org.entrystore.Entry;
import org.entrystore.EntryType;
import org.entrystore.impl.converters.ConverterUtil;
import org.entrystore.AuthorizationException;
import org.entrystore.rest.util.GraphUtil;
import org.entrystore.rest.util.RDFJSON;
import org.entrystore.rest.util.Util;
import org.openrdf.model.Graph;
import org.openrdf.model.impl.GraphImpl;
import org.openrdf.rio.RDFFormat;
import org.openrdf.rio.n3.N3Writer;
import org.openrdf.rio.ntriples.NTriplesWriter;
import org.openrdf.rio.rdfxml.util.RDFXMLPrettyWriter;
import org.openrdf.rio.trig.TriGWriter;
import org.openrdf.rio.trix.TriXWriter;
import org.openrdf.rio.turtle.TurtleWriter;
import org.restlet.data.MediaType;
import org.restlet.data.Status;
import org.restlet.representation.EmptyRepresentation;
import org.restlet.representation.Representation;
import org.restlet.representation.StringRepresentation;
import org.restlet.resource.Get;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * Performs a lookup based on the resource URI and returns either metadata or
 * the resource.
 * 
 * @author Hannes Ebner
 */
public class LookupResource extends BaseResource {

	static Logger log = LoggerFactory.getLogger(LookupResource.class);
	
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

		Util.handleIfUnmodifiedSince(entry, getRequest());
		
		entry = null;
	}

	@Get
	public Representation represent() {
		String resourceURI = null;
		
		if (parameters.containsKey("uri")) {
			try {
				resourceURI = URLDecoder.decode(parameters.get("uri"), "UTF-8");
			} catch (UnsupportedEncodingException e) {
				log.error(e.getMessage());
			}
		}
		
		String scope = "all";
		if (parameters.containsKey("scope")) {
			scope = parameters.get("scope").toLowerCase();
			if (!"all".equals(scope) && !"local".equals(scope) && !"external".equals(scope)) {
				getResponse().setStatus(Status.CLIENT_ERROR_BAD_REQUEST);
				return null;
			}
		}
		
		if (resourceURI == null || context == null) {
			getResponse().setStatus(Status.CLIENT_ERROR_BAD_REQUEST);
			return null;
		}
		
		// get entry based on uri
		Set<Entry> entries = context.getByResourceURI(URI.create(resourceURI));
		if (entries.isEmpty()) {
			getResponse().setStatus(Status.CLIENT_ERROR_NOT_FOUND);
			return null;
		}
		
		if (entries.size() > 1) {
			log.warn("Multiple matching entries for resource URI " + resourceURI);
		}
		
		// we take the first matching entry
		entry = entries.iterator().next();
		
		MediaType preferredMediaType = getRequest().getClientInfo().getPreferredMediaType(supportedMediaTypes);
		if (preferredMediaType == null) {
			preferredMediaType = MediaType.APPLICATION_RDF_XML;
		}
		
		Representation r;
		
		// try to get metadata
		r = getMetadata(entry, scope, (format != null) ? format : preferredMediaType);

		if (r != null) {
			// we also set the modification date on the response
			Date lastMod = entry.getModifiedDate();
			if (lastMod != null) {
				r.setModificationDate(lastMod);
			}
		}
		
		return r;
	}
	
	private Representation getMetadata(Entry e, String scope, MediaType mediaType) throws AuthorizationException {
		EntryType locType = entry.getEntryType();
		Graph graph = new GraphImpl();
		if (EntryType.Local.equals(locType) || EntryType.Link.equals(locType)) {
			if ("all".equals(scope) || "local".equals(scope)) {
				graph.addAll(entry.getLocalMetadata().getGraph());
			}
		} else if (EntryType.Reference.equals(locType)) {
			if ("all".equals(scope) || "external".equals(scope)) {
				graph.addAll(entry.getCachedExternalMetadata().getGraph());
			}
		} else if (EntryType.LinkReference.equals(locType)) {
			if ("all".equals(scope) || "local".equals(scope)) {
				graph.addAll(entry.getLocalMetadata().getGraph());
			}
			if ("all".equals(scope) || "external".equals(scope)) {
				graph.addAll(entry.getCachedExternalMetadata().getGraph());
			}
		}

		if (graph != null) {
			String serializedGraph = null;
			if (mediaType.getName().equals("application/lom+xml")) {
				URI resURI = entry.getResourceURI();
				if (resURI != null) {
					serializedGraph = ConverterUtil.convertGraphToLOM(graph, graph.getValueFactory().createURI(resURI.toString()));
				}
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

}