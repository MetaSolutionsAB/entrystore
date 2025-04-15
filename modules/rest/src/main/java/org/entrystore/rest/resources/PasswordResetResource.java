/*
 * Copyright (c) 2007-2025 MetaSolutions AB
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

import net.tanesha.recaptcha.ReCaptchaImpl;
import net.tanesha.recaptcha.ReCaptchaResponse;
import org.apache.commons.lang.RandomStringUtils;
import org.entrystore.Entry;
import org.entrystore.PrincipalManager;
import org.entrystore.User;
import org.entrystore.config.Config;
import org.entrystore.repository.config.Settings;
import org.entrystore.repository.security.Password;
import org.entrystore.rest.EntryStoreApplication;
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

import java.net.URI;
import java.net.URLDecoder;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.LocalDateTime;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.restlet.data.Status.CLIENT_ERROR_REQUEST_ENTITY_TOO_LARGE;

/**
 * Supports resetting a user's password. Based on SignupResource.
 *
 * @author Hannes Ebner
 */
public class PasswordResetResource extends BaseResource {

	private static final Logger log = LoggerFactory.getLogger(PasswordResetResource.class);

	private final SimpleHTML html = new SimpleHTML("Password reset");

	@Get
	public Representation represent() throws ResourceException {
		String token = parameters.get("confirm");

		if (token == null) {
			boolean reCaptcha = "on".equalsIgnoreCase(getRM().getConfiguration().getString(Settings.AUTH_RECAPTCHA, "off"));
			return new StringRepresentation(constructHtmlForm(reCaptcha), MediaType.TEXT_HTML, Language.ENGLISH);
		}

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

			Entry userEntry = pm.getPrincipalEntry(ci.getEmail());
			User u;
			if (userEntry != null) {
				log.debug("Loaded user entry via email adress");
				u = (User) userEntry.getResource();
			} else {
				log.debug("Trying to load user entry via external ID");
				u = pm.getUserByExternalID(ci.getEmail());
			}
			if (u == null) {
				getResponse().setStatus(Status.CLIENT_ERROR_NOT_FOUND);
				if (ci.getUrlFailure() != null) {
					getResponse().redirectTemporary(URLDecoder.decode(ci.getUrlFailure(), UTF_8));
					return new EmptyRepresentation();
				} else {
					return html.representation("User with provided email address does not exist.");
				}
			}

			// Reset password
			if (u.setSaltedHashedSecret(ci.getSaltedHashedPassword())) {
				LoginTokenCache loginTokenCache = ((EntryStoreApplication)getApplication()).getLoginTokenCache();
				loginTokenCache.removeTokens(ci.getEmail());
				tc.removeAllTokens();
				log.debug("Removed any authentication tokens belonging to user {}", u.getURI());
				Email.sendPasswordChangeConfirmation(getRM().getConfiguration(), u.getEntry());
				log.info("Reset password for user {}", u.getURI());
			} else {
				log.error("Error when resetting password for user {}", u.getURI());
				getResponse().setStatus(Status.SERVER_ERROR_INTERNAL);
				if (ci.getUrlFailure() != null) {
					getResponse().redirectTemporary(URLDecoder.decode(ci.getUrlFailure(), UTF_8));
					return new EmptyRepresentation();
				} else {
					return html.representation("Unable to reset password due to internal error.");
				}
			}
		} finally {
			pm.setAuthenticatedUserURI(authUser);
		}

