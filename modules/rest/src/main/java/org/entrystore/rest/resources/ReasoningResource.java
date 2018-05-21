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

import org.entrystore.AuthorizationException;
import org.entrystore.PrincipalManager;
import org.entrystore.reasoning.ReasoningManager;
import org.entrystore.rest.util.Util;
import org.restlet.data.Status;
import org.restlet.ext.json.JsonRepresentation;
import org.restlet.representation.Representation;
import org.restlet.resource.Get;
import org.restlet.resource.ResourceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;


/**
 * Controls the Reasoning store.
 * 
 * @author Matthias Palmer
 */
public class ReasoningResource extends BaseResource {

	static Logger log = LoggerFactory.getLogger(ReasoningResource.class);

	@Override
	public void doInit() {

	}
	
	@Get
	public Representation represent() throws ResourceException {
		ReasoningManager resoningM = getRM().getReasoningManager();
		if (resoningM == null) {
			getResponse().setStatus(Status.CLIENT_ERROR_EXPECTATION_FAILED);
			return new JsonRepresentation(Util.createResponseObject(Status.CLIENT_ERROR_EXPECTATION_FAILED.getCode(), "Reasoning not activated"));
		}
		try {
			PrincipalManager pm = getRM().getPrincipalManager();
			URI authUser = pm.getAuthenticatedUserURI();
			if (!pm.getAdminUser().getURI().equals(authUser) && !pm.getAdminGroup().isMember(pm.getUser(authUser))) {
				return unauthorizedGET();
			}
			
			if (parameters.containsKey("cmd")) {
				String command = parameters.get("cmd");
				if ("recalculate".equalsIgnoreCase(command)) {
					Runnable reindexThread = new Runnable() {
						public void run() {
								resoningM.recalculateInferredMetadata();
							}
					};
					new Thread(reindexThread).start();
					log.info("Started recalculation thread");
					getResponse().setStatus(Status.SUCCESS_ACCEPTED);
					return new JsonRepresentation(Util.createResponseObject(Status.SUCCESS_ACCEPTED.getCode(), "Started recalculating all inferred metadata"));
				} else if ("recalculateKnown".equalsIgnoreCase(command)) {
					Runnable reindexThread = new Runnable() {
						public void run() {
							resoningM.recalculateKnownInferredMetadata();
						}
					};
					new Thread(reindexThread).start();
					log.info("Started recalculation thread");
					getResponse().setStatus(Status.SUCCESS_ACCEPTED);
					return new JsonRepresentation(Util.createResponseObject(Status.SUCCESS_ACCEPTED.getCode(), "Started recalculating all known inferred metadata"));
				}
			}
			 
			getResponse().setStatus(Status.CLIENT_ERROR_BAD_REQUEST);
			return new JsonRepresentation(Util.createResponseObject(Status.CLIENT_ERROR_BAD_REQUEST.getCode(), "Unknown command"));
		} catch(AuthorizationException e) {
			return unauthorizedGET();
		}
	}
}