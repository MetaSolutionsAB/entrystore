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
import org.entrystore.Entry;
import org.entrystore.User;
import org.entrystore.AuthorizationException;
import org.entrystore.rest.auth.LoginTokenCache;
import org.entrystore.rest.auth.TokenCache;
import org.entrystore.rest.auth.UserInfo;
import org.json.JSONException;
import org.json.JSONObject;
import org.restlet.data.Cookie;
import org.restlet.ext.json.JsonRepresentation;
import org.restlet.representation.Representation;
import org.restlet.resource.Get;
import org.restlet.resource.ResourceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * This resource provides basic information about the currently logged in user.
 * 
 * @author Hannes Ebner
 */
public class UserResource extends BaseResource {

	private static Logger log = LoggerFactory.getLogger(UserResource.class);

	@Get
	public Representation represent() throws ResourceException {
		try {
			User currentUser = getPM().getUser(getPM().getAuthenticatedUserURI());
			boolean guest = currentUser.getURI().equals(getPM().getGuestUser().getURI()); 
			
			JSONObject result = new JSONObject();
			
			try {
				result.put("user", currentUser.getName());
				result.put("id", currentUser.getEntry().getId());
				result.put("uri", currentUser.getEntry().getEntryURI());

				if (!guest) {
					Context homeContext = currentUser.getHomeContext();
					if (homeContext != null) {
						result.put("homecontext", homeContext.getEntry().getId());
					}
					String userLang = currentUser.getLanguage();
					if (userLang != null) {
						result.put("language", userLang);
					}
					String extID = currentUser.getExternalID();
					if (extID != null) {
						result.put("external_id", extID);
					}

					Cookie authTokenCookie = getRequest().getCookies().getFirst("auth_token");
					if (authTokenCookie != null) {
						String authToken = authTokenCookie.getValue();
						UserInfo ui = LoginTokenCache.getInstance().getTokenValue(authToken);
						if (ui != null && ui.getLoginExpiration() != null) {
							result.put("authTokenExpires", ui.getLoginExpiration());
						}
					}
				}
			} catch (JSONException e) {
				JSONObject error = new JSONObject();
				try {
					error.put("error", e.getMessage());
				} catch (JSONException ignored) {}
				return new JsonRepresentation(error);
			}
			
			return new JsonRepresentation(result);
		} catch(AuthorizationException e) {
			return unauthorizedGET();
		}
	}

}