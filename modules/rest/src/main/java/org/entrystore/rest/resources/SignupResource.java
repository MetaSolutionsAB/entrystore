/*
 * Copyright (c) 2007-2014
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

import net.tanesha.recaptcha.ReCaptcha;
import net.tanesha.recaptcha.ReCaptchaFactory;
import net.tanesha.recaptcha.ReCaptchaImpl;
import net.tanesha.recaptcha.ReCaptchaResponse;
import org.apache.commons.lang.RandomStringUtils;
import org.apache.commons.validator.routines.EmailValidator;
import org.entrystore.repository.Entry;
import org.entrystore.repository.PrincipalManager;
import org.entrystore.repository.ResourceType;
import org.entrystore.repository.User;
import org.entrystore.repository.config.Config;
import org.entrystore.repository.config.Settings;
import org.entrystore.repository.security.Password;
import org.entrystore.rest.auth.SignupInfo;
import org.entrystore.rest.auth.Signup;
import org.entrystore.rest.auth.SignupTokenCache;
import org.entrystore.rest.auth.TokenCache;
import org.restlet.data.ClientInfo;
import org.restlet.data.Form;
import org.restlet.data.Language;
import org.restlet.data.MediaType;
import org.restlet.data.Status;
import org.restlet.representation.Representation;
import org.restlet.representation.StringRepresentation;
import org.restlet.resource.Get;
import org.restlet.resource.Post;
import org.restlet.resource.ResourceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.Date;
import java.util.UUID;


/**
 * Resource to handle manual signups.
 * 
 * @author Hannes Ebner
 */
public class SignupResource extends BaseResource {
	
	private static Logger log = LoggerFactory.getLogger(SignupResource.class);

	@Get
	public Representation represent() throws ResourceException {
		if (!parameters.containsKey("confirm")) {
			return new StringRepresentation(constructHtmlForm(), MediaType.TEXT_HTML, Language.ENGLISH);
		}

		String token = parameters.get("confirm");
		TokenCache tc = SignupTokenCache.getInstance();
		SignupInfo ci = SignupTokenCache.getInstance().getTokenValue(token);
		if (ci == null) {
			getResponse().setStatus(Status.CLIENT_ERROR_BAD_REQUEST, "Invalid confirmation token");
			return null;
		}
		tc.removeToken(token);

		PrincipalManager pm = getPM();
		URI authUser = pm.getAuthenticatedUserURI();
		try {
			pm.setAuthenticatedUserURI(pm.getAdminUser().getURI());

			Entry userEntry = pm.getPrincipalEntry(ci.email);
			if ((userEntry != null && ResourceType.User.equals(userEntry.getResourceType())) ||
					pm.getUserByExternalID(ci.email) != null) {
				getResponse().setStatus(Status.CLIENT_ERROR_CONFLICT, "User with submitted email address exists already");
				return null;
			}

			// Create user
			Entry entry = pm.createResource(null, ResourceType.User, null, null);
			if (entry == null) {
				try {
					if (ci.urlFailure != null) {
						getResponse().redirectTemporary(URLDecoder.decode(ci.urlFailure, "UTF-8"));
					} else {
						getResponse().setStatus(Status.SERVER_ERROR_INTERNAL, "Unable to create user");
					}
					return null;
				} catch (UnsupportedEncodingException e) {
					log.warn("Unable to decode URL: " + e.getMessage());
				}
			}

			// Set alias, metadata and password
			pm.setPrincipalName(entry.getResourceURI(), ci.email);
			Signup.setFoafMetadata(entry, new org.restlet.security.User("", "", ci.firstName, ci.lastName, ci.email));
			User u = (User) entry.getResource();
			u.setSecret(ci.password);
			log.info("Created user " + u.getURI());

			// Create context and set ACL and alias
			Entry homeContext = getCM().createResource(null, ResourceType.Context, null, null);
			homeContext.addAllowedPrincipalsFor(PrincipalManager.AccessProperty.Administer, u.getURI());
			getCM().setContextAlias(homeContext.getEntryURI(), ci.email);
			log.info("Created context " + homeContext.getResourceURI());

			// Set home context of user
			u.setHomeContext((org.entrystore.repository.Context) homeContext.getResource());
			log.info("Set home context of user " + u.getURI() + " to " + homeContext.getResourceURI());
		} finally {
			pm.setAuthenticatedUserURI(authUser);
		}

		try {
			if (ci.urlSuccess != null) {
				getResponse().redirectTemporary(URLDecoder.decode(ci.urlSuccess, "UTF-8"));
			}
			return new StringRepresentation("Signup successful");
		} catch (UnsupportedEncodingException e) {
			log.warn("Unable to decode URL: " + e.getMessage());
		}

		getResponse().setStatus(Status.CLIENT_ERROR_BAD_REQUEST);
		return new StringRepresentation("User confirmation failed");
	}

