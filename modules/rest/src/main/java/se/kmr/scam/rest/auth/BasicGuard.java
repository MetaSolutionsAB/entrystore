///**
// * Copyright (c) 2007-2010
// *
// * Licensed under the Apache License, Version 2.0 (the "License");
// * you may not use this file except in compliance with the License.
// * You may obtain a copy of the License at
// *
// *     http://www.apache.org/licenses/LICENSE-2.0
// *
// * Unless required by applicable law or agreed to in writing, software
// * distributed under the License is distributed on an "AS IS" BASIS,
// * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// * See the License for the specific language governing permissions and
// * limitations under the License.
// */
//
//package se.kmr.scam.rest.auth;
//
//import java.net.URI;
//import java.net.URLDecoder;
//import java.util.List;
//
//import org.restlet.Context;
//import org.restlet.Request;
//import org.restlet.Response;
//import org.restlet.data.ChallengeResponse;
//import org.restlet.data.ChallengeScheme;
//import org.restlet.data.Cookie;
//import org.restlet.data.Form;
//import org.restlet.data.Method;
//import org.restlet.data.Status;
//import org.restlet.ext.crypto.DigestAuthenticator;
//import org.restlet.routing.Route;
//import org.restlet.security.Authenticator;
//import org.restlet.security.ChallengeAuthenticator;
//import org.restlet.util.Series;
//import org.slf4j.Logger;
//import org.slf4j.LoggerFactory;
//
//import se.kmr.scam.repository.AuthorizationException;
//import se.kmr.scam.repository.BuiltinType;
//import se.kmr.scam.repository.Entry;
//import se.kmr.scam.repository.PrincipalManager;
//import se.kmr.scam.repository.User;
//import se.kmr.scam.rest.ScamApplication;
//
///**
// * <p>
// * When you need to secure the access to some Restlets, several options are available. 
// * A common way is to rely on cookies to identify clients (or client sessions) and to 
// * check a given user ID or session ID against your application state to determine 
// * if access should be granted. Restlets natively support cookies via the Cookie  
// * and CookieSetting objects accessible from a Request or a Response.
// *</p><p>
// * There is another way based on the standard HTTP authentication mechanism. 
// * The Noelios Restlet Engine currently accepts credentials sent and received 
// * in the Basic HTTP scheme and also the credentials sent in the Amazon Web 
// * Services scheme.
// *</p><p>
// * When receiving a call, developers can use the parsed credentials available in 
// * Request.challengeResponse.identifier/secret via the Guard filter. 
// * Filters are specialized Restlets that can pre-process a call before invoking and 
// * attached Restlet or post-process a call after the attached Restlet returns it. 
// * If you are familiar with the Servlet API, the concept is similar to the 
// * Filter interface.
// *</p>
// * @author Eric Johansson
// * @author Hannes Ebner
// */
//public class BasicGuard extends ChallengeAuthenticator {
//	/** The manager which manage the principlas */
//	private PrincipalManager pm;
//	/** Logs prints */
//	private Logger log = LoggerFactory.getLogger(BasicGuard.class);
//
//	/**
//	 * Constructor
//	 * 
//	 * @param context	Contextual data and services provided to a Restlet.
//	 * @param scheme 	Challenge scheme used to authenticate remote clients.  
//	 * 					The authentication scheme to use.
//	 * @param realm 	The authentication realm.
//	 * @param pm		The manager which manage the principlas
//	 */
//	public BasicGuard(Context context, ChallengeScheme scheme, String realm, PrincipalManager pm) {
//		super(context, scheme, realm);
//		this.pm = pm;
//	}
//
//	/**
//	 * Indicates if the request is authorized to pass through the Guard.
//	 * This method is only called if the call was sucessfully authenticated. 
//	 * It always returns true by default. If specific checks are required, 
//	 * they could be added by overriding this method.
//	 *  
//     * @param request 	The request to authorize. 
//     * @return			True if the request is authorized.
//	 */
//	public boolean authenticate(Request request, Response response) {
//		if (request.getChallengeResponse() == null) { // If no HTTP basic authentication was made...
//			List<String> strs = request.getResourceRef().getSegments();
//			List<String> strs2 = request.getRootRef().getSegments();
//			Form query = request.getResourceRef().getQueryAsForm();
//			if (!strs.get(strs2.size()).equals("login") || // ...and this isn't a request to /login...
//					((query.getFirst("auth_user") != null && // ...or the login is contained elsewhere (i.e., in the query or a cookie)...
//							query.getFirst("auth_password") != null)
//						|| request.getCookies().getFirst("scamSimpleLogin") != null) ||
//					"false".equals(query.getFirstValue("auth_challenge"))) { // ...or a HTTP auth challenge isn't wanted...
//				// ...then, pretend (at least for now) that we're logging in as the guest user
//				request.setChallengeResponse(new ChallengeResponse(ChallengeScheme.HTTP_BASIC, "_guest", "guestpwd"));
//			}
//		}
//		boolean result = super.authenticate(request, response); 
//		
//		return result;
//	}
//
//	/**
//	 * Challenges the client unless the request indicates that a challenge
//	 * should not be made, in which case the request is forbidden instead.
//	 * 
//	 * @see org.restlet.Guard#challenge(org.restlet.data.Response, boolean)
//	 */
//	@Override
//	public void challenge(Response response, boolean stale) {
//		Form query = response.getRequest().getResourceRef().getQueryAsForm();
//		if ("false".equals(query.getFirstValue("auth_challenge")) ||
//				(query.getFirst("auth_user") != null && query.getFirst("auth_password") != null &&
//				!"true".equals(query.getFirstValue("auth_challenge")))) {
//			// Respond with 400 Bad Request since we want to make sure that
//			//  no challenge is shown to the user in any browser
//			response.setStatus(Status.CLIENT_ERROR_BAD_REQUEST);
//		} else {
//			super.challenge(response, stale);
//		}
//	}
//
//	/**
//	 * Indicates if the secret is valid for the given identifier. By default, 
//	 * this returns true given the correct login/password couple as verified 
//	 * via the findSecret() method.
//	 * 
//	 * @param identifier	The username typed in from the person trying to log in.
//	 * @return 				true if the user exists, otherwise false.
//	 */
//	@Override
//	public boolean checkSecret(Request request, String identifier, char[] secret) throws AuthorizationException {	
//		URI userURI = null;
//		try {
//			pm.setAuthenticatedUserURI(pm.getAdminUser().getURI());
//
//			// Check query parameters for user name and password, and if they exist,
//			//  use these for logging in instead of any previous identifier and secret
//			Form query = request.getResourceRef().getQueryAsForm();
//			Series<Cookie> cookies = request.getCookies();
//			if (query.getFirst("auth_user") != null && query.getFirst("auth_password") != null) {
//				identifier = query.getFirstValue("auth_user");
//				secret = query.getFirstValue("auth_password").toCharArray();
//				log.debug("Secret for user found in query parameters: " + identifier);
//			} else if (cookies.getFirst("scamSimpleLogin") != null) {
//				String cookie = URLDecoder.decode(cookies.getFirstValue("scamSimpleLogin"), "UTF-8");
//				int separator = cookie.indexOf(":");
//				if (separator >= 0) {
//					identifier = cookie.substring(0, separator);
//					secret = cookie.substring(separator + 1).toCharArray();
//					log.debug("Secret for user found in cookie: " + identifier);
//				}
//			}
//
//			if (identifier.equals("_guest")) {
//				userURI = pm.getGuestUser().getURI();
//				return true;
//			} else if (super.checkSecret(request, identifier, secret)) {
//				Entry userEntry = pm.getPrincipalEntry(identifier);
//				userURI = userEntry.getResourceURI();
//				return true;
//			}
//			return false;
//		} finally {
//			pm.setAuthenticatedUserURI(userURI);
//		}
//	}
//	
//	/**
//	 * Finds the secret associated to a given identifier. '
//	 * By default it looks up into the secrets map, 
//	 * but this behavior can be overriden like in this case.
//	 * 
//	 * @param identifier	The identifier to lookup. In this case the username.
//	 * @return 				The secret associated to the identifier or null.
//	 */
//	private char[] getSecret(String identifier) {
//		Entry userEntry = pm.getPrincipalEntry(identifier);
//		if (userEntry != null && userEntry.getBuiltinType() == BuiltinType.User) {
//			User user = ((User) userEntry.getResource());
//			if (user.getSecret() != null) {
//				return user.getSecret().toCharArray();
//			} else {
//				log.error("No reference to the users secret.");
//			}
//		}
//
//		return null;
//	}
//
//	/**
//	 * Allows filtering after processing by the next Restlet.
//	 * 
//	 * @param request	The request to handle.
//     * @param response	The response to update.
//	 */
//	protected void afterHandle(Request request, Response response) {
//		pm.setAuthenticatedUserURI(null);
//	}
//	
//	@Override
//	protected int beforeHandle(Request request, Response response) {
//		if (request.getMethod().equals(Method.GET)) {
//			return CONTINUE;
//		} else {
//			ScamApplication scamApp = (ScamApplication) getContext().getAttributes().get(ScamApplication.KEY);
//			boolean lockout = scamApp.getRM().hasModificationLockOut();
//			if (lockout) {
//				response.setStatus(Status.SERVER_ERROR_SERVICE_UNAVAILABLE,
//					"The service is being maintained and does not accept modification requests right now, please check back later");
//				return SKIP;
//			}
//		}
//		return CONTINUE;
//	}
//
//}