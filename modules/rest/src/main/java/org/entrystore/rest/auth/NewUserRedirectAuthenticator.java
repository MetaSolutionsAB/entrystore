package org.entrystore.rest.auth;

import org.entrystore.repository.BuiltinType;
import org.entrystore.repository.ContextManager;
import org.entrystore.repository.Entry;
import org.entrystore.repository.PrincipalManager;
import org.entrystore.repository.PrincipalManager.AccessProperty;
import org.entrystore.repository.RepositoryManager;
import org.entrystore.repository.User;
import org.entrystore.repository.impl.converters.NS;
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
		if (this.user == null && user.getEmail() != null) {
			PrincipalManager pm = rm.getPrincipalManager();
			ContextManager cm = rm.getContextManager();

			try {
				// We need Admin-rights to create user and context
				pm.setAuthenticatedUserURI(pm.getAdminUser().getURI());

				// Create user and set alias, metadata and e-mail
				Entry entry = rm.getPrincipalManager().createResource(null, BuiltinType.User, null, null);
				pm.setPrincipalName(entry.getResourceURI(), user.getEmail());
				setFoafMetadata(entry, user);
				User u = (User) entry.getResource();
				u.setExternalID(user.getEmail());
				log.info("Created user " + u.getURI() + ", mapped to OpenID E-Mail " + u.getExternalID());

				// Create context and set ACL and alias
				Entry homeContext = cm.createResource(null, BuiltinType.Context, null, null);
				homeContext.addAllowedPrincipalsFor(AccessProperty.Administer, u.getURI());
				cm.setContextAlias(homeContext.getEntryURI(), user.getEmail());
				log.info("Created context " + homeContext.getResourceURI());

				// Set home context of user
				u.setHomeContext((org.entrystore.repository.Context) homeContext.getResource());
				log.info("Set home context of user " + u.getURI() + " to " + homeContext.getResourceURI());

				// We set the user, this will be used later by super.authenticated()
				this.user = u;
			} finally {
				pm.setAuthenticatedUserURI(pm.getGuestUser().getURI());
			}
		}
	}
	
	private void setFoafMetadata(Entry entry, org.restlet.security.User userInfo) {
		Graph graph = entry.getLocalMetadata().getGraph();
		ValueFactory vf = graph.getValueFactory();
		org.openrdf.model.URI resourceURI = vf.createURI(entry.getResourceURI().toString());
		String fullname = null;
		if (userInfo.getFirstName() != null) {
			fullname = userInfo.getFirstName();
			graph.add(vf.createStatement(resourceURI, vf.createURI(NS.foaf, "givenName"), vf.createLiteral(userInfo.getFirstName())));
			graph.add(vf.createStatement(resourceURI, vf.createURI(NS.foaf, "firstName"), vf.createLiteral(userInfo.getFirstName())));
		}
		if (userInfo.getLastName() != null) {
			if (fullname != null) {
				fullname = fullname + " " + userInfo.getLastName();
			} else {
				fullname = userInfo.getLastName();
			}
			graph.add(vf.createStatement(resourceURI, vf.createURI(NS.foaf, "familyName"), vf.createLiteral(userInfo.getLastName())));
			graph.add(vf.createStatement(resourceURI, vf.createURI(NS.foaf, "lastName"), vf.createLiteral(userInfo.getLastName())));
		}
		if (fullname != null) {
			graph.add(vf.createStatement(resourceURI, vf.createURI(NS.foaf, "name"), vf.createLiteral(fullname)));
		}
		if (userInfo.getEmail() != null) {
			graph.add(vf.createStatement(resourceURI, vf.createURI(NS.foaf, "mbox"), vf.createURI("mailto:", userInfo.getEmail())));
		}
		
		entry.getLocalMetadata().setGraph(graph);
	}

}