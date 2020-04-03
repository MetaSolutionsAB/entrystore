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

import org.entrystore.PrincipalManager;
import org.entrystore.User;
import org.entrystore.repository.RepositoryManager;
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

import java.util.Date;

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

	PrincipalManager pm;

	public ExistingUserRedirectAuthenticator(Context context, Verifier verifier, Restlet forbiddenResource, RepositoryManager rm) {
		super(context, verifier, forbiddenResource);
		this.rm = rm;
		this.pm = rm.getPrincipalManager();
	}

	public ExistingUserRedirectAuthenticator(Context context, Verifier verifier, String identifierCookie, String origRefCookie,
			Restlet forbiddenResource, RepositoryManager rm) {
		super(context, verifier, identifierCookie, origRefCookie, forbiddenResource);
		this.rm = rm;
		this.pm = rm.getPrincipalManager();
	}
	
	@Override
	protected void handleUser(org.restlet.security.User user, boolean cached) {
		String email = user.getEmail();
		if (email == null) {
			log.warn("Unable to perform OpenID login, no user email set");
			return;
		}
		
		User u = rm.getPrincipalManager().getUserByExternalID(email);
		if (u != null) {
			log.info("Found match for OpenID E-Mail " + email + ", setting authenticated user to " + u.getURI());
			rm.getPrincipalManager().setAuthenticatedUserURI(u.getURI());
		}
	}
	
	@Override
	protected int authenticated(Request request, Response response) {
		User u = pm.getUser(pm.getAuthenticatedUserURI());
		if (u == null || pm.getGuestUser().getURI().equals(u.getURI())) {
			CookieVerifier.cleanCookies(rm,"auth_token", request, response);
			CookieVerifier.cleanCookies(rm, RedirectAuthenticator.DEFAULT_IDENTIFIER_COOKIE, request, response);
			CookieVerifier.cleanCookies(rm, RedirectAuthenticator.DEFAULT_ORIGINAL_REF_COOKIE, request, response);
			response.setStatus(Status.CLIENT_ERROR_UNAUTHORIZED);
			return SKIP;
		}
		
		CookieVerifier.cleanCookies(rm,"auth_token", request, response);

		int maxAge = 7 * 24 * 3600;
		String token = Password.getRandomBase64(128);
		Date loginExpiration = new Date(new Date().getTime() + (maxAge * 1000));

		LoginTokenCache.getInstance().putToken(token, new UserInfo(u.getName(), loginExpiration));
		CookieSetting tokenCookieSetting = new CookieSetting(0, "auth_token", token);
		tokenCookieSetting.setMaxAge(maxAge);
		tokenCookieSetting.setPath(rm.getRepositoryURL().getPath());
		response.getCookieSettings().add(tokenCookieSetting);

		return CONTINUE;
	}
	
	@Override
	public void afterHandle(Request request, Response response) {
		rm.getPrincipalManager().setAuthenticatedUserURI(null);
	}

}