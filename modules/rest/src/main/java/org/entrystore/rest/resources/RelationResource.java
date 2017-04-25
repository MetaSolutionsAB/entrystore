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
import org.entrystore.rest.util.GraphUtil;
import org.entrystore.rest.util.JSONErrorMessages;
import org.openrdf.model.impl.LinkedHashModel;
import org.openrdf.rio.RDFFormat;
import org.restlet.data.MediaType;
import org.restlet.data.Status;
import org.restlet.ext.json.JsonRepresentation;
import org.restlet.representation.EmptyRepresentation;
import org.restlet.representation.Representation;
import org.restlet.representation.StringRepresentation;
import org.restlet.resource.Get;
import org.restlet.resource.ResourceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;


public class RelationResource extends BaseResource {

	static Logger log = LoggerFactory.getLogger(RelationResource.class);

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
		supportedMediaTypes.add(new MediaType("application/rdf+json"));
	}

	@Get
	public Representation represent() throws ResourceException {
		try {
			Representation result = null;

			if (entry == null) {
				getResponse().setStatus(Status.CLIENT_ERROR_NOT_FOUND);
				return new JsonRepresentation(JSONErrorMessages.errorEntryNotFound);
			}

			MediaType preferredMediaType = getRequest().getClientInfo().getPreferredMediaType(supportedMediaTypes);
			if (preferredMediaType == null) {
				preferredMediaType = MediaType.APPLICATION_RDF_XML;
			}
			MediaType prefFormat = (format != null) ? format : preferredMediaType;

			String serializedGraph = GraphUtil.serializeGraph(new LinkedHashModel(entry.getRelations()), prefFormat);

			if (serializedGraph != null) {
				getResponse().setStatus(Status.SUCCESS_OK);
				result = new StringRepresentation(serializedGraph, prefFormat);
			} else {
				result = new EmptyRepresentation();
				getResponse().setStatus(Status.SERVER_ERROR_INTERNAL);
			}

			// set modification date
			Date lastMod = entry.getModifiedDate();
			if (lastMod != null) {
				result.setModificationDate(lastMod);
			}

			return result;
		} catch(AuthorizationException e) {
			return unauthorizedGET();
		}
	}

}