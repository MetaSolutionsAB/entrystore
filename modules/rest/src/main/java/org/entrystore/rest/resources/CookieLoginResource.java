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

import java.util.Date;

import org.entrystore.repository.security.Password;
import org.entrystore.rest.auth.BasicVerifier;
import org.entrystore.rest.auth.LoginTokenCache;
import org.entrystore.rest.auth.TokenCache;
import org.entrystore.rest.auth.UserInfo;
import org.restlet.data.CookieSetting;
import org.restlet.data.Form;
import org.restlet.data.Status;
import org.restlet.representation.Representation;
import org.restlet.resource.Post;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This resource checks credentials and sets a cookie.
 * 
 * It only allows POST requests to avoid user/password in URL and therefore
 * logging in clear-text.
 * 
 * @author Hannes Ebner
 */
public class CookieLoginResource extends BaseResource {

	private static Logger log = LoggerFactory.getLogger(CookieLoginResource.class);

	@Post
	public void acceptRepresentation(Representation r) {
		Form query = new Form(r);
		String userName = query.getFirstValue("auth_username");
		String password = query.getFirstValue("auth_password");
		String maxAgeStr = query.getFirstValue("auth_maxage");
		
		if (userName == null || password == null) {
			getResponse().setStatus(Status.CLIENT_ERROR_BAD_REQUEST);
			return;
		}
		
		String saltedHashedSecret = BasicVerifier.getSaltedHashedSecret(getPM(), userName);
		if (saltedHashedSecret != null && Password.check(password, saltedHashedSecret)) {
			// 24h default, lifetime in seconds
			int maxAge = 24 * 3600;
			if (maxAgeStr != null) {
				try {
					maxAge = Integer.parseInt(maxAgeStr);
				} catch (NumberFormatException nfe) {}
			}
			
			String token = Password.getRandomBase64(128);
			Date loginExpiration = new Date(new Date().getTime() + (maxAge * 1000));
			LoginTokenCache.getInstance().addToken(token, new UserInfo(userName, loginExpiration));
			
			CookieSetting tokenCookieSetting = new CookieSetting(0, "auth_token", token);
			tokenCookieSetting.setMaxAge(maxAge);
			tokenCookieSetting.setPath(getRM().getRepositoryURL().getPath());
	        getResponse().getCookieSettings().add(tokenCookieSetting);
	        
	        getResponse().setStatus(Status.SUCCESS_OK);
		} else {
			getResponse().setStatus(Status.CLIENT_ERROR_UNAUTHORIZED);
		}
	}

}