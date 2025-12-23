package org.entrystore.rest.standalone.springboot.service.auth;

import lombok.extern.slf4j.Slf4j;
import org.entrystore.Entry;
import org.entrystore.GraphType;
import org.entrystore.PrincipalManager;
import org.entrystore.User;

import java.net.URI;

/**
 * Does a simple lookup for the secret of a principal.
 *
 * @author Hannes Ebner
 */
@Slf4j
public class BasicVerifier {

	public static String getSaltedHashedSecret(PrincipalManager pm, String identifier) {
		URI authUser = pm.getAuthenticatedUserURI();
		try {
			pm.setAuthenticatedUserURI(pm.getAdminUser().getURI());
			Entry userEntry = pm.getPrincipalEntry(identifier);
			if (userEntry != null && GraphType.User.equals(userEntry.getGraphType())) {
				User user = ((User) userEntry.getResource());
				if (user.getSaltedHashedSecret() != null) {
					return user.getSaltedHashedSecret();
				} else {
					log.error("No secret found for principal: {}", identifier);
				}
			}
		} finally {
			pm.setAuthenticatedUserURI(authUser);
		}

		return null;
	}

	public static boolean isUserDisabled(PrincipalManager pm, User user) {
		URI currentUser = pm.getAuthenticatedUserURI();
		try {
			pm.setAuthenticatedUserURI(pm.getAdminUser().getURI());
			return user.isDisabled();
		} finally {
			pm.setAuthenticatedUserURI(currentUser);
		}
	}
/*
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

			if (!isUserDisabled(pm, identifier) && Password.check(secret, getSaltedHashedSecret(pm, identifier))) {
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

 */
}
