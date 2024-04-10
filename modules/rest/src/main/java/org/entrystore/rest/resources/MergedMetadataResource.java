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

import org.eclipse.rdf4j.model.Model;
import org.eclipse.rdf4j.rio.RDFFormat;
import org.entrystore.AuthorizationException;
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
import org.restlet.resource.Get;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Objects;

import static org.entrystore.rest.resources.AbstractMetadataResource.getFileExtensionForMediaType;


/**
 * Access to a merged graph including local and cached external metadata.
 *
 * Not using AbstractMetadataResource because we'd have to use MetadataImpl which is overkill for this case.
 * 
 * @author Hannes Ebner
 */
public class MergedMetadataResource extends BaseResource {

	static Logger log = LoggerFactory.getLogger(MergedMetadataResource.class);

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
				result = getRepresentation(entry.getMetadataGraph(), prefFormat);

				// set file name
				String fileName = entry.getFilename();
				if (fileName == null) {
					fileName = entry.getId();
				}
				fileName += "." + getFileExtensionForMediaType(prefFormat);

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
				result.setTag(Util.createTag(lastMod));
			}

			return result;
		} catch (AuthorizationException e) {
			return unauthorizedGET();
		}
	}

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

	protected Date getModificationDate() {
		return latest(entry.getExternalMetadataCacheDate(), entry.getModifiedDate());
	}

	public static Date latest(Date... dates) {
		return Arrays.stream(dates).filter(Objects::nonNull).max(Date::compareTo).orElse(null);
	}

}