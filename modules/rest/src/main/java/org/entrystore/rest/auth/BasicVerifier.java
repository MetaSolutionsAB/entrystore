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

import java.util.concurrent.ConcurrentHashMap;
import org.entrystore.Entry;
import org.entrystore.GraphType;
import org.entrystore.PrincipalManager;
import org.entrystore.User;
import org.entrystore.config.Config;
import org.entrystore.repository.config.Settings;
import org.entrystore.repository.security.Password;
import org.restlet.Request;
import org.restlet.Response;
import org.restlet.data.ChallengeResponse;
import org.restlet.data.Status;
import org.restlet.security.Verifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;


/**
 * Does a simple lookup for the secret of a principal.
 *
 * @author Hannes Ebner
 */
public class BasicVerifier implements Verifier {

	private static Logger log = LoggerFactory.getLogger(BasicVerifier.class);

	private final PrincipalManager pm;
	private final Map<String, Long> loginCache = new ConcurrentHashMap<>();
	private final List<String> passwordLoginWhitelist;

	public BasicVerifier(PrincipalManager pm, Config config) {
		this.pm = pm;
		if ("whitelist".equalsIgnoreCase(config.getString(Settings.AUTH_PASSWORD))) {
			this.passwordLoginWhitelist = config.getStringList(Settings.AUTH_PASSWORD_WHITELIST, new ArrayList());
		} else {
			passwordLoginWhitelist = null;
		}
	}

	public static String getSaltedHashedSecret(PrincipalManager pm , String identifier) {
		URI authUser = pm.getAuthenticatedUserURI();
		try {
			pm.setAuthenticatedUserURI(pm.getAdminUser().getURI());
			Entry userEntry = pm.getPrincipalEntry(identifier);
			if (userEntry != null && GraphType.User.equals(userEntry.getGraphType())) {
				User user = ((User) userEntry.getResource());
				if (user.getSaltedHashedSecret() != null) {
					return user.getSaltedHashedSecret();
				} else {
					log.error("No secret found for principal: " + identifier);
				}
			}
		} finally {
			pm.setAuthenticatedUserURI(authUser);
		}

		return null;
	}

	public static boolean userExists(PrincipalManager pm, String userName) {
		if (userName == null) {
			return false;
		}

		URI currentUser = pm.getAuthenticatedUserURI();
		try {
			pm.setAuthenticatedUserURI(pm.getAdminUser().getURI());
			Entry userEntry = pm.getPrincipalEntry(userName);
			if (userEntry != null) {
				return true;
			}
		} finally {
			pm.setAuthenticatedUserURI(currentUser);
		}
		return false;
	}

	public static boolean isUserDisabled(PrincipalManager pm, String userName) {
		URI currentUser = pm.getAuthenticatedUserURI();
		try {
			pm.setAuthenticatedUserURI(pm.getAdminUser().getURI());
			Entry userEntry = pm.getPrincipalEntry(userName);
			if (userEntry != null) {
				return ((User) userEntry.getResource()).isDisabled();
			}
		} finally {
			pm.setAuthenticatedUserURI(currentUser);
		}
		return false;
	}

	@Override
	public int verify(Request request, Response response) {
		// to avoid an override of an already existing authentication, e.g. from CookieVerifier
		URI authUser = pm.getAuthenticatedUserURI();
		if (authUser != null && !pm.getGuestUser().getURI().equals(authUser)) {
			return RESULT_VALID;
		}

		URI userURI = null;
		boolean challenge = !"false".equalsIgnoreCase(response.getRequest().getResourceRef().getQueryAsForm().getFirstValue("auth_challenge"));

		try {
			if (request.getChallengeResponse() == null && "basic".equals(request.getResourceRef().getLastSegment())) {
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
			String secret = null;
			ChallengeResponse cr = request.getChallengeResponse();
			if (cr == null) {
				identifier = "_guest";
			} else {
				identifier = request.getChallengeResponse().getIdentifier();
				secret = String.valueOf(request.getChallengeResponse().getSecret());
			}

			pm.setAuthenticatedUserURI(pm.getAdminUser().getURI());

			if (identifier == null) {
				return RESULT_MISSING;
			}

			if ("_guest".equals(identifier)) {
				userURI = pm.getGuestUser().getURI();
				return RESULT_VALID;
			}

			if (secret == null) {
				return RESULT_MISSING;
			}

			if (secret.length() > Password.PASSWORD_MAX_LENGTH) {
				return RESULT_UNSUPPORTED;
			}

			identifier = identifier.toLowerCase();
			if (passwordLoginWhitelist != null && !passwordLoginWhitelist.contains(identifier)) {
				response.setStatus(Status.CLIENT_ERROR_UNAUTHORIZED);
				return RESULT_INVALID;
			}

			Entry userEntry = pm.getPrincipalEntry(identifier);
			if (userEntry == null) {
				return RESULT_UNKNOWN;
			}

			// check whether login is cached, setting max age to 1 hour (3600 seconds)
			if (isLoginCached(userEntry.getEntryURI().toString(), secret, 3600)) {
				userURI = userEntry.getResourceURI();
				return RESULT_VALID;
			}

			if (secret != null &&
					!isUserDisabled(pm, identifier) &&
					Password.check(secret, getSaltedHashedSecret(pm, identifier))) {

				userURI = userEntry.getResourceURI();
				addLoginToCache(userEntry.getEntryURI().toString(), secret);
				return RESULT_VALID;
			} else {
				// workaround to avoid challenge response window in browsers
				if (!challenge) {
					userURI = pm.getGuestUser().getURI();
					response.setStatus(Status.CLIENT_ERROR_UNAUTHORIZED);
					return RESULT_VALID;
				}
			}
			return RESULT_INVALID;
		} finally {
			pm.setAuthenticatedUserURI(userURI);
		}
	}

	private boolean addLoginToCache(String user, String password) {
		String hash = Password.sha256(user + password);
		if (hash != null) {
			loginCache.put(hash, new Date().getTime());
			return true;
		}
		return false;
	}

	private boolean isLoginCached(String user, String password, long seconds) {
		String hash = Password.sha256(user + password);
		if (hash == null || !loginCache.containsKey(hash)) {
			return false;
		}
		long loginTime = loginCache.get(hash);
		long expirationTime = new Date().getTime() - (seconds * 1000);
		if (loginTime < expirationTime) {
			log.info("Login has expired for user " + user);
			loginCache.remove(hash);
			return false;
		}
		return true;
	}
}
