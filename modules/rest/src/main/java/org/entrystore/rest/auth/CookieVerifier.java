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

import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URLDecoder;

import org.entrystore.repository.Entry;
import org.entrystore.repository.PrincipalManager;
import org.entrystore.repository.security.Password;
import org.restlet.Request;
import org.restlet.Response;
import org.restlet.data.Cookie;
import org.restlet.data.Form;
import org.restlet.data.Status;
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
			Series<Cookie> cookies = request.getCookies();
			Cookie cookieSimpleLogin = cookies.getFirst("scamSimpleLogin");
			boolean challenge = !"false".equalsIgnoreCase(response.getRequest().getResourceRef().getQueryAsForm().getFirstValue("auth_challenge"));
			
			if (cookieSimpleLogin == null && request.getChallengeResponse() == null && "login".equals(request.getResourceRef().getLastSegment())) {
				if (challenge) {
					return RESULT_MISSING;
				} else {
					// workaround to avoid challenge response window in browsers
					userURI = pm.getGuestUser().getURI();
					response.setStatus(Status.CLIENT_ERROR_UNAUTHORIZED);
					return RESULT_VALID;
				}
			}
			
			pm.setAuthenticatedUserURI(pm.getAdminUser().getURI());
			
			String identifier = null;
			String secret = null;
			Form query = request.getResourceRef().getQueryAsForm();
			
			if (query.getFirst("auth_user") != null && query.getFirst("auth_password") != null) {
				identifier = query.getFirstValue("auth_user");
				secret = query.getFirstValue("auth_password");
			} else if (cookieSimpleLogin != null) {
				String cookie = null;
				try {
					cookie = URLDecoder.decode(cookies.getFirstValue("scamSimpleLogin"), "UTF-8");
				} catch (UnsupportedEncodingException e) {
					log.error(e.getMessage());
				}
				int separator = cookie.indexOf(":");
				if (separator >= 0) {
					identifier = cookie.substring(0, separator);
					secret = cookie.substring(separator + 1);
				}
			}

			if (identifier == null) {
				return RESULT_MISSING;
			} else if ("_guest".equals(identifier)) {
				userURI = pm.getGuestUser().getURI();
				return RESULT_VALID;
			} else {
				Entry userEntry = pm.getPrincipalEntry(identifier);
				if (userEntry == null) {
					return RESULT_UNKNOWN;
				}
				BasicVerifier pv = new BasicVerifier(pm);
				String saltedHashedSecret = pv.getSaltedHashedSecret(identifier);
				if (secret != null && Password.check(secret, saltedHashedSecret)) {
					userURI = userEntry.getResourceURI();
					return RESULT_VALID;
				}
			}
			return RESULT_INVALID;
		} finally {
			pm.setAuthenticatedUserURI(userURI);
		}
	}

}