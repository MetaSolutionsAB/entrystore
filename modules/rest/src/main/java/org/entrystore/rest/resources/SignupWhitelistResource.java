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

import org.entrystore.config.Config;
import org.entrystore.repository.config.Settings;
import org.json.JSONArray;
import org.restlet.ext.json.JsonRepresentation;
import org.restlet.representation.Representation;
import org.restlet.resource.Get;
import org.restlet.resource.ResourceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;


/**
 * Returns a list with all email domains that are white-listed for sign-up.
 * 
 * @author Hannes Ebner
 */
public class SignupWhitelistResource extends BaseResource {
	
	private static Logger log = LoggerFactory.getLogger(SignupWhitelistResource.class);

	@Get
	public Representation represent() throws ResourceException {
		Config config = getRM().getConfiguration();
		List<String> domainWhitelist = config.getStringList(Settings.SIGNUP_WHITELIST, new ArrayList<String>());
		JSONArray result = new JSONArray();
		for (String domain : domainWhitelist) {
			if (domain != null) {
				result.put(domain.toLowerCase());
			}
		}
		return new JsonRepresentation(result);
	}

}