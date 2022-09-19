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
import org.entrystore.rest.util.HttpUtil;
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

	private static final Logger log = LoggerFactory.getLogger(CookieLoginResource.class);

	private static List<String> passwordLoginWhitelist;

	private static List<String> passwordLoginBlacklist;

	@Override
	public void init(Context c, Request request, Response response) {
		super.init(c, request, response);
		Config config = getRM().getConfiguration();
		if ("whitelist".equalsIgnoreCase(config.getString(Settings.AUTH_PASSWORD))) {
			passwordLoginWhitelist = config.getStringList(Settings.AUTH_PASSWORD_WHITELIST);
		}
		passwordLoginBlacklist = config.getStringList(Settings.AUTH_PASSWORD_BLACKLIST);
	}

	@Post
	public void acceptRepresentation(Representation r) {
		if (HttpUtil.isLargerThan(r, 32768)) {
			log.warn("The size of the representation is larger than 32KB or unknown, similar requests may be blocked in future versions");
		}

		boolean html = MediaType.TEXT_HTML.equals(getRequest().getClientInfo().getPreferredMediaType(Arrays.asList(MediaType.TEXT_HTML, MediaType.APPLICATION_ALL)));
		Form query;
		try {
			query = new Form(r);
		} catch (IllegalArgumentException iae) {
			log.warn(iae.getMessage());
			getResponse().setStatus(Status.CLIENT_ERROR_BAD_REQUEST);
			return;
		}

		String userName = query.getFirstValue("auth_username");
		String password = query.getFirstValue("auth_password");
		String maxAgeStr = query.getFirstValue("auth_maxage");
		
		if (userName == null || password == null) {
			getResponse().setStatus(Status.CLIENT_ERROR_BAD_REQUEST);
			return;
		}

		if (password.length() > Password.PASSWORD_MAX_LENGTH) {
			getResponse().setStatus(Status.CLIENT_ERROR_BAD_REQUEST);
			return;
		}

		userName = userName.toLowerCase();

		// Use case for whitelisting: enforced SSO with some users that should be able to login
		// with their local credentials, see https://entrystore.org/#!KB/Authentication.md
		if ((passwordLoginWhitelist != null && !passwordLoginWhitelist.contains(userName)) ||
				(passwordLoginBlacklist != null && passwordLoginBlacklist.contains(userName))) {
			getResponse().setStatus(Status.CLIENT_ERROR_UNAUTHORIZED);
			if (html) {
				getResponse().setEntity(new SimpleHTML("Login").representation("Login failed."));
			}
			return;
		}

		String saltedHashedSecret = BasicVerifier.getSaltedHashedSecret(getPM(), userName);
		boolean userIsEnabled = !BasicVerifier.isUserDisabled(getPM(), userName);
		try {
			if (saltedHashedSecret != null && Password.check(password, saltedHashedSecret)) {
				if (userIsEnabled) {
					new CookieVerifier(getRM()).createAuthToken(userName, maxAgeStr, getResponse());
					getResponse().setStatus(Status.SUCCESS_OK);
					if (html) {
						getResponse().setEntity(new SimpleHTML("Login").representation("Login successful."));
					}
					return;
				} else {
					getResponse().setStatus(Status.CLIENT_ERROR_FORBIDDEN);
					if (html) {
						getResponse().setEntity(new SimpleHTML("Login").representation("Login failed. The account is disabled."));
					}
					return;
				}
			}
		} catch (IllegalArgumentException iae) {
			log.warn(iae.getMessage());
			getResponse().setStatus(Status.CLIENT_ERROR_BAD_REQUEST);
			if (html) {
				getResponse().setEntity(new SimpleHTML("Login").representation(iae.getMessage()));
			}
			return;
		}

		getResponse().setStatus(Status.CLIENT_ERROR_UNAUTHORIZED);
		if (html) {
			getResponse().setEntity(new SimpleHTML("Login").representation("Login failed."));
		}
	}

}
