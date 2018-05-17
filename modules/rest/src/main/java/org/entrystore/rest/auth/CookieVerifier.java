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
import org.entrystore.User;
import org.entrystore.repository.RepositoryManager;
import org.entrystore.repository.config.Settings;
import org.entrystore.repository.security.Password;
import org.restlet.Request;
import org.restlet.Response;
import org.restlet.data.Cookie;
import org.restlet.data.CookieSetting;
import org.restlet.engine.util.DateUtils;
import org.restlet.security.Verifier;
import org.restlet.util.Series;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.util.Date;
import java.util.concurrent.TimeUnit;


/**
 * @author Hannes Ebner
 */
public class CookieVerifier implements Verifier {

	private PrincipalManager pm;

	private RepositoryManager rm;

	private Logger log = LoggerFactory.getLogger(CookieVerifier.class);

	public CookieVerifier(RepositoryManager rm) {
		this.rm = rm;
		this.pm = rm.getPrincipalManager();
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
						cleanCookies("auth_token", request, response);
					}
				} else {
					log.debug("Auth token not found in token cache: " + authToken);
					cleanCookies("auth_token", request, response);
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

	public static void cleanCookies(String cookieName, Request request, Response response) {
		response.getCookieSettings().removeAll(cookieName);
		Series<Cookie> cookies = request.getCookies();		
		for (Cookie c : cookies) {
			if (c.getName().equals(cookieName)) {
				CookieSetting cs = new CookieSetting(c.getVersion(), c.getName(), c.getValue());
				cs.setPath(c.getPath());
				// we don't use Restlet's getCookieSettings().add(cs) with
				// Max-Age of 0 due to some shortcoming of the implementation,
				// see comment on createExpireCookie() below
				response.getHeaders().add("Set-Cookie", createExpiredCookie(cs));
			}
		}
	}

	/**
	 * This method is a workaround for Restlet's inability of setting a Cookie
	 * expiration date in the past. This is needed for some browsers (e.g. Edge)
	 * which take the local machine's time instead of the server time to decide
	 * whether the cookie is expired or not.
	 *
	 * This method should only be used to unset/expire a Cookie. Also, for the
	 * sake of simplicity it lacks support for Cookie version 1.
	 *
	 * @param cookieSetting The Cookie setting to be used to write the header.
	 * @return The value of the header (without "Set-Cookie:") to be set on a Response body.
	 */
	private static String createExpiredCookie(CookieSetting cookieSetting) {
		if (cookieSetting.getVersion() > 0) {
			throw new IllegalArgumentException("This method does not support Cookie version 1");
		}

		StringBuffer result = new StringBuffer();

		String name = cookieSetting.getName();
		String value = cookieSetting.getValue();

		if ((name == null) || (name.length() == 0)) {
			throw new IllegalArgumentException(
					"Can't write cookie. Invalid name detected");
		}

		result.append(name).append('=');

		// Append the value
		if ((value != null) && (value.length() > 0)) {
			result.append(value);
		}

		// Append the path
		String path = cookieSetting.getPath();

		if ((path != null) && (path.length() > 0)) {
			result.append("; Path=").append(path);
		}

		// Append the expiration date
		long currentTime = System.currentTimeMillis();
		long expiresTime = currentTime - TimeUnit.DAYS.toMillis(1);
		Date expires = new Date(expiresTime);

		result.append("; Expires=");
		result.append(DateUtils.format(expires,	DateUtils.FORMAT_RFC_1123.get(0)));

		// Append the domain
		String domain = cookieSetting.getDomain();

		if ((domain != null) && (domain.length() > 0)) {
			result.append("; Domain=");
			result.append(domain.toLowerCase());
		}

		// Append the secure flag
		if (cookieSetting.isSecure()) {
			result.append("; Secure");
		}

		// Append the secure flag
		if (cookieSetting.isAccessRestricted()) {
			result.append("; HttpOnly");
		}

		return result.toString();
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

		CookieSetting tokenCookieSetting = new CookieSetting(0, "auth_token", token);
		tokenCookieSetting.setMaxAge(maxAge);
		tokenCookieSetting.setPath(rm.getRepositoryURL().getPath());
		response.getCookieSettings().add(tokenCookieSetting);
	}

}