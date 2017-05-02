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

import java.util.ArrayList;
import java.util.List;

import org.restlet.data.MediaType;
import org.restlet.data.Status;
import org.restlet.ext.json.JsonRepresentation;
import org.restlet.representation.Representation;
import org.restlet.representation.StringRepresentation;
import org.restlet.resource.Get;
import org.restlet.resource.ResourceException;


/**
 * Fallback
 * 
 * @author Hannes Ebner
 */
public class DefaultResource extends BaseResource {

	List<MediaType> supportedMediaTypes = new ArrayList<MediaType>();
	
	@Override
	public void doInit() {
		supportedMediaTypes.add(MediaType.APPLICATION_JSON);
		supportedMediaTypes.add(MediaType.APPLICATION_XHTML);
		supportedMediaTypes.add(MediaType.APPLICATION_ALL);
	}

	@Get
	public Representation represent() throws ResourceException {
		MediaType preferredMediaType = getRequest().getClientInfo().getPreferredMediaType(supportedMediaTypes);
		getResponse().setStatus(Status.CLIENT_ERROR_NOT_FOUND);
		String msg = "You made a request against the EntryStore REST API. There is no resource at this URI.";
		if (MediaType.APPLICATION_JSON.equals(preferredMediaType)) {
			return new JsonRepresentation("{\"error\":\"" + msg + "\"}");
		} else {
			return new StringRepresentation(msg);
		}
	}

}