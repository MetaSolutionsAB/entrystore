package org.entrystore.rest.auth;

import org.entrystore.repository.ResourceType;
import org.entrystore.repository.ContextManager;
import org.entrystore.repository.Entry;
import org.entrystore.repository.PrincipalManager.AccessProperty;
import org.entrystore.repository.RepositoryManager;
import org.entrystore.repository.User;
import org.entrystore.repository.util.NS;
import org.openrdf.model.Graph;
import org.openrdf.model.ValueFactory;
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
				Entry entry = rm.getPrincipalManager().createResource(null, ResourceType.User, null, null);
				pm.setPrincipalName(entry.getResourceURI(), user.getEmail());
				Signup.setFoafMetadata(entry, user);
				u = (User) entry.getResource();
				u.setExternalID(user.getEmail());
				log.info("Created user " + u.getURI() + ", mapped to OpenID E-Mail " + u.getExternalID());

				// Create context and set ACL and alias
				Entry homeContext = cm.createResource(null, ResourceType.Context, null, null);
				homeContext.addAllowedPrincipalsFor(AccessProperty.Administer, u.getURI());
				cm.setContextAlias(homeContext.getEntryURI(), user.getEmail());
				log.info("Created context " + homeContext.getResourceURI());

				// Set home context of user
				u.setHomeContext((org.entrystore.repository.Context) homeContext.getResource());
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