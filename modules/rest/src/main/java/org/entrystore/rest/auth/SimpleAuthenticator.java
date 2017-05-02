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