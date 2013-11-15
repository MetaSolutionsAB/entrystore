package org.entrystore.rest.auth;

import java.util.Date;

import org.entrystore.repository.RepositoryManager;
import org.entrystore.repository.User;
import org.entrystore.repository.security.Password;
import org.restlet.Context;
import org.restlet.Request;
import org.restlet.Response;
import org.restlet.Restlet;
import org.restlet.data.CookieSetting;
import org.restlet.data.Status;
import org.restlet.ext.openid.RedirectAuthenticator;
import org.restlet.security.Verifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handles OpenID login procedure and lookup for EntryStore users.
 * 
 * Overrides afterHandle() to make sure no user is set after execution.
 * 
 * @author Hannes Ebner
 */
public class ExistingUserRedirectAuthenticator extends RedirectAuthenticator {
	
	private static Logger log = LoggerFactory.getLogger(ExistingUserRedirectAuthenticator.class);
	
	RepositoryManager rm;
	
	User user;

	public ExistingUserRedirectAuthenticator(Context context, Verifier verifier, Restlet forbiddenResource, RepositoryManager rm) {
		super(context, verifier, forbiddenResource);
		this.rm = rm;
	}

	public ExistingUserRedirectAuthenticator(Context context, Verifier verifier, String identifierCookie, String origRefCookie,
			Restlet forbiddenResource, RepositoryManager rm) {
		super(context, verifier, identifierCookie, origRefCookie, forbiddenResource);
		this.rm = rm;
	}
	
	@Override
	protected void handleUser(org.restlet.security.User user) {
		String email = user.getEmail();
		if (email == null) {
			this.user = null;
			log.warn("Unable to perform OpenID login, no user email set");
			return;
		}
		
		User u = rm.getPrincipalManager().getUserByExternalID(email);
		if (u != null) {
			this.user = u;
			log.info("Found matching user " + u.getURI() + " for OpenID E-Mail " + email);
		} else {
			this.user = null;
		}
	}
	
	@Override
	protected int authenticated(Request request, Response response) {
		if (this.user == null) {
			CookieVerifier.cleanCookies("auth_token", request, response);
			CookieVerifier.cleanCookies(RedirectAuthenticator.DEFAULT_IDENTIFIER_COOKIE, request, response);
			CookieVerifier.cleanCookies(RedirectAuthenticator.DEFAULT_ORIGINAL_REF_COOKIE, request, response);
			response.setStatus(Status.CLIENT_ERROR_UNAUTHORIZED);
			return SKIP;
		}
		
		CookieVerifier.cleanCookies("auth_token", request, response);
		
		int maxAge = 7 * 24 * 3600;
		String token = Password.getRandomBase64(128);
		Date loginExpiration = new Date(new Date().getTime() + (maxAge * 1000));
		
		TokenCache.addToken(token, new UserInfo(user.getName(), loginExpiration));
		CookieSetting tokenCookieSetting = new CookieSetting(0, "auth_token", token);
		tokenCookieSetting.setMaxAge(maxAge);
		tokenCookieSetting.setPath(rm.getRepositoryURL().getPath());
		response.getCookieSettings().add(tokenCookieSetting);
		
		log.info("Setting authenticated user to " + user.getURI());
		rm.getPrincipalManager().setAuthenticatedUserURI(user.getURI());

		return CONTINUE;
	}
	
	@Override
	public void afterHandle(Request request, Response response) {
		rm.getPrincipalManager().setAuthenticatedUserURI(null);
	}

}