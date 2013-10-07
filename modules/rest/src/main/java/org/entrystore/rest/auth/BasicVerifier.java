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
import java.util.Arrays;
import java.util.Map;

import org.entrystore.repository.BuiltinType;
import org.entrystore.repository.Entry;
import org.entrystore.repository.PrincipalManager;
import org.entrystore.repository.User;
import org.entrystore.rest.util.Util;
import org.restlet.Request;
import org.restlet.Response;
import org.restlet.data.ChallengeResponse;
import org.restlet.data.Status;
import org.restlet.security.Verifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * Does a simple lookup for the secret of a principal.
 * 
 * @author Hannes Ebner
 */
public class BasicVerifier implements Verifier {
	
	private PrincipalManager pm;
	
	private static Logger log = LoggerFactory.getLogger(BasicVerifier.class);

	public BasicVerifier(PrincipalManager pm) {
		this.pm = pm;
	}

	public char[] getLocalSecret(String identifier) {
		URI authUser = pm.getAuthenticatedUserURI();
		try {
			pm.setAuthenticatedUserURI(pm.getAdminUser().getURI());
			Entry userEntry = pm.getPrincipalEntry(identifier);
			if (userEntry != null && BuiltinType.User.equals(userEntry.getBuiltinType())) {
				User user = ((User) userEntry.getResource());
				if (user.getSecret() != null) {
					return user.getSecret().toCharArray();
				} else {
					log.error("No secret found for principal: " + identifier);
				}
			}
		} finally {
			pm.setAuthenticatedUserURI(authUser);
		}

		return null;
	}

	public int verify(Request request, Response response) {
		// to avoid an override of an already existing authentication, e.g. from CookieVerifier
		URI authUser = pm.getAuthenticatedUserURI();
		if (authUser != null && !pm.getGuestUser().getURI().equals(authUser)) {
			return RESULT_VALID;
		}
		
		URI userURI = null;
		boolean challenge = !"false".equalsIgnoreCase(response.getRequest().getResourceRef().getQueryAsForm().getFirstValue("auth_challenge"));
		Map<String, String> params = Util.parseRequest(request.getResourceRef().getRemainingPart());

		try {
			if (request.getChallengeResponse() == null && "login".equals(request.getResourceRef().getLastSegment())) {
				if (challenge) {
					return RESULT_MISSING;
				} else {
					// workaround to avoid challenge response window in browsers
					userURI = pm.getGuestUser().getURI();
					response.setStatus(Status.CLIENT_ERROR_UNAUTHORIZED);
					return RESULT_VALID;
				}
			}

			String identifier = null;
			char[] secret = null;
			ChallengeResponse cr = request.getChallengeResponse();
			if (cr == null && !params.containsKey("auth_user")) {
				identifier = "_guest";
			} else {
				if (cr != null) {
					identifier = request.getChallengeResponse().getIdentifier();
				}
				if (identifier == null) {
					// fallback for requests where credentials are sent as URL parameters
					identifier = params.get("auth_user");
					if (params.containsKey("auth_password") && params.get("auth_password") != null) {
						secret = params.get("auth_password").toCharArray();
					}
				} else {
					secret = request.getChallengeResponse().getSecret();
				}
			}

			pm.setAuthenticatedUserURI(pm.getAdminUser().getURI());

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
				char[] localSecret = getLocalSecret(identifier);
				if (secret != null && Arrays.equals(secret, localSecret)) {
					userURI = userEntry.getResourceURI();
					return RESULT_VALID;
				} else {
					// workaround to avoid challenge response window in browsers
					if (!challenge) {
						userURI = pm.getGuestUser().getURI();
						response.setStatus(Status.CLIENT_ERROR_UNAUTHORIZED);
						return RESULT_VALID;
					}
				}
			}

			return RESULT_INVALID;
		} finally {
			pm.setAuthenticatedUserURI(userURI);
		}
	}

}