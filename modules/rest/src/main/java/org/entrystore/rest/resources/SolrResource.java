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

import java.io.IOException;
import java.net.URI;
import org.entrystore.AuthorizationException;
import org.entrystore.Entry;
import org.entrystore.PrincipalManager;
import org.json.JSONObject;
import org.restlet.data.MediaType;
import org.restlet.data.Status;
import org.restlet.ext.json.JsonRepresentation;
import org.restlet.representation.Representation;
import org.restlet.resource.Post;
import org.restlet.resource.ResourceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * Controls the Solr index.
 *
 * @author Hannes Ebner
 */
public class SolrResource extends BaseResource {

	static Logger log = LoggerFactory.getLogger(SolrResource.class);

	@Override
	public void doInit() {

	}

	@Post
	public void acceptRepresentation(Representation r) throws ResourceException {
		if (!MediaType.APPLICATION_JSON.equals(r.getMediaType())) {
			getResponse().setStatus(Status.CLIENT_ERROR_BAD_REQUEST);
			return;
		}

		JSONObject request = null;
		try {
			request = new JsonRepresentation(r).getJsonObject();
		} catch (IOException ioe) {
			getResponse().setStatus(Status.CLIENT_ERROR_BAD_REQUEST);
			return;
		}

		if (getRM().getIndex() == null) {
			log.warn("Cannot reindex, Solr is not used");
			getResponse().setStatus(Status.SERVER_ERROR_SERVICE_UNAVAILABLE);
			return;
		}

		// JSON for reindexing request: { "command": "reindex", "context": "uri or null" }
		if (request.has("command") && "reindex".equalsIgnoreCase(request.getString("command"))) {
			PrincipalManager pm = getRM().getPrincipalManager();
			URI authUser = pm.getAuthenticatedUserURI();
			String contextURIStr = request.has("context") ? request.getString("context") : null;
			if (contextURIStr == null) {
				if (!pm.getAdminUser().getURI().equals(authUser) && !pm.getAdminGroup().isMember(pm.getUser(authUser))) {
					unauthorizedPOST();
					return;
				} else {
					getRM().getIndex().reindex(false);
					getResponse().setStatus(Status.SUCCESS_ACCEPTED);
					return;
				}
			} else {
				URI contextURI = URI.create(contextURIStr);
				Entry contextEntry = getRM().getContextManager().getByEntryURI(contextURI);
				try {
					pm.checkAuthenticatedUserAuthorized(contextEntry, PrincipalManager.AccessProperty.Administer);
				} catch (AuthorizationException ae) {
					unauthorizedPOST();
					return;
				}

				getRM().getIndex().reindex(contextURI, false);
				getResponse().setStatus(Status.SUCCESS_ACCEPTED);
				return;
			}
		}

		getResponse().setStatus(Status.CLIENT_ERROR_BAD_REQUEST);
	}
}
