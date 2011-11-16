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

import org.json.JSONException;
import org.json.JSONObject;
import org.openrdf.model.impl.GraphImpl;
import org.restlet.Context;
import org.restlet.data.MediaType;
import org.restlet.data.Request;
import org.restlet.data.Response;
import org.restlet.data.Status;
import org.restlet.ext.json.JsonRepresentation;
import org.restlet.resource.Representation;
import org.restlet.resource.ResourceException;
import org.restlet.resource.Variant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import se.kmr.scam.repository.AuthorizationException;
import se.kmr.scam.repository.Entry;
import se.kmr.scam.rest.ScamApplication;
import se.kmr.scam.rest.util.RDFJSON;

/**
 * @author Eric Johansson (eric.johansson@educ.umu.se) 
 * @see BaseResource
 */

public class RelationResource extends BaseResource {

	Logger log = LoggerFactory.getLogger(RelationResource.class);

	/** The given entry from the URL. */
	Entry entry = null;

	/** The entrys ID. */
	String entryId = null; 

	/** The contexts ID. */
	String contextId = null;

	/** The context object for the context */
	se.kmr.scam.repository.Context context = null;
	
	ScamApplication scamApp;

	public RelationResource(Context context, Request request,
			Response response) {
		super(context, request, response);

		scamApp = (ScamApplication) getContext().getAttributes().get(ScamApplication.KEY);

		this.contextId = (String) getRequest().getAttributes().get("context-id");
		this.entryId = (String) getRequest().getAttributes().get("entry-id");

		getVariants().add(new Variant(MediaType.APPLICATION_JSON));
		getVariants().add(new Variant(MediaType.ALL));

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
	}

	@Override
	public boolean allowGet() {
		return true;
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

	//GET
	@Override
	public Representation represent(Variant variant) throws ResourceException {
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