	@Post
	public void acceptRepresentation(Representation r) {
		Form form = new Form(getRequest().getEntity());
		String firstName = form.getFirstValue("firstname", true);
		String lastName = form.getFirstValue("lastname", true);
		String email = form.getFirstValue("email", true);
		String password = form.getFirstValue("password", true);
		String rcChallenge = form.getFirstValue("recaptcha_challenge_field", true);
		String rcResponse = form.getFirstValue("recaptcha_response_field", true);
		String urlFailure = form.getFirstValue("urlfailure", true);
		String urlSuccess = form.getFirstValue("urlsuccess", true);

		if (firstName == null || lastName == null || email == null || password == null) {
			getResponse().setStatus(Status.CLIENT_ERROR_BAD_REQUEST, "One or more parameters are missing");
			return;
		}

		if (firstName.trim().length() < 2 || lastName.trim().length() < 2) {
			getResponse().setStatus(Status.CLIENT_ERROR_BAD_REQUEST, "Invalid name");
			return;
		}

		if (password.trim().length() < 8) {
			getResponse().setStatus(Status.CLIENT_ERROR_BAD_REQUEST, "Password too short");
			return;
		}

		if (!EmailValidator.getInstance().isValid(email)) {
			getResponse().setStatus(Status.CLIENT_ERROR_BAD_REQUEST, "Invalid e-mail address " + email);
			return;
		}

		Config config = getRM().getConfiguration();

		log.info("Received signup request for " + email);

		if ("on".equalsIgnoreCase(config.getString(Settings.SIGNUP_RECAPTCHA, "off"))
				&& config.getString(Settings.SIGNUP_RECAPTCHA_PRIVATE_KEY) != null) {
			if (rcChallenge == null || rcResponse == null) {
				getResponse().setStatus(Status.CLIENT_ERROR_BAD_REQUEST, "reCaptcha information missing");
				return;
			}
			log.info("Checking reCaptcha for " + email);

			String remoteAddr = getRequest().getClientInfo().getUpstreamAddress();
			ReCaptchaImpl captcha = new ReCaptchaImpl();
			captcha.setPrivateKey(config.getString(Settings.SIGNUP_RECAPTCHA_PRIVATE_KEY));
			ReCaptchaResponse reCaptchaResponse = captcha.checkAnswer(remoteAddr, rcChallenge, rcResponse);

			// FIXME can we set a timeout here?

			if (reCaptchaResponse.isValid()) {
				log.info("Valid reCaptcha for " + email);
			} else {
				log.info("Invalid reCaptcha for " + email);
				getResponse().setStatus(Status.CLIENT_ERROR_BAD_REQUEST, "Invalid reCaptcha");
				return;
			}
		}

		SignupInfo ci = new SignupInfo();
		ci.firstName = firstName;
		ci.lastName = lastName;
		ci.email = email;
		ci.password = password;
		ci.urlFailure = urlFailure;
		ci.urlSuccess = urlSuccess;
		ci.expirationDate = new Date(new Date().getTime() + (24 * 3600 * 1000)); // 24 hours later

		String token = RandomStringUtils.randomAlphanumeric(16);
		String confirmationLink = getRM().getRepositoryURL().toExternalForm() + "auth/signup?confirm=" + token;
		SignupTokenCache.getInstance().addToken(token, ci);
		log.info("Generated signup token " + token + " for " + email);

		boolean sendSuccessful = Signup.sendRequestForConfirmation(getRM().getConfiguration(), email, confirmationLink);
		if (sendSuccessful) {
			log.info("Sent confirmation request to " + email);
		} else {
			log.info("Failed to send confirmation request to " + email);
		}

		getResponse().setStatus(Status.SUCCESS_OK);
		getResponse().setEntity(new StringRepresentation("A confirmation message has been sent"));
	}

	private String constructHtmlForm() {
		Config config = getRM().getConfiguration();
		String privateKey = config.getString(Settings.SIGNUP_RECAPTCHA_PRIVATE_KEY);
		String publicKey = config.getString(Settings.SIGNUP_RECAPTCHA_PUBLIC_KEY);

		if (privateKey == null || publicKey == null) {
			return "reCaptcha keys must be configured";
		}
		ReCaptcha c = ReCaptchaFactory.newReCaptcha(publicKey, privateKey, false);

		StringBuilder sb = new StringBuilder();
		sb.append("<html>");
		sb.append("<head><title>EntryStore signup</title></head>");
		sb.append("<body>");
		sb.append("<form action=\"\" method=\"post\">");
		sb.append("First name<br/><input type=\"text\" name=\"firstname\"><br/>");
		sb.append("Last name<br/><input type=\"text\" name=\"lastname\"><br/>");
		sb.append("E-Mail address<br/><input type=\"text\" name=\"email\"><br/>");
		sb.append("Password<br/><input type=\"text\" name=\"password\"><br/>");
		sb.append(c.createRecaptchaHtml(null, null));
		sb.append("<input type=\"submit\" value=\"Submit\" />");
		sb.append("</form>");
		sb.append("</body>");
		sb.append("</html>");

		return sb.toString();
	}

}