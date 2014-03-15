/*
 * Copyright (c) 2007-2014 MetaSolutions AB
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

import org.entrystore.GraphType;
import org.entrystore.ContextManager;
import org.entrystore.Entry;
import org.entrystore.PrincipalManager.AccessProperty;
import org.entrystore.repository.RepositoryManager;
import org.entrystore.User;
import org.restlet.Context;
import org.restlet.Restlet;
import org.restlet.security.Verifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class NewUserRedirectAuthenticator extends ExistingUserRedirectAuthenticator {
	
	private static Logger log = LoggerFactory.getLogger(NewUserRedirectAuthenticator.class);

	public NewUserRedirectAuthenticator(Context context, Verifier verifier, Restlet forbiddenResource,
			RepositoryManager rm) {
		super(context, verifier, forbiddenResource, rm);
	}

	public NewUserRedirectAuthenticator(Context context, Verifier verifier, String identifierCookie,
			String origRefCookie, Restlet forbiddenResource, RepositoryManager rm) {
		super(context, verifier, identifierCookie, origRefCookie, forbiddenResource, rm);
	}
	
	@Override
	public void handleUser(org.restlet.security.User user) {
		super.handleUser(user);

		String email = user.getEmail();
		if (email == null) {
			log.warn("Unable to perform OpenID login, no user email set");
			return;
		}

		User u = pm.getUser(pm.getAuthenticatedUserURI());

		// Create a new user if we don't have one yet
		if (u == null || pm.getGuestUser().getURI().equals(u.getURI())) {
			ContextManager cm = rm.getContextManager();
			try {
				// We need Admin-rights to create user and context
				pm.setAuthenticatedUserURI(pm.getAdminUser().getURI());

				// Create user and set alias, metadata and e-mail
				Entry entry = rm.getPrincipalManager().createResource(null, GraphType.User, null, null);
				pm.setPrincipalName(entry.getResourceURI(), user.getEmail());
				Signup.setFoafMetadata(entry, user);
				u = (User) entry.getResource();
				u.setExternalID(user.getEmail());
				log.info("Created user " + u.getURI() + ", mapped to OpenID E-Mail " + u.getExternalID());

				// Create context and set ACL and alias
				Entry homeContext = cm.createResource(null, GraphType.Context, null, null);
				homeContext.addAllowedPrincipalsFor(AccessProperty.Administer, u.getURI());
				cm.setContextAlias(homeContext.getEntryURI(), user.getEmail());
				log.info("Created context " + homeContext.getResourceURI());

				// Set home context of user
				u.setHomeContext((org.entrystore.Context) homeContext.getResource());
				log.info("Set home context of user " + u.getURI() + " to " + homeContext.getResourceURI());
			} finally {
				if (u != null) {
					pm.setAuthenticatedUserURI(u.getURI());
				} else {
					pm.setAuthenticatedUserURI(pm.getGuestUser().getURI());
				}
			}
		}
	}

}