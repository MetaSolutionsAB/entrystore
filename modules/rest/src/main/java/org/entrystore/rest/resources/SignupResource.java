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

import com.google.common.base.Joiner;
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
import org.entrystore.rest.util.RecaptchaVerifier;
import org.entrystore.rest.util.SimpleHTML;
import org.json.JSONException;
import org.json.JSONObject;
import org.restlet.Request;
import org.restlet.Response;
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

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;


/**
 * Resource to handle manual sign-ups.
 * 
 * @author Hannes Ebner
 */
public class SignupResource extends BaseResource {
	
	private static Logger log = LoggerFactory.getLogger(SignupResource.class);

	protected SimpleHTML html = new SimpleHTML("Sign-up");

	private static Set<String> domainWhitelist = null;

	private static Object mutex = new Object();

	@Override
	public void doInit() {
		synchronized (mutex) {
			if (domainWhitelist == null) {
				Config config = getRM().getConfiguration();
				List<String> tmpDomainWhitelist = config.getStringList(Settings.SIGNUP_WHITELIST, new ArrayList<String>());
				domainWhitelist = new HashSet<>();
				// we normalize the list to lower case and to not contain null
				for (String domain : tmpDomainWhitelist) {
					if (domain != null) {
						domainWhitelist.add(domain.toLowerCase());
					}
				}
				if (domainWhitelist.size() > 0) {
					log.info("Sign-up whitelist initialized with following domains: " + Joiner.on(", ").join(domainWhitelist));
				} else {
					log.info("No domains provided for sign-up whitelist; sign-ups for any domain are allowed");
				}
			}
		}
	}

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
						return new EmptyRepresentation();
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
				return new EmptyRepresentation();
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
		SignupInfo ci = new SignupInfo();
		ci.expirationDate = new Date(new Date().getTime() + (24 * 3600 * 1000)); // 24 hours later
		String rcChallenge = null;
		String rcResponse = null;
		String rcResponseV2 = null;

		if (MediaType.APPLICATION_JSON.equals(r.getMediaType())) {
			try {
				JSONObject siJson = new JSONObject(r.getText());
				if (siJson.has("firstname")) {
					ci.firstName = siJson.getString("firstname");
				}
				if (siJson.has("lastname")) {
					ci.lastName = siJson.getString("lastname");
				}
				if (siJson.has("email")) {
					ci.email = siJson.getString("email");
				}
				if (siJson.has("password")) {
					ci.password = siJson.getString("password");
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
			ci.firstName = form.getFirstValue("firstname", true);
			ci.lastName = form.getFirstValue("lastname", true);
			ci.email = form.getFirstValue("email", true);
			ci.password = form.getFirstValue("password", true);
			rcChallenge = form.getFirstValue("recaptcha_challenge_field", true);
			rcResponse = form.getFirstValue("recaptcha_response_field", true);
			rcResponseV2 = form.getFirstValue("g-recaptcha-response", true);
			ci.urlFailure = form.getFirstValue("urlfailure", true);
			ci.urlSuccess = form.getFirstValue("urlsuccess", true);
		}

		if (ci.firstName == null || ci.lastName == null || ci.email == null || ci.password == null) {
			getResponse().setStatus(Status.CLIENT_ERROR_BAD_REQUEST);
			getResponse().setEntity(html.representation("One or more parameters are missing."));
			return;
		}

		if (ci.firstName.trim().length() < 2 || ci.lastName.trim().length() < 2) {
			getResponse().setStatus(Status.CLIENT_ERROR_BAD_REQUEST);
			getResponse().setEntity(html.representation("Invalid name."));
			return;
		}

		if (ci.password.trim().length() < 8) {
			getResponse().setStatus(Status.CLIENT_ERROR_BAD_REQUEST);
			getResponse().setEntity(html.representation("The password has to consist of at least 8 characters."));
			return;
		}

		if (!EmailValidator.getInstance(true).isValid(ci.email)) {
			getResponse().setStatus(Status.CLIENT_ERROR_BAD_REQUEST);
			getResponse().setEntity(html.representation("Invalid email address: " + ci.email));
			return;
		}

		if (domainWhitelist.size() > 0) {
			String emailDomain = ci.email.substring(ci.email.indexOf("@") + 1).toLowerCase();
			if (!domainWhitelist.contains(emailDomain)) {
				getResponse().setStatus(Status.CLIENT_ERROR_EXPECTATION_FAILED);
				getResponse().setEntity(html.representation("The email domain is not allowed for sign-up: " + emailDomain));
				return;
			}
		}

		Config config = getRM().getConfiguration();

		log.info("Received sign-up request for " + ci.email);

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

		String token = RandomStringUtils.randomAlphanumeric(16);
		String confirmationLink = getRM().getRepositoryURL().toExternalForm() + "auth/signup?confirm=" + token;
		log.info("Generated sign-up token " + token + " for " + ci.email);

		boolean sendSuccessful = Signup.sendRequestForConfirmation(getRM().getConfiguration(), ci.firstName + " " + ci.lastName, ci.email, confirmationLink, false);
		if (sendSuccessful) {
			SignupTokenCache.getInstance().addToken(token, ci);
			log.info("Sent confirmation request to " + ci.email);
		} else {
			log.info("Failed to send confirmation request to " + ci.email);
			getResponse().setStatus(Status.SERVER_ERROR_INTERNAL);
			return;
		}

		getResponse().setStatus(Status.SUCCESS_OK);
		getResponse().setEntity(html.representation("A confirmation message was sent to " + ci.email));
	}

	private String constructHtmlForm(boolean reCaptcha) {
		Config config = getRM().getConfiguration();

		StringBuilder sb = new StringBuilder();
		sb.append(html.header());
		sb.append("<form action=\"\" method=\"post\">\n");
		sb.append("First name<br/><input type=\"text\" name=\"firstname\"><br/>\n");
		sb.append("Last name<br/><input type=\"text\" name=\"lastname\"><br/>\n");
		sb.append("E-Mail address<br/><input type=\"text\" name=\"email\"><br/>\n");
		sb.append("Password<br/><input type=\"password\" name=\"password\"><br/>\n");
		if (reCaptcha) {
			String siteKey = config.getString(Settings.AUTH_RECAPTCHA_PUBLIC_KEY);
			if (siteKey == null) {
				log.warn("reCaptcha keys must be configured; rendering form without reCaptcha");
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