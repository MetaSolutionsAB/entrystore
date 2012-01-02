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

import java.net.URI;

import org.restlet.data.Status;
import org.restlet.ext.json.JsonRepresentation;
import org.restlet.representation.Representation;
import org.restlet.resource.Get;
import org.restlet.resource.ResourceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import se.kmr.scam.repository.AuthorizationException;
import se.kmr.scam.repository.PrincipalManager;
import se.kmr.scam.rest.util.Util;

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
	
	@Get
	public Representation represent() throws ResourceException {
		try {
			PrincipalManager pm = getRM().getPrincipalManager();
			URI authUser = pm.getAuthenticatedUserURI();
			if (!pm.getAdminUser().getURI().equals(authUser) && !pm.getAdminGroup().isMember(pm.getUser(authUser))) {
				return unauthorizedGET();
			}
			
			if (parameters.containsKey("cmd")) {
				String command = parameters.get("cmd");
				if ("reindex".equals(command)) {
					if (getRM().getSolrSupport() != null) {
						Runnable reindexThread = new Runnable() {
							public void run() {
								getRM().getSolrSupport().reindexLiterals();
							}
						};
						new Thread(reindexThread).start();
						log.info("Started reindexing thread");
						getResponse().setStatus(Status.SUCCESS_ACCEPTED);
						return new JsonRepresentation(Util.createResponseObject(Status.SUCCESS_ACCEPTED.getCode(), "Started reindexing"));
					} else {
						log.warn("Cannot reindex, Solr is not used");
						getResponse().setStatus(Status.CLIENT_ERROR_NOT_FOUND);
						return new JsonRepresentation(Util.createResponseObject(Status.CLIENT_ERROR_NOT_FOUND.getCode(), "Unable to reindex, Solr is not used"));
					}
				}
			}
			 
			getResponse().setStatus(Status.CLIENT_ERROR_BAD_REQUEST);
			return new JsonRepresentation(Util.createResponseObject(Status.CLIENT_ERROR_BAD_REQUEST.getCode(), "Unknown command"));
		} catch(AuthorizationException e) {
			return unauthorizedGET();
		}
	}

}