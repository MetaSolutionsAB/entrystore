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

import org.restlet.security.LocalVerifier;
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
public class PrincipalVerifier extends LocalVerifier {
	
	private PrincipalManager pm;
	
	private Logger log = LoggerFactory.getLogger(PrincipalVerifier.class);

	public PrincipalVerifier(PrincipalManager pm) {
		this.pm = pm;
	}

	@Override
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

}