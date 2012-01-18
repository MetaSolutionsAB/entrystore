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

import java.net.URI;
import java.util.Arrays;

import org.restlet.Request;
import org.restlet.Response;
import org.restlet.data.Status;
import org.restlet.security.Verifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import se.kmr.scam.repository.BuiltinType;
import se.kmr.scam.repository.Entry;
import se.kmr.scam.repository.PrincipalManager;
import se.kmr.scam.repository.User;

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
		URI userURI = null;
		boolean challenge = !"false".equalsIgnoreCase(response.getRequest().getResourceRef().getQueryAsForm().getFirstValue("auth_challenge"));

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
			if (request.getChallengeResponse() ==  null) {
				identifier = "_guest";
			} else {
				identifier = request.getChallengeResponse().getIdentifier();
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

				char[] secret = request.getChallengeResponse().getSecret();
				char[] localSecret = getLocalSecret(identifier);
				if (Arrays.equals(secret, localSecret)) {
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