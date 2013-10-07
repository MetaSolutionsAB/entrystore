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

import org.entrystore.repository.AuthorizationException;
import org.entrystore.rest.util.RDFJSON;
import org.json.JSONException;
import org.json.JSONObject;
import org.openrdf.model.impl.GraphImpl;
import org.restlet.data.Status;
import org.restlet.ext.json.JsonRepresentation;
import org.restlet.representation.Representation;
import org.restlet.resource.Get;
import org.restlet.resource.ResourceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public class RelationResource extends BaseResource {

	static Logger log = LoggerFactory.getLogger(RelationResource.class);
	
	@Override
	public void doInit() {
		
	}

	@Get
	public Representation represent() throws ResourceException {
		try {
			JSONObject jsonObj = null; 
			try {
				jsonObj = getRelations(); 
			} catch (JSONException e) {
				log.error(e.getMessage()); 
			}

			if (jsonObj == null) {
				getResponse().setStatus(Status.CLIENT_ERROR_NOT_FOUND);
				return new JsonRepresentation("{\"error\":\"No information found about the relation\"}");
			}

			try {
				return new JsonRepresentation(jsonObj.toString(2));
			} catch (JSONException e) {
				return new JsonRepresentation(jsonObj);
			}
		} catch(AuthorizationException e) {
			return unauthorizedGET();
		}
	}

	private JSONObject getRelations() throws JSONException {
		return new JSONObject(RDFJSON.graphToRdfJson(new GraphImpl(entry.getRelations())));
	}
	
}