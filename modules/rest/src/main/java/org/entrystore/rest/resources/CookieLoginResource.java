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
import org.entrystore.repository.security.Password;
import org.entrystore.rest.auth.BasicVerifier;
import org.entrystore.rest.auth.CookieVerifier;
import org.entrystore.rest.util.SimpleHTML;
import org.restlet.Context;
import org.restlet.Request;
import org.restlet.Response;
import org.restlet.data.Form;
import org.restlet.data.MediaType;
import org.restlet.data.Status;
import org.restlet.representation.Representation;
import org.restlet.resource.Post;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * This resource checks credentials and sets a cookie.
 * 
 * It only allows POST requests to avoid user/password in URL and therefore
 * logging in clear-text.
 * 
 * @author Hannes Ebner
 */
public class CookieLoginResource extends BaseResource {

	private static Logger log = LoggerFactory.getLogger(CookieLoginResource.class);

	private static List<String> passwordLoginWhitelist;

	@Override
	public void init(Context c, Request request, Response response) {
		super.init(c, request, response);
		Config config = getRM().getConfiguration();
		if ("whitelist".equalsIgnoreCase(config.getString(Settings.AUTH_PASSWORD))) {
			this.passwordLoginWhitelist = config.getStringList(Settings.AUTH_PASSWORD_WHITELIST, new ArrayList());
		}
	}

	@Post
	public void acceptRepresentation(Representation r) {
		boolean html = MediaType.TEXT_HTML.equals(getRequest().getClientInfo().getPreferredMediaType(Arrays.asList(MediaType.TEXT_HTML, MediaType.APPLICATION_ALL)));
		Form query = new Form(r);
		String userName = query.getFirstValue("auth_username");
		String password = query.getFirstValue("auth_password");
		String maxAgeStr = query.getFirstValue("auth_maxage");
		
		if (userName == null || password == null) {
			getResponse().setStatus(Status.CLIENT_ERROR_BAD_REQUEST);
			return;
		}

		userName = userName.toLowerCase();

		if (passwordLoginWhitelist != null && !passwordLoginWhitelist.contains(userName)) {
			getResponse().setStatus(Status.CLIENT_ERROR_UNAUTHORIZED);
			if (html) {
				getResponse().setEntity(new SimpleHTML("Login").representation("Login failed."));
			}
			return;
		}

		String saltedHashedSecret = BasicVerifier.getSaltedHashedSecret(getPM(), userName);
		boolean userExists = BasicVerifier.userExists(getPM(), userName);
		boolean userIsEnabled = !BasicVerifier.isUserDisabled(getPM(), userName);
		if (saltedHashedSecret != null && userIsEnabled && Password.check(password, saltedHashedSecret)) {
			new CookieVerifier(getRM()).createAuthToken(userName, maxAgeStr, getResponse());
	        getResponse().setStatus(Status.SUCCESS_OK);
			if (html) {
				getResponse().setEntity(new SimpleHTML("Login").representation("Login successful."));
			}
			return;
		}

		if (userExists && !userIsEnabled) {
			getResponse().setStatus(Status.CLIENT_ERROR_FORBIDDEN);
			if (html) {
				getResponse().setEntity(new SimpleHTML("Login").representation("Login failed. The account is disabled."));
			}
		} else {
			getResponse().setStatus(Status.CLIENT_ERROR_UNAUTHORIZED);
			if (html) {
				getResponse().setEntity(new SimpleHTML("Login").representation("Login failed."));
			}
		}
	}

}