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

package org.entrystore.rest.resources;

import static org.restlet.data.Status.CLIENT_ERROR_REQUEST_ENTITY_TOO_LARGE;

import net.tanesha.recaptcha.ReCaptchaImpl;
import net.tanesha.recaptcha.ReCaptchaResponse;
import org.apache.commons.lang.RandomStringUtils;
import org.entrystore.Entry;
import org.entrystore.PrincipalManager;
import org.entrystore.User;
import org.entrystore.config.Config;
import org.entrystore.repository.config.Settings;
import org.entrystore.repository.security.Password;
import org.entrystore.rest.auth.LoginTokenCache;
import org.entrystore.rest.auth.SignupInfo;
import org.entrystore.rest.auth.SignupTokenCache;
import org.entrystore.rest.util.Email;
import org.entrystore.rest.util.EmailValidator;
import org.entrystore.rest.util.HttpUtil;
import org.entrystore.rest.util.RecaptchaVerifier;
import org.entrystore.rest.util.SimpleHTML;
import org.json.JSONObject;
import org.restlet.data.Form;
import org.restlet.data.Language;
import org.restlet.data.MediaType;
import org.restlet.data.Status;
import org.restlet.representation.EmptyRepresentation;
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
import java.security.SecureRandom;
import java.util.Date;

/**
 * Supports resetting a user's password. Based on SignupResource.
 *
 * @author Hannes Ebner
 */
public class PasswordResetResource extends BaseResource {

	private static Logger log = LoggerFactory.getLogger(PasswordResetResource.class);

