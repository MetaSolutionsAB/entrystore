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

import org.entrystore.repository.GraphType;
import org.entrystore.repository.Group;
import org.entrystore.repository.User;
import org.json.JSONException;
import org.json.JSONObject;
import org.restlet.data.Status;
import org.restlet.ext.json.JsonRepresentation;
import org.restlet.representation.Representation;
import org.restlet.resource.Get;
import org.restlet.resource.Put;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * This class is the resource for entries. 
 * 
 * @author Eric Johansson (eric.johansson@educ.umu.se)
 * @author Hannes Ebner
 * @see BaseResource
 */
public class AliasResource extends BaseResource {
	
	static Logger log = LoggerFactory.getLogger(AliasResource.class);
	
	@Override
	public void doInit() {

	}

	@Get
	public Representation represent() {
		String name = null;
		String alias = null;
		if (this.context != null && this.entryId == null) {
			alias = getCM().getContextAlias(context.getURI()); 
		} else if (this.context != null && this.entry != null) {
			GraphType bt = entry.getGraphType();
			if (GraphType.Group.equals(bt)) {
				name = ((Group) entry.getResource()).getName();
			} else if (GraphType.User.equals(bt)) {
				name = ((User) entry.getResource()).getName();
			} else if (GraphType.Context.equals(bt)) {
				org.entrystore.repository.Context c = getCM().getContext(entryId);
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

	@Put
	public void storeRepresentation(Representation r) {
		try {
			JSONObject newAliasObj = new JSONObject(getRequestEntity().getText());
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
				GraphType bt = entry.getGraphType();
				if (GraphType.Group.equals(bt) && name != null) {
					success = ((Group) entry.getResource()).setName(name);
				} else if (GraphType.User.equals(bt) && name != null) {
					success = ((User) entry.getResource()).setName(name);
				} else if (GraphType.Context.equals(bt) && alias != null) {
					org.entrystore.repository.Context c = getCM().getContext(entryId);
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
			getResponse().setEntity(new JsonRepresentation("{\"error\":\"The request does not contain JSON\"}"));
		}
	}
	
}