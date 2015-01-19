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

package org.entrystore.rest.resources;

import net.tanesha.recaptcha.ReCaptcha;
import net.tanesha.recaptcha.ReCaptchaFactory;
import net.tanesha.recaptcha.ReCaptchaImpl;
import net.tanesha.recaptcha.ReCaptchaResponse;
import org.apache.commons.lang.RandomStringUtils;
import org.apache.commons.validator.routines.EmailValidator;
import org.entrystore.Context;
import org.entrystore.Entry;
import org.entrystore.GraphType;
import org.entrystore.PrincipalManager;
import org.entrystore.User;
import org.entrystore.config.Config;
import org.entrystore.repository.config.Settings;
import org.entrystore.rest.auth.Signup;
import org.entrystore.rest.auth.SignupInfo;
import org.entrystore.rest.auth.SignupTokenCache;
import org.entrystore.rest.util.SimpleHTML;
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
import java.util.Date;


/**
 * Resource to handle manual sign-ups.
 * 
 * @author Hannes Ebner
 */
public class SignupResource extends BaseResource {
	
	private static Logger log = LoggerFactory.getLogger(SignupResource.class);

	protected SimpleHTML html = new SimpleHTML("Sign-up");

	@Get
	public Representation represent() throws ResourceException {
		if (!parameters.containsKey("confirm")) {
			boolean reCaptcha = "on".equalsIgnoreCase(getRM().getConfiguration().getString(Settings.AUTH_RECAPTCHA, "off"));
			return new StringRepresentation(constructHtmlForm(reCaptcha), MediaType.TEXT_HTML, Language.ENGLISH);
		}

		String token = parameters.get("confirm");
		SignupTokenCache tc = SignupTokenCache.getInstance();
		SignupInfo ci = tc.getTokenValue(token);
		if (ci == null) {
			getResponse().setStatus(Status.CLIENT_ERROR_BAD_REQUEST);
			return html.representation("Invalid confirmation token.");
		}
		tc.removeToken(token);

		PrincipalManager pm = getPM();
		URI authUser = pm.getAuthenticatedUserURI();
		try {
			pm.setAuthenticatedUserURI(pm.getAdminUser().getURI());

			Entry userEntry = pm.getPrincipalEntry(ci.email);
			if ((userEntry != null && GraphType.User.equals(userEntry.getGraphType())) ||
					pm.getUserByExternalID(ci.email) != null) {
				getResponse().setStatus(Status.CLIENT_ERROR_CONFLICT);
				return html.representation("User with submitted email address exists already.");
			}

			// Create user
			Entry entry = pm.createResource(null, GraphType.User, null, null);
			if (entry == null) {
				try {
					if (ci.urlFailure != null) {
						getResponse().redirectTemporary(URLDecoder.decode(ci.urlFailure, "UTF-8"));
					} else {
						getResponse().setStatus(Status.SERVER_ERROR_INTERNAL);
					}
					return html.representation("Unable to create user.");
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
			Entry homeContext = getCM().createResource(null, GraphType.Context, null, null);
			homeContext.addAllowedPrincipalsFor(PrincipalManager.AccessProperty.Administer, u.getURI());
			getCM().setName(homeContext.getEntryURI(), ci.email);
			log.info("Created context " + homeContext.getResourceURI());

			// Set home context of user
			u.setHomeContext((Context) homeContext.getResource());
			log.info("Set home context of user " + u.getURI() + " to " + homeContext.getResourceURI());
		} finally {
			pm.setAuthenticatedUserURI(authUser);
		}

		try {
			if (ci.urlSuccess != null) {
				getResponse().redirectTemporary(URLDecoder.decode(ci.urlSuccess, "UTF-8"));
			}
			getResponse().setStatus(Status.SUCCESS_CREATED);
			return html.representation("Sign-up successful.");
		} catch (UnsupportedEncodingException e) {
			log.warn("Unable to decode URL: " + e.getMessage());
		}

		getResponse().setStatus(Status.CLIENT_ERROR_BAD_REQUEST);
		return html.representation("User sign-up failed.");
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
			getResponse().setStatus(Status.CLIENT_ERROR_BAD_REQUEST);
			getResponse().setEntity(html.representation("One or more parameters are missing."));
			return;
		}

		if (firstName.trim().length() < 2 || lastName.trim().length() < 2) {
			getResponse().setStatus(Status.CLIENT_ERROR_BAD_REQUEST);
			getResponse().setEntity(html.representation("Invalid name."));
			return;
		}

		if (password.trim().length() < 8) {
			getResponse().setStatus(Status.CLIENT_ERROR_BAD_REQUEST);
			getResponse().setEntity(html.representation("The password has to consist of at least 8 characters."));
			return;
		}

		if (!EmailValidator.getInstance().isValid(email)) {
			getResponse().setStatus(Status.CLIENT_ERROR_BAD_REQUEST);
			getResponse().setEntity(html.representation("Invalid email address: " + email));
			return;
		}

		Config config = getRM().getConfiguration();

		log.info("Received sign-up request for " + email);

		if ("on".equalsIgnoreCase(config.getString(Settings.AUTH_RECAPTCHA, "off"))
				&& config.getString(Settings.AUTH_RECAPTCHA_PRIVATE_KEY) != null) {
			if (rcChallenge == null || rcResponse == null) {
				getResponse().setStatus(Status.CLIENT_ERROR_BAD_REQUEST);
				getResponse().setEntity(html.representation("reCaptcha information missing"));
				return;
			}
			log.info("Checking reCaptcha for " + email);

			String remoteAddr = getRequest().getClientInfo().getUpstreamAddress();
			ReCaptchaImpl captcha = new ReCaptchaImpl();
			captcha.setPrivateKey(config.getString(Settings.AUTH_RECAPTCHA_PRIVATE_KEY));
			ReCaptchaResponse reCaptchaResponse = captcha.checkAnswer(remoteAddr, rcChallenge, rcResponse);

			if (reCaptchaResponse.isValid()) {
				log.info("Valid reCaptcha for " + email);
			} else {
				log.info("Invalid reCaptcha for " + email);
				getResponse().setStatus(Status.CLIENT_ERROR_BAD_REQUEST);
				getResponse().setEntity(html.representation("Invalid reCaptcha received."));
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
		log.info("Generated sign-up token " + token + " for " + email);

		boolean sendSuccessful = Signup.sendRequestForConfirmation(getRM().getConfiguration(), firstName + " " + lastName, email, confirmationLink, false);
		if (sendSuccessful) {
			log.info("Sent confirmation request to " + email);
		} else {
			log.info("Failed to send confirmation request to " + email);
		}

		getResponse().setStatus(Status.SUCCESS_OK);
		getResponse().setEntity(html.representation("A confirmation message was sent to " + email));
	}

	private String constructHtmlForm(boolean reCaptcha) {
		Config config = getRM().getConfiguration();

		String reCaptchaHtml = null;
		if (reCaptcha) {
			String privateKey = config.getString(Settings.AUTH_RECAPTCHA_PRIVATE_KEY);
			String publicKey = config.getString(Settings.AUTH_RECAPTCHA_PUBLIC_KEY);
			if (privateKey == null || publicKey == null) {
				return "reCaptcha keys must be configured";
			}
			ReCaptcha c = ReCaptchaFactory.newReCaptcha(publicKey, privateKey, false);
			reCaptchaHtml = c.createRecaptchaHtml(null, null);
		}

		StringBuilder sb = new StringBuilder();
		sb.append(html.header());
		sb.append("<form action=\"\" method=\"post\">\n");
		sb.append("First name<br/><input type=\"text\" name=\"firstname\"><br/>\n");
		sb.append("Last name<br/><input type=\"text\" name=\"lastname\"><br/>\n");
		sb.append("E-Mail address<br/><input type=\"text\" name=\"email\"><br/>\n");
		sb.append("Password<br/><input type=\"password\" name=\"password\"><br/>\n");
		if (reCaptcha) {
			sb.append("<br/>\n");
			sb.append(reCaptchaHtml);
			sb.append("\n");
		}
		sb.append("<br/>\n<input type=\"submit\" value=\"Sign-up\" />\n");
		sb.append("</form>\n");
		boolean openid = "on".equalsIgnoreCase(config.getString(Settings.AUTH_OPENID, "off"));
		if (openid) {
			boolean google = "on".equalsIgnoreCase(config.getString(Settings.AUTH_OPENID_GOOGLE, "off"));
			boolean yahoo = "on".equalsIgnoreCase(config.getString(Settings.AUTH_OPENID_YAHOO, "off"));
			if (google || yahoo) {
				sb.append("<br/>\n");
				sb.append("Sign-up with: ");
				if (google) {
					sb.append("<a href=\"openid/google/signup\">Google</a>\n");
				}
				if (yahoo) {
					if (google) {
						sb.append(" | ");
					}
					sb.append("<a href=\"openid/yahoo/signup\">Yahoo!</a>\n");
				}
			}
		}
		sb.append(html.footer());
		return sb.toString();
	}

}