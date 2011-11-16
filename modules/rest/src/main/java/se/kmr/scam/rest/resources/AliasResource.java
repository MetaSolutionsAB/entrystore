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
import org.restlet.Context;
import org.restlet.data.MediaType;
import org.restlet.data.Request;
import org.restlet.data.Response;
import org.restlet.data.Status;
import org.restlet.ext.json.JsonRepresentation;
import org.restlet.resource.Representation;
import org.restlet.resource.Variant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import se.kmr.scam.repository.BuiltinType;
import se.kmr.scam.repository.Entry;
import se.kmr.scam.repository.Group;
import se.kmr.scam.repository.User;

/**
 * This class is the resource for entries. 
 * 
 * @author Eric Johansson (eric.johansson@educ.umu.se)
 * @author Hannes Ebner
 * @see BaseResource
 */
public class AliasResource extends BaseResource {
	/** Logger. */
	Logger log = LoggerFactory.getLogger(AliasResource.class);
	/** The contexts ID. */
	String contextId = null;
	/** The context object for the context */
	se.kmr.scam.repository.Context context = null;
	
	Entry entry = null;
	
	String entryId = null;
	
	/**
	 * Constructor 
	 * 
	 * @param context The parent context
	 * @param request The Request from the HTTP connection
	 * @param response The Response which will be sent back.
	 */
	public AliasResource(Context context, Request request, Response response) {
		super(context, request, response);

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
		
		if (context != null && entryId != null) {
			entry = this.context.get(entryId);
		}
	}

	/**
	 * GET
	 */
	public Representation represent(Variant variant) {
		String name = null;
		String alias = null;
		if (this.context != null && this.entryId == null) {
			alias = getCM().getContextAlias(context.getURI()); 
		} else if (this.context != null && this.entry != null) {
			BuiltinType bt = entry.getBuiltinType();
			if (BuiltinType.Group.equals(bt)) {
				name = ((Group) entry.getResource()).getName();
			} else if (BuiltinType.User.equals(bt)) {
				name = ((User) entry.getResource()).getName();
			} else if (BuiltinType.Context.equals(bt)) {
				se.kmr.scam.repository.Context c = getCM().getContext(entryId);
				alias = getCM().getContextAlias(c.getURI());
			}
		}
		
		JSONObject result = new JSONObject();
		try {
			if (alias != null) {
				result.put("alias", alias);
			} else if (name != null) {
				result.put("name", name);
			}
		} catch (JSONException e) {
			log.error(e.getMessage());
		}
		if (result.length() > 0) {
			return new JsonRepresentation(result);
		}
		
		getResponse().setStatus(Status.CLIENT_ERROR_NOT_FOUND);
		return new JsonRepresentation("{error:\"Cannot find that context or entry\"}"); 
	}

	/**
	 * PUT
	 */
	public void storeRepresentation(Representation representation) {
		try {
			JSONObject newAliasObj = new JSONObject(this.getRequest().getEntity().getText());
			String alias = null;
			String name = null;
			if (newAliasObj.has("alias")) {
				alias = newAliasObj.getString("alias");
			}
			if (newAliasObj.has("name")) {
				name = newAliasObj.getString("name");	
			}
			
			boolean success = false;
			if (this.context != null && this.entryId == null) {
				success = getCM().setContextAlias(context.getURI(), alias); 
			} else if (this.context != null && this.entry != null) {
				BuiltinType bt = entry.getBuiltinType();
				if (BuiltinType.Group.equals(bt) && name != null) {
					success = ((Group) entry.getResource()).setName(name);
				} else if (BuiltinType.User.equals(bt) && name != null) {
					success = ((User) entry.getResource()).setName(name);
				} else if (BuiltinType.Context.equals(bt) && alias != null) {
					se.kmr.scam.repository.Context c = getCM().getContext(entryId);
					success = getCM().setContextAlias(c.getURI(), alias);
				}
			} else {
				getResponse().setStatus(Status.CLIENT_ERROR_BAD_REQUEST);
			}
			
			if (!success) {
				JSONObject msg = new JSONObject();
				msg.put("error", "The alias already exists"); 
				getResponse().setStatus(Status.CLIENT_ERROR_BAD_REQUEST);
				getResponse().setEntity(new JsonRepresentation(msg));
			}
		} catch (Exception e) {
			getResponse().setStatus(Status.CLIENT_ERROR_BAD_REQUEST);
			getResponse().setEntity(new JsonRepresentation("{error:\"The request doesn't contain a JSON object.\"}"));
		}
	}

	/**
	 * Allow methods.
	 */
	@Override
	public boolean allowPut() {
		return true;
	}
	
}