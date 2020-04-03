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

package org.entrystore.rest.resources;

import org.entrystore.rest.auth.CookieVerifier;
import org.entrystore.rest.auth.LoginTokenCache;
import org.entrystore.rest.util.SimpleHTML;
import org.restlet.data.MediaType;
import org.restlet.data.Status;
import org.restlet.ext.openid.RedirectAuthenticator;
import org.restlet.representation.Representation;
import org.restlet.resource.Get;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;

/**
 * This resource removes cookies and performs other actions necessary for
 * logging out.
 * 
 * @author Hannes Ebner
 */
public class LogoutResource extends BaseResource {

	private static Logger log = LoggerFactory.getLogger(LogoutResource.class);

	@Get
	public Representation represent() {
		// remove all existing tokens from token cache; there should only be
		// one, but they may be stale cookies left from previous successful
		// authentication attempts
		String[] tokens = getRequest().getCookies().getValuesArray("auth_token");
		for (String t : tokens) {
			LoginTokenCache.getInstance().removeToken(t);
		}
		
		// remove all auth_token cookies
		CookieVerifier.cleanCookies(getRM(), "auth_token", getRequest(), getResponse());
		
		// remove also eventually existing OpenID-related cookies
		CookieVerifier.cleanCookies(getRM(), RedirectAuthenticator.DEFAULT_IDENTIFIER_COOKIE, getRequest(), getResponse());
		CookieVerifier.cleanCookies(getRM(), RedirectAuthenticator.DEFAULT_ORIGINAL_REF_COOKIE, getRequest(), getResponse());
		
		getResponse().setStatus(Status.SUCCESS_OK);
		boolean html = MediaType.TEXT_HTML.equals(getRequest().getClientInfo().getPreferredMediaType(Arrays.asList(MediaType.TEXT_HTML, MediaType.APPLICATION_ALL)));
		if (html) {
			return new SimpleHTML("Logout").representation("Logout successful.");
		}
		return null;
	}

}