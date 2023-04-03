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
import org.entrystore.Context;
import org.entrystore.PrincipalManager;
import org.entrystore.User;
import org.entrystore.rest.auth.LoginTokenCache;
import org.entrystore.rest.auth.UserInfo;
import org.json.JSONException;
import org.json.JSONObject;
import org.restlet.data.Cookie;
import org.restlet.data.Language;
import org.restlet.data.Preference;
import org.restlet.data.Status;
import org.restlet.ext.json.JsonRepresentation;
import org.restlet.representation.EmptyRepresentation;
import org.restlet.representation.Representation;
import org.restlet.resource.Get;
import org.restlet.resource.ResourceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.util.Locale;


/**
 * This resource provides basic information about the currently logged in user.
 * 
 * @author Hannes Ebner
 */
public class UserResource extends BaseResource {

	private static final Logger log = LoggerFactory.getLogger(UserResource.class);

	@Get
	public Representation represent() throws ResourceException {
		try {
			try {
				return new JsonRepresentation(buildUserInfo(getPM(), getPM().getUser(getPM().getAuthenticatedUserURI())));
			} catch (JSONException e) {
				log.error(e.getMessage());
				getResponse().setStatus(Status.SERVER_ERROR_INTERNAL);
				return new EmptyRepresentation();
			}
		} catch(AuthorizationException e) {
			return unauthorizedGET();
		}
	}

	private JSONObject buildUserInfo(PrincipalManager pm, User user) throws JSONException {
		JSONObject result = new JSONObject();
		result.put("user", user.getName());
		result.put("id", user.getEntry().getId());
		result.put("uri", user.getEntry().getEntryURI());

		// we also send back the browser's Accept-Language header
		// as this information is not accessible from JavaScript
		JSONObject clientAcceptLanguage = new JSONObject();
		// we need the hack with DecimalFormat and Float.valueOf
		// due to ugly numbers in the JSON representation otherwise
		DecimalFormat decFormat = (DecimalFormat) DecimalFormat.getInstance(Locale.ENGLISH);
		decFormat.applyPattern("#.##");
		decFormat.setRoundingMode(RoundingMode.FLOOR);
		for (Preference<Language> lang : getRequest ().getClientInfo().getAcceptedLanguages()) {
			clientAcceptLanguage.put(lang.getMetadata().toString(), Float.valueOf(decFormat.format(lang.getQuality())));
		}
		result.put("clientAcceptLanguage", clientAcceptLanguage);

		if (!user.getURI().equals(pm.getGuestUser().getURI())) {
			Context homeContext = user.getHomeContext();
			if (homeContext != null) {
				result.put("homecontext", homeContext.getEntry().getId());
			}
			String userLang = user.getLanguage();
			if (userLang != null) {
				result.put("language", userLang);
			}
			String extID = user.getExternalID();
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

		return result;
	}

}