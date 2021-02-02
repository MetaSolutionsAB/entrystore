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

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.config.Configurator;
import org.entrystore.AuthorizationException;
import org.json.JSONObject;
import org.restlet.data.MediaType;
import org.restlet.data.Status;
import org.restlet.representation.Representation;
import org.restlet.resource.Put;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;


/**
 * Provides a way to temporarily override parts of the logging configuration.
 * 
 * @author Hannes Ebner
 */
public class LoggingResource extends BaseResource {

	static Logger log = LoggerFactory.getLogger(LoggingResource.class);
	
	List<MediaType> supportedMediaTypes = new ArrayList<MediaType>();

	@Override
	public void doInit() {
		supportedMediaTypes.add(MediaType.APPLICATION_JSON);
	}
	
	@Put("application/json")
	public void acceptRepresentation(Representation r) {
		try {
			URI currentUser = getPM().getAuthenticatedUserURI();
			if (!getPM().getAdminUser().getURI().equals(currentUser) &&
					!getPM().getAdminGroup().isMember(getPM().getUser(currentUser))) {
				getResponse().setStatus(Status.CLIENT_ERROR_FORBIDDEN);
				return;
			}
		} catch (AuthorizationException ae) {
			getResponse().setStatus(Status.CLIENT_ERROR_FORBIDDEN);
			return;
		}

		try {
			JSONObject body = new JSONObject(r.getText());
			if (body.has("level")) {
				Level l = Level.toLevel(body.getString("level"), Level.INFO);
				log.info("Setting log root level to {}", l);
				Configurator.setRootLevel(l);
			}
			if (body.has("packages")) {
				JSONObject packages = body.getJSONObject("packages");
				for (String p : packages.keySet()) {
					Level pL = Level.toLevel(packages.getString(p), Level.INFO);
					log.info("Setting log level of package {} to {}", p, pL);
					Configurator.setLevel(p, pL);
				}
			}
			getResponse().setStatus(Status.SUCCESS_ACCEPTED);
		} catch (IOException e) {
			log.warn(e.getMessage());
			getResponse().setStatus(Status.CLIENT_ERROR_BAD_REQUEST);
		}
	}

}