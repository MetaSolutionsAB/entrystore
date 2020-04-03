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

package org.entrystore.rest.auth;

import org.entrystore.Entry;
import org.entrystore.PrincipalManager;
import org.entrystore.repository.RepositoryManager;
import org.entrystore.repository.config.Settings;
import org.entrystore.repository.security.Password;
import org.entrystore.rest.filter.CORSFilter;
import org.restlet.Request;
import org.restlet.Response;
import org.restlet.data.Cookie;
import org.restlet.data.CookieSetting;
import org.restlet.security.Verifier;
import org.restlet.util.Series;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.util.Date;


/**
 * @author Hannes Ebner
 */
public class CookieVerifier implements Verifier {

	private PrincipalManager pm;

	private RepositoryManager rm;

	private CORSFilter corsFilter;

	private Logger log = LoggerFactory.getLogger(CookieVerifier.class);

	public CookieVerifier(RepositoryManager rm) {
		this(rm, null);
	}

	public CookieVerifier(RepositoryManager rm, CORSFilter corsFilter) {
		this.rm = rm;
		this.pm = rm.getPrincipalManager();
		this.corsFilter = corsFilter;
	}

	public int verify(Request request, Response response) {
		// to avoid an override of an already existing authentication, e.g. from BasicVerifier
		URI authUser = pm.getAuthenticatedUserURI();
		if (authUser != null && !pm.getGuestUser().getURI().equals(authUser)) {
			return RESULT_VALID;
		}
		
		URI userURI = null;

		try {
			pm.setAuthenticatedUserURI(pm.getAdminUser().getURI());
			
			Cookie authTokenCookie = request.getCookies().getFirst("auth_token");
			TokenCache<String, UserInfo> tc = LoginTokenCache.getInstance();
			if (authTokenCookie != null) {
				String authToken = authTokenCookie.getValue();
				UserInfo ui = tc.getTokenValue(authToken);
				if (ui != null) {
					String userName = ui.getUserName();
					Entry userEntry = pm.getPrincipalEntry(userName);
					if (userEntry != null) {
						userURI = userEntry.getResourceURI();
					} else {
						log.error("Auth token maps to non-existing user, removing token");
						tc.removeToken(authToken);
						cleanCookies(rm,"auth_token", request, response);
					}
				} else {
					log.debug("Auth token not found in token cache: " + authToken);
					cleanCookies(rm,"auth_token", request, response);
					// CORS needs to be handled here, because we return a RESULT_INVALID which
					// interrupts the filter chain before the CORS filter can do its work
					if (corsFilter != null) {
						corsFilter.addCorsHeader(request, response);
					}
					return RESULT_INVALID;
				}
			}

			if (userURI == null) {
				userURI = pm.getGuestUser().getURI();
				return RESULT_VALID;
			}
			return RESULT_VALID;
		} finally {
			pm.setAuthenticatedUserURI(userURI);
		}
	}

	public static void cleanCookies(RepositoryManager rm, String cookieName, Request request, Response response) {
		response.getCookieSettings().removeAll(cookieName);
		Series<Cookie> cookies = request.getCookies();		
		for (Cookie c : cookies) {
			if (c.getName().equals(cookieName)) {
				// The following is a hack, explained in createAuthToken() below
				String value = c.getValue() + "; Max-Age=0";
				CookieSetting cs = new CookieSetting(c.getVersion(), c.getName(), value, getCookiePath(rm), null);
				cs.setMaxAge(0);
				response.getCookieSettings().add(cs);
			}
		}
	}

	public void createAuthToken(String userName, String maxAgeStr, Response response) {
		// 24h default, lifetime in seconds
		int maxAge = rm.getConfiguration().getInt(Settings.AUTH_TOKEN_MAX_AGE, 24 * 3600);
		if (maxAgeStr != null) {
			try {
				maxAge = Integer.parseInt(maxAgeStr);
			} catch (NumberFormatException nfe) {}
		}

		String token = Password.getRandomBase64(128);
		Date loginExpiration = new Date(new Date().getTime() + (maxAge * 1000));
		LoginTokenCache.getInstance().putToken(token, new UserInfo(userName, loginExpiration));

		log.debug("User " + userName + " receives authentication token that will expire on " + loginExpiration);

		// We hack the mechanism and set additional properties as part of the Cookie value since
		// there is no direct way to set properties such as Max-Age, SameSite, etc.
		// This works since Restlet does not parse or process the value; this hack might break in the future.
		token = token + "; Max-Age=" + maxAge;
		CookieSetting tokenCookieSetting = new CookieSetting(0, "auth_token", token);
		// CookieSetting.setMaxAge() actually materializes as an "Expires" setting for some strange reason,
		// that's why we set "Max-Age" (which takes precedent over "Expires") above instead.
		// tokenCookieSetting.setMaxAge(maxAge);
		tokenCookieSetting.setPath(getCookiePath(rm));
		response.getCookieSettings().add(tokenCookieSetting);
	}

	private static String getCookiePath(RepositoryManager rm) {
		String cookiePath = rm.getConfiguration().getString(Settings.AUTH_COOKIE_PATH, "auto");
		if ("auto".equalsIgnoreCase(cookiePath)) {
			return rm.getRepositoryURL().getPath();
		}
		return cookiePath;
	}

}