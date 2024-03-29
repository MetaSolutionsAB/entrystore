/*
 * Copyright (c) 2007-2018 MetaSolutions AB
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

import static org.entrystore.rest.util.HttpUtil.COOKIE_AUTH_TOKEN;
import static org.restlet.data.Status.CLIENT_ERROR_BAD_REQUEST;
import static org.restlet.data.Status.CLIENT_ERROR_NOT_FOUND;

import java.io.IOException;
import java.util.Map;
import org.entrystore.rest.EntryStoreApplication;
import org.entrystore.rest.auth.LoginTokenCache;
import org.entrystore.rest.auth.UserInfo;
import org.json.JSONException;
import org.json.JSONObject;
import org.restlet.data.Cookie;
import org.restlet.ext.json.JsonRepresentation;
import org.restlet.representation.EmptyRepresentation;
import org.restlet.representation.Representation;
import org.restlet.resource.Delete;
import org.restlet.resource.Get;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * Returns the tokens this user have used in all active login sessions.
 *
 */
public class TokenResource extends BaseResource {

	static Logger log = LoggerFactory.getLogger(TokenResource.class);

	final LoginTokenCache loginTokenCache = ((EntryStoreApplication)getApplication()).getLoginTokenCache();

	@Get
	public Representation get() {
		if (getPM().currentUserIsGuest()) {
			return unauthorizedGET();
		}

		Cookie authTokenCookie = getRequest().getCookies().getFirst(COOKIE_AUTH_TOKEN);
		if (authTokenCookie == null) { // Probably using Basic Authentication
			getResponse().setStatus(CLIENT_ERROR_NOT_FOUND);
			return new EmptyRepresentation();
		}

		UserInfo userInfo = loginTokenCache.getTokenValue(authTokenCookie.getValue());
		Map<String, UserInfo> loginTokens = loginTokenCache.getTokens(userInfo.getUserName());
		JSONObject json = new JSONObject(loginTokens);
		return new JsonRepresentation(json);
	}

	@Delete
	public void delete(Representation representation) {
		if (getPM().currentUserIsGuest()) {
			unauthorizedDELETE();
		}

		try {
			String json = representation.getText();
			JSONObject jsonObject = new JSONObject(json);
			String authToken = jsonObject.getString("token");
			loginTokenCache.removeToken(authToken);
		} catch (IOException e) {
			log.debug(e.getMessage(), e);
			getResponse().setStatus(CLIENT_ERROR_BAD_REQUEST);
		} catch (JSONException e) {
			log.debug(e.getMessage(), e);
			getResponse().setStatus(CLIENT_ERROR_BAD_REQUEST);
		}
	}
}
