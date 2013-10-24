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

package org.entrystore.rest.resources;

import org.entrystore.rest.auth.CookieVerifier;
import org.restlet.data.Status;
import org.restlet.ext.openid.RedirectAuthenticator;
import org.restlet.representation.Representation;
import org.restlet.representation.StringRepresentation;
import org.restlet.resource.Get;
import org.restlet.resource.ResourceException;


/**
 * OpenId helper to handle redirects and cookie cleaning.
 * 
 * @author Hannes Ebner
 */
public class OpenIdResource extends BaseResource {

	@Get
	public Representation represent() throws ResourceException {
		// We clean the cookies because we want to trigger an OpenID login
		// without getting some cached session from which we cannot get user
		// information such as email address. A cached session would require us
		// to keep track of OpenID logins mapped to internal users.
		CookieVerifier.cleanCookies(RedirectAuthenticator.DEFAULT_IDENTIFIER_COOKIE, getRequest(), getResponse());
		CookieVerifier.cleanCookies(RedirectAuthenticator.DEFAULT_ORIGINAL_REF_COOKIE, getRequest(), getResponse());
		
		if (!getPM().getGuestUser().getURI().equals(getPM().getAuthenticatedUserURI())) {
			if (parameters.containsKey("redirectOnSuccess")) {
				getResponse().redirectTemporary(parameters.get("redirectOnSuccess"));
			}
			return new StringRepresentation("OpenID login succeeded");
		} else {
			if (parameters.containsKey("redirectOnFailure")) {
				getResponse().redirectTemporary(parameters.get("redirectOnFailure"));
			}
		}
		getResponse().setStatus(Status.CLIENT_ERROR_UNAUTHORIZED);
		return new StringRepresentation("OpenID login failed");
	}

}