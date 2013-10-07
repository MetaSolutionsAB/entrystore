package se.kmr.scam.rest.auth;

import org.entrystore.repository.PrincipalManager;
import org.restlet.Context;
import org.restlet.Request;
import org.restlet.Response;
import org.restlet.data.ChallengeScheme;
import org.restlet.security.ChallengeAuthenticator;
import org.restlet.security.Verifier;


/**
 * Overrides afterHandle() to make sure no user is set after execution.
 * 
 * @author Hannes Ebner
 */
public class SimpleAuthenticator extends ChallengeAuthenticator {
	
	PrincipalManager pm;

	public SimpleAuthenticator(Context context, boolean optional, ChallengeScheme challengeScheme, String realm, PrincipalManager pm) {
		super(context, optional, challengeScheme, realm);
		this.pm = pm;
	}
	
	public SimpleAuthenticator(Context context, boolean optional, ChallengeScheme challengeScheme, String realm, Verifier verifier, PrincipalManager pm) {
		super(context, optional, challengeScheme, realm, verifier);
		this.pm = pm;
	}
	
	public SimpleAuthenticator(Context context, ChallengeScheme challengeScheme, String realm, PrincipalManager pm) {
		super(context, challengeScheme, realm);
		this.pm = pm;
	}
	
	@Override
	public void afterHandle(Request request, Response response) {
		pm.setAuthenticatedUserURI(null);
	}

}