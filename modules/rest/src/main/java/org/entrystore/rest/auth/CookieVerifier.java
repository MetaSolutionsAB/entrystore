/**
 * Copyright (c) 2007-2010
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

import java.net.URI;
import java.util.Date;

import org.entrystore.repository.Entry;
import org.entrystore.repository.PrincipalManager;
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

	private PrincipalManager pm;

	private Logger log = LoggerFactory.getLogger(CookieVerifier.class);

	public CookieVerifier(PrincipalManager pm) {
		this.pm = pm;
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
					userURI = userEntry.getResourceURI();
				} else {
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
		Series<Cookie> cookies = request.getCookies();		
		for (Cookie c : cookies) {
			if (c.getName().equals(cookieName)) {
				CookieSetting cs = new CookieSetting(c.getVersion(), c.getName(), c.getValue());
				cs.setPath(c.getPath());
				cs.setMaxAge(0);
				response.getCookieSettings().add(cs);
			}
		}
	}
	
}