	private SimpleHTML html = new SimpleHTML("Password reset");

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
			return html.representation("The confirmation token is invalid or has been used already.");
		}
		tc.removeToken(token);

		PrincipalManager pm = getPM();
		URI authUser = pm.getAuthenticatedUserURI();
		try {
			pm.setAuthenticatedUserURI(pm.getAdminUser().getURI());

			Entry userEntry = pm.getPrincipalEntry(ci.email);
			User u = null;
			if (userEntry != null) {
				log.debug("Loaded user entry via email adress");
				u = (User) userEntry.getResource();
			} else {
				log.debug("Trying to load user entry via external ID");
				u = pm.getUserByExternalID(ci.email);
			}
			if (u == null) {
				getResponse().setStatus(Status.CLIENT_ERROR_NOT_FOUND);
				if (ci.urlFailure != null) {
					try {
						getResponse().redirectTemporary(URLDecoder.decode(ci.urlFailure, "UTF-8"));
						return new EmptyRepresentation();
					} catch (UnsupportedEncodingException use) {
						log.warn("Unable to decode URL: " + use.getMessage());
					}
					return new EmptyRepresentation();
				} else {
					return html.representation("User with provided email address does not exist.");
				}
			}

			// Reset password
			if (u.setSaltedHashedSecret(ci.saltedHashedPassword)) {
				LoginTokenCache.getInstance().removeTokens(ci.email);
				log.debug("Removed any authentication tokens belonging to user " + u.getURI());
				Email.sendPasswordChangeConfirmation(getRM().getConfiguration(), u.getEntry());
				log.info("Reset password for user " + u.getURI());
			} else {
				log.error("Error when resetting password for user " + u.getURI());
				getResponse().setStatus(Status.SERVER_ERROR_INTERNAL);
				if (ci.urlFailure != null) {
					try {
						getResponse().redirectTemporary(URLDecoder.decode(ci.urlFailure, "UTF-8"));
						return new EmptyRepresentation();
					} catch (UnsupportedEncodingException use) {
						log.warn("Unable to decode URL: " + use.getMessage());
					}
					return new EmptyRepresentation();
				} else {
					return html.representation("Unable to reset password due to internal error.");
				}
			}
		} finally {
			pm.setAuthenticatedUserURI(authUser);
		}

		if (ci.urlSuccess != null) {
			try {
				getResponse().redirectTemporary(URLDecoder.decode(ci.urlSuccess, "UTF-8"));
				return new EmptyRepresentation();
			} catch (UnsupportedEncodingException e) {
				log.warn("Unable to decode URL: " + e.getMessage());
			}
			return new EmptyRepresentation();
		} else {
			return html.representation("Password reset was successful.");
		}
	}

	@Post
	public void acceptRepresentation(Representation r) {
		if (HttpUtil.isLargerThan(r, 32768)) {
			log.warn("The size of the representation is larger than 32KB or unknown, request blocked");
			getResponse().setStatus(CLIENT_ERROR_REQUEST_ENTITY_TOO_LARGE);
			return;
		}

		SignupInfo ci = new SignupInfo();
		ci.expirationDate = new Date(new Date().getTime() + (24 * 3600 * 1000)); // 24 hours later
		String rcChallenge = null;
		String rcResponse = null;
		String rcResponseV2 = null;
		String password = null;

		if (MediaType.APPLICATION_JSON.equals(r.getMediaType())) {
			try {
				JSONObject siJson = new JSONObject(r.getText());
				if (siJson.has("email")) {
					ci.email = siJson.getString("email");
				}
				if (siJson.has("password")) {
					password = siJson.getString("password");
				}
				if (siJson.has("recaptcha_challenge_field")) {
					rcChallenge = siJson.getString("recaptcha_challenge_field");
				}
				if (siJson.has("recaptcha_response_field")) {
					rcResponse = siJson.getString("recaptcha_response_field");
				}
				if (siJson.has("grecaptcharesponse")) {
					rcResponseV2 = siJson.getString("grecaptcharesponse");
				}
				if (siJson.has("urlfailure")) {
					ci.urlFailure = siJson.getString("urlfailure");
				}
				if (siJson.has("urlsuccess")) {
					ci.urlSuccess = siJson.getString("urlsuccess");
				}
			} catch (Exception e) {
				getResponse().setStatus(Status.CLIENT_ERROR_BAD_REQUEST);
				return;
			}
		} else {
			Form form = new Form(getRequest().getEntity());
			ci.email = form.getFirstValue("email", true);
			password = form.getFirstValue("password", true);
			rcChallenge = form.getFirstValue("recaptcha_challenge_field", true);
			rcResponse = form.getFirstValue("recaptcha_response_field", true);
			rcResponseV2 = form.getFirstValue("g-recaptcha-response", true);
			ci.urlFailure = form.getFirstValue("urlfailure", true);
			ci.urlSuccess = form.getFirstValue("urlsuccess", true);
		}

		if (ci.email == null || password == null) {
			getResponse().setStatus(Status.CLIENT_ERROR_BAD_REQUEST);
			getResponse().setEntity(html.representation("One or more parameters are missing."));
			return;
		}

		// we have to store it in lower case only to avoid problems with different cases in
		// different steps of the process (if the user provides inconsistent information)
		ci.email = ci.email.toLowerCase();

		if (password.trim().length() < 8) {
			getResponse().setStatus(Status.CLIENT_ERROR_BAD_REQUEST);
			getResponse().setEntity(html.representation("The password has to consist of at least 8 characters."));
			return;
		}

		if (!EmailValidator.getInstance().isValid(ci.email)) {
			getResponse().setStatus(Status.CLIENT_ERROR_BAD_REQUEST);
			getResponse().setEntity(html.representation("Invalid email address: " + ci.email));
			return;
		}

		Config config = getRM().getConfiguration();

		log.info("Received password reset request for " + ci.email);

		if ("on".equalsIgnoreCase(config.getString(Settings.AUTH_RECAPTCHA, "off"))
				&& config.getString(Settings.AUTH_RECAPTCHA_PRIVATE_KEY) != null) {
			if ((rcChallenge == null || rcResponse == null) && rcResponseV2 == null) {
				getResponse().setStatus(Status.CLIENT_ERROR_BAD_REQUEST);
				getResponse().setEntity(html.representation("reCaptcha information missing"));
				return;
			}
			log.info("Checking reCaptcha for " + ci.email);

			String remoteAddr = getRequest().getClientInfo().getUpstreamAddress();
			boolean reCaptchaIsValid = false;

			if (rcResponseV2 != null) {
				RecaptchaVerifier rcVerifier = new RecaptchaVerifier(config.getString(Settings.AUTH_RECAPTCHA_PRIVATE_KEY));
				reCaptchaIsValid = rcVerifier.verify(rcResponseV2, remoteAddr);
			} else {
				ReCaptchaImpl captcha = new ReCaptchaImpl();
				captcha.setPrivateKey(config.getString(Settings.AUTH_RECAPTCHA_PRIVATE_KEY));
				ReCaptchaResponse reCaptchaResponse = captcha.checkAnswer(remoteAddr, rcChallenge, rcResponse);
				reCaptchaIsValid = reCaptchaResponse.isValid();
			}

			if (reCaptchaIsValid) {
				log.info("Valid reCaptcha for " + ci.email);
			} else {
				log.info("Invalid reCaptcha for " + ci.email);
				getResponse().setStatus(Status.CLIENT_ERROR_BAD_REQUEST);
				getResponse().setEntity(html.representation("Invalid reCaptcha received."));
				return;
			}
		}

		PrincipalManager pm = getPM();
		URI authUser = pm.getAuthenticatedUserURI();
		try {
			pm.setAuthenticatedUserURI(pm.getAdminUser().getURI());

			Entry userEntry = pm.getPrincipalEntry(ci.email);
			User u = null;
			if (userEntry != null) {
				log.debug("Loaded user entry via email adress");
				u = (User) userEntry.getResource();
			} else {
				log.debug("Trying to load user entry via external ID");
				u = pm.getUserByExternalID(ci.email);
			}

			// to avoid spamming etc we only send emails to users that exist
			if (u != null) {
				String token = RandomStringUtils.random(16, 0, 0, true, true, null, new SecureRandom());
				String confirmationLink = getRM().getRepositoryURL().toExternalForm() + "auth/pwreset?confirm=" + token;
				log.info("Generated password reset token for " + ci.email);

				boolean sendSuccessful = Email.sendPasswordResetConfirmation(getRM().getConfiguration(), ci.email, confirmationLink);
				if (sendSuccessful) {
					ci.saltedHashedPassword = Password.getSaltedHash(password);
					SignupTokenCache.getInstance().putToken(token, ci);
					log.info("Sent confirmation request to " + ci.email);
				} else {
					log.info("Failed to send confirmation request to " + ci.email);
					getResponse().setStatus(Status.SERVER_ERROR_INTERNAL);
					return;
				}
			} else {
				log.info("Ignoring password reset attempt for non-existing user " + ci.email);
			}
		} finally {
			pm.setAuthenticatedUserURI(authUser);
		}

		getResponse().setStatus(Status.SUCCESS_OK);
		getResponse().setEntity(html.representation("A confirmation message was sent to " + ci.email + " if the user exists."));
	}

	private String constructHtmlForm(boolean reCaptcha) {
		Config config = getRM().getConfiguration();

		StringBuilder sb = new StringBuilder();
		sb.append(html.header());
		sb.append("<form action=\"\" method=\"post\">\n");
		sb.append("E-Mail address<br/><input type=\"text\" name=\"email\"><br/>\n");
		sb.append("New password<br/><input type=\"password\" name=\"password\"><br/>\n");
		if (reCaptcha) {
			String siteKey = config.getString(Settings.AUTH_RECAPTCHA_PUBLIC_KEY);
			if (siteKey == null) {
				log.warn("reCaptcha site key must be configured; rendering form without reCaptcha");
			} else {
				/* reCaptcha 1.0 (deprecated)
				String publicKey = config.getString(Settings.AUTH_RECAPTCHA_PUBLIC_KEY);
				ReCaptcha c = ReCaptchaFactory.newReCaptcha(publicKey, privateKey, false);
				reCaptchaHtml = c.createRecaptchaHtml(null, null);
				*/

				// reCaptcha 2.0
				sb.append("<script src=\"https://www.google.com/recaptcha/api.js\" async defer></script>\n");
				sb.append("<p>\n<div class=\"g-recaptcha\" data-sitekey=\"").append(siteKey).append("\"></div>\n</p>\n");
			}
		}
		sb.append("<br/>\n<input type=\"submit\" value=\"Reset password\" />\n");
		sb.append("</form>\n");
		sb.append(html.footer());
		return sb.toString();
	}

}
