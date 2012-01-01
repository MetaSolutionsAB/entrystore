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

package se.kmr.scam.rest.auth;

import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URLDecoder;

import org.restlet.Request;
import org.restlet.Response;
import org.restlet.data.Cookie;
import org.restlet.data.Form;
import org.restlet.security.Verifier;
import org.restlet.util.Series;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import se.kmr.scam.repository.Entry;
import se.kmr.scam.repository.PrincipalManager;

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
		String identifier = null;
		char[] secret = null;
		URI userURI = null;

		try {
			pm.setAuthenticatedUserURI(pm.getAdminUser().getURI());

			Form query = request.getResourceRef().getQueryAsForm();
			Series<Cookie> cookies = request.getCookies();
			if (query.getFirst("auth_user") != null && query.getFirst("auth_password") != null) {
				identifier = query.getFirstValue("auth_user");
				secret = query.getFirstValue("auth_password").toCharArray();
			} else if (cookies.getFirst("scamSimpleLogin") != null) {
				String cookie = null;
				try {
					cookie = URLDecoder.decode(cookies.getFirstValue("scamSimpleLogin"), "UTF-8");
				} catch (UnsupportedEncodingException e) {
					log.error(e.getMessage());
				}
				int separator = cookie.indexOf(":");
				if (separator >= 0) {
					identifier = cookie.substring(0, separator);
					secret = cookie.substring(separator + 1).toCharArray();
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
				PrincipalVerifier pv = new PrincipalVerifier(pm);
				char[] localSecret = pv.getLocalSecret(identifier);
				if (secret.equals(localSecret)) {
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