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

import static org.entrystore.repository.config.Settings.AUTH_COOKIE_INVALID_TOKEN_ERROR;
import static org.entrystore.repository.config.Settings.AUTH_COOKIE_PATH;

import java.net.URI;
import java.time.LocalDateTime;
import org.apache.commons.lang3.RandomStringUtils;
import org.entrystore.Entry;
import org.entrystore.PrincipalManager;
import org.entrystore.config.Config;
import org.entrystore.repository.RepositoryManager;
import org.entrystore.repository.config.Settings;
import org.entrystore.rest.EntryStoreApplication;
import org.entrystore.rest.auth.CustomCookieSettings.SameSite;
import org.entrystore.rest.filter.CORSFilter;
import org.entrystore.rest.util.HttpUtil;
import org.restlet.Request;
import org.restlet.Response;
import org.restlet.data.Cookie;
import org.restlet.data.CookieSetting;
import org.restlet.security.Verifier;
import org.restlet.util.Series;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * @author Hannes Ebner
 */
public class CookieVerifier implements Verifier {

	private static final Logger log = LoggerFactory.getLogger(CookieVerifier.class);

//	private static final int DEFAULT_MAX_AGE_IN_SECONDS = (int) Duration.ofDays(7).toSeconds();

	private static CustomCookieSettings cookieSettings;

	private final PrincipalManager pm;
	private final RepositoryManager rm;
	private final CORSFilter corsFilter;

	private final boolean configInvalidTokenError;

	private final LoginTokenCache loginTokenCache;

	public CookieVerifier(EntryStoreApplication app, RepositoryManager rm) {
		this(app, rm, null);
	}

	public CookieVerifier(EntryStoreApplication app, RepositoryManager rm, CORSFilter corsFilter) {
		this.rm = rm;
		this.pm = rm.getPrincipalManager();
		this.corsFilter = corsFilter;

		Config config = rm.getConfiguration();
		this.configInvalidTokenError = config.getBoolean(AUTH_COOKIE_INVALID_TOKEN_ERROR, true);
		this.loginTokenCache = app.getLoginTokenCache();

		if (cookieSettings == null) {
			SameSite sameSite = SameSite.Strict;
			try {
				String sameSiteStr = config.getString(Settings.AUTH_COOKIE_SAMESITE, SameSite.Strict.name()).toLowerCase();
				if (sameSiteStr.length() > 1) {
					sameSiteStr = sameSiteStr.substring(0, 1).toUpperCase() + sameSiteStr.substring(1);
				}
				sameSite = SameSite.valueOf(sameSiteStr);
			} catch (IllegalArgumentException iae) {
				log.warn("Invalid value for setting " + Settings.AUTH_COOKIE_SAMESITE + ": " + iae.getMessage());
			}
			cookieSettings = new CustomCookieSettings(
					config.getBoolean(Settings.AUTH_COOKIE_SECURE, true),
					config.getBoolean(Settings.AUTH_COOKIE_HTTPONLY, true),
					sameSite);
		}
	}

	@Override
	public int verify(Request request, Response response) {
		// to avoid an override of an already existing authentication, e.g. from BasicVerifier
		URI authUser = pm.getAuthenticatedUserURI();
		if (authUser != null && !pm.getGuestUser().getURI().equals(authUser)) {
			return RESULT_VALID;
		}

		URI userURI = null;

		try {
			pm.setAuthenticatedUserURI(pm.getAdminUser().getURI());
			String authToken = getAuthToken(request);
			if (authToken != null) {
				UserInfo ui = loginTokenCache.registerUserInteraction(authToken, request);

				if (ui != null) {
					String userName = ui.getUserName();
					Entry userEntry = pm.getPrincipalEntry(userName);
					if (userEntry != null) {
						userURI = userEntry.getResourceURI();
					} else {
						log.error("Auth token maps to non-existing user, removing token");
						loginTokenCache.removeToken(authToken);
						cleanCookies(rm,"auth_token", request, response);
					}
				} else {
					log.debug("Auth token not found in token cache");
					cleanCookies(rm,"auth_token", request, response);
					// CORS needs to be handled here, because we return a RESULT_INVALID which
					// interrupts the filter chain before the CORS filter can do its work
					if (corsFilter != null) {
						corsFilter.addCorsHeader(request, response);
					}
					if (configInvalidTokenError) {
						return RESULT_INVALID;
					}
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
				String value = c.getValue();
				value += "; Max-Age=0; ";
				value += cookieSettings.toString();
				CookieSetting cs = new CookieSetting(c.getVersion(), c.getName(), value, getCookiePath(rm), null);
				cs.setMaxAge(0);
				response.getCookieSettings().add(cs);
			}
		}
	}

	public void createAuthToken(String userName, boolean sessionCookie, Request request, Response response) {
		// String token = Password.getRandomBase64(128);

		// Generates a random string without the '+' and '/' chars used in Base64, which can
		// cause problems if this string is ever needed to be UUDecoded or sent over http,
		// as a in link or in a form.
		String token = RandomStringUtils.random(128, true, true);

		UserInfo userInfo = new UserInfo(userName, LocalDateTime.now());
		userInfo.setLastAccessTime(userInfo.getLoginTime());
		userInfo.setLoginExpiration(userInfo.getLoginTime().plusSeconds(loginTokenCache.getConfigCookieMaxAgeInSeconds()));
		userInfo.setLastUsedUserAgent(request.getClientInfo().getAgent());
		userInfo.setLastUsedIpAddress(HttpUtil.getClientIpAddress(request));
		loginTokenCache.putToken(token, userInfo);
		log.debug("User [{}] receives authentication token [{}]", userName, userInfo);

		// We hack the mechanism and set additional properties as part of the Cookie value since
		// there is no direct way to set properties such as Max-Age, SameSite, etc.
		// This works since Restlet does not parse or process the value; this hack might break in the future.
		String cookieValue = token + "; Max-Age=" + (sessionCookie ? -1 : Integer.MAX_VALUE) + "; " + cookieSettings;
		CookieSetting tokenCookieSetting = new CookieSetting(0, "auth_token", cookieValue);
		tokenCookieSetting.setPath(getCookiePath(rm));
		response.getCookieSettings().add(tokenCookieSetting);
	}

	private static String getCookiePath(RepositoryManager rm) {
		String cookiePath = rm.getConfiguration().getString(AUTH_COOKIE_PATH, "auto");
		if ("auto".equalsIgnoreCase(cookiePath)) {
			return rm.getRepositoryURL().getPath();
		}
		return cookiePath;
	}

	public static String getAuthToken(Request request) {
		Cookie authTokenCookie = request.getCookies().getFirst("auth_token");
		if (authTokenCookie != null) {
			return authTokenCookie.getValue();
		}
		return null;
	}
}