		if (ci.getUrlSuccess() != null) {
			getResponse().redirectTemporary(URLDecoder.decode(ci.getUrlSuccess(), UTF_8));
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

		SignupInfo ci = new SignupInfo(getRM());
		ci.setExpirationDate(LocalDateTime.now(Clock.systemDefaultZone()).plusDays(1)); // 24 hours later
		String rcChallenge = null;
		String rcResponse = null;
		String rcResponseV2 = null;
		String password = null;

		if (MediaType.APPLICATION_JSON.equals(r.getMediaType())) {
			try {
				JSONObject siJson = new JSONObject(r.getText());
				if (siJson.has("email")) {
					ci.setEmail(siJson.getString("email"));
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
					ci.setUrlFailure(siJson.getString("urlfailure"));
				}
				if (siJson.has("urlsuccess")) {
					ci.setUrlSuccess(siJson.getString("urlsuccess"));
				}
			} catch (Exception e) {
				getResponse().setStatus(Status.CLIENT_ERROR_BAD_REQUEST);
				return;
			}
		} else {
			Form form = new Form(getRequest().getEntity());
			ci.setEmail(form.getFirstValue("email", true));
			password = form.getFirstValue("password", true);
			rcChallenge = form.getFirstValue("recaptcha_challenge_field", true);
			rcResponse = form.getFirstValue("recaptcha_response_field", true);
			rcResponseV2 = form.getFirstValue("g-recaptcha-response", true);
			ci.setUrlFailure(form.getFirstValue("urlfailure", true));
			ci.setUrlSuccess(form.getFirstValue("urlsuccess", true));
		}

		if (ci.getEmail() == null || password == null) {
			getResponse().setStatus(Status.CLIENT_ERROR_BAD_REQUEST);
			getResponse().setEntity(html.representation("One or more parameters are missing."));
			return;
		}

		if (password.trim().length() < 8) {
			getResponse().setStatus(Status.CLIENT_ERROR_BAD_REQUEST);
			getResponse().setEntity(html.representation("The password has to consist of at least 8 characters."));
			return;
		}

		if (!EmailValidator.getInstance().isValid(ci.getEmail())) {
			getResponse().setStatus(Status.CLIENT_ERROR_BAD_REQUEST);
			getResponse().setEntity(html.representation("Invalid email address: " + ci.getEmail()));
			return;
		}

		Config config = getRM().getConfiguration();

		log.info("Received password reset request for {}", ci.getEmail());

		if ("on".equalsIgnoreCase(config.getString(Settings.AUTH_RECAPTCHA, "off"))
				&& config.getString(Settings.AUTH_RECAPTCHA_PRIVATE_KEY) != null) {
			if ((rcChallenge == null || rcResponse == null) && rcResponseV2 == null) {
				getResponse().setStatus(Status.CLIENT_ERROR_BAD_REQUEST);
				getResponse().setEntity(html.representation("reCaptcha information missing"));
				return;
			}
			log.info("Checking reCaptcha for {}", ci.getEmail());

			String remoteAddr = getRequest().getClientInfo().getUpstreamAddress();
			boolean reCaptchaIsValid;

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
				log.info("Valid reCaptcha for {}", ci.getEmail());
			} else {
				log.info("Invalid reCaptcha for {}", ci.getEmail());
				getResponse().setStatus(Status.CLIENT_ERROR_BAD_REQUEST);
				getResponse().setEntity(html.representation("Invalid reCaptcha received."));
				return;
			}
		}

		PrincipalManager pm = getPM();
		URI authUser = pm.getAuthenticatedUserURI();
		try {
			pm.setAuthenticatedUserURI(pm.getAdminUser().getURI());

			Entry userEntry = pm.getPrincipalEntry(ci.getEmail());
			User u;
			if (userEntry != null) {
				log.debug("Loaded user entry via email adress");
				u = (User) userEntry.getResource();
			} else {
				log.debug("Trying to load user entry via external ID");
				u = pm.getUserByExternalID(ci.getEmail());
			}

			// to avoid spamming etc. we only send emails to users that exist
			if (u != null) {
				if (u.isDisabled()) {
					log.info("User {} is disabled, not allowing password reset", ci.getEmail());
					getResponse().setStatus(Status.CLIENT_ERROR_FORBIDDEN);
					return;
				}

				String token = RandomStringUtils.random(16, 0, 0, true, true, null, new SecureRandom());
				String confirmationLink = getRM().getRepositoryURL().toExternalForm() + "auth/pwreset?confirm=" + token;
				log.info("Generated password reset token for {}", ci.getEmail());

				boolean sendSuccessful = Email.sendPasswordResetConfirmation(getRM().getConfiguration(), ci.getEmail(), confirmationLink);
				if (sendSuccessful) {
					ci.setSaltedHashedPassword(Password.getSaltedHash(password));
					SignupTokenCache.getInstance().putToken(token, ci);
					log.info("Sent confirmation request to {}", ci.getEmail());
				} else {
					log.info("Failed to send confirmation request to {}", ci.getEmail());
					getResponse().setStatus(Status.SERVER_ERROR_INTERNAL);
					return;
				}
			} else {
				log.info("Ignoring password reset attempt for non-existing user {}", ci.getEmail());
			}
		} finally {
			pm.setAuthenticatedUserURI(authUser);
		}

		getResponse().setStatus(Status.SUCCESS_OK);
		getResponse().setEntity(html.representation("A confirmation message was sent to " + ci.getEmail() + " if the user exists."));
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
