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

import org.entrystore.Context;
import org.entrystore.GraphType;
import org.entrystore.Group;
import org.entrystore.User;
import org.json.JSONException;
import org.json.JSONObject;
import org.restlet.data.Status;
import org.restlet.ext.json.JsonRepresentation;
import org.restlet.representation.EmptyRepresentation;
import org.restlet.representation.Representation;
import org.restlet.resource.Get;
import org.restlet.resource.Put;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;


/**
 * Resource to get and set names (called "alias" in earlier EntryStore versions) of certain entry types.
 *
 * @author Hannes Ebner
 */
public class NameResource extends BaseResource {
	
	static Logger log = LoggerFactory.getLogger(NameResource.class);

	@Get
	public Representation represent() {
		String name = null;
		if (this.context != null && this.entry != null) {
			GraphType bt = entry.getGraphType();
			if (GraphType.Group.equals(bt)) {
				name = ((Group) entry.getResource()).getName();
			} else if (GraphType.User.equals(bt)) {
				name = ((User) entry.getResource()).getName();
			} else if (GraphType.Context.equals(bt)) {
				Context c = getCM().getContext(entryId);
				name = getCM().getContextAlias(c.getURI());
			}
		}

		if (name != null) {
			JSONObject result = new JSONObject();
			try {
				result.put("name", name);
			} catch (JSONException e) {
				log.error(e.getMessage());
			}
			return new JsonRepresentation(result);
		}

		getResponse().setStatus(Status.CLIENT_ERROR_NOT_FOUND);
		return new EmptyRepresentation();
	}

	@Put
	public void storeRepresentation(Representation r) {
		String name = null;
		try {
			JSONObject nameObj = new JSONObject(getRequestEntity().getText());
			if (nameObj.has("name")) {
				name = nameObj.getString("name").trim();
			}
		} catch (JSONException jsone) {
			log.warn(jsone.getMessage());
		} catch (IOException ioe) {
			log.warn(ioe.getMessage());
		}
		if (name == null || name.length() == 0 || this.context == null || this.entry == null) {
			getResponse().setStatus(Status.CLIENT_ERROR_BAD_REQUEST);
			return;
		}

		boolean success = false;
		GraphType bt = entry.getGraphType();
		if (GraphType.Group.equals(bt)) {
			success = ((Group) entry.getResource()).setName(name);
		} else if (GraphType.User.equals(bt)) {
			success = ((User) entry.getResource()).setName(name);
		} else if (GraphType.Context.equals(bt)) {
			Context c = getCM().getContext(entryId);
			success = getCM().setContextAlias(c.getURI(), name);
		}

		if (!success) {
			getResponse().setStatus(Status.CLIENT_ERROR_CONFLICT);
		}
	}
	
}