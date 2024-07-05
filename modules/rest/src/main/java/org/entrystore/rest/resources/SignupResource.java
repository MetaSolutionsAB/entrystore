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

import com.google.common.base.Joiner;
import net.tanesha.recaptcha.ReCaptchaImpl;
import net.tanesha.recaptcha.ReCaptchaResponse;
import org.apache.commons.lang.RandomStringUtils;
import org.apache.commons.lang3.StringUtils;
import org.entrystore.Context;
import org.entrystore.Entry;
import org.entrystore.GraphType;
import org.entrystore.PrincipalManager;
import org.entrystore.User;
import org.entrystore.config.Config;
import org.entrystore.repository.config.Settings;
import org.entrystore.repository.security.Password;
import org.entrystore.rest.auth.Signup;
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
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import static org.restlet.data.Status.CLIENT_ERROR_REQUEST_ENTITY_TOO_LARGE;

/**
 * Resource to handle manual sign-ups.
 *
 * @author Hannes Ebner
 */
public class SignupResource extends BaseResource {

	private static final Logger log = LoggerFactory.getLogger(SignupResource.class);

	protected SimpleHTML html = new SimpleHTML("Sign-up");

	private static Set<String> domainWhitelist = null;

	private static final Object mutex = new Object();

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
			URL bURL = getRM().getRepositoryURL();
			String appURL = bURL.getProtocol() + "://" + bURL.getHost() + (Arrays.asList(-1, 80, 443).contains(bURL.getPort()) ? "" : ":" + bURL.getPort());
			return html.representation("<h4>Invalid confirmation link.</h4>" +
					"This may be due to one of the following reasons:<br/>" +
					"<ul><li>You have clicked the link twice and you already have an account.</li>" +
					"<li>The confirmation link has expired.</li>" +
					"<li>The link's confirmation token has never existed.</li></ul>" +
					"Click here to sign up again and to receive a new confirmation link:<br/>" +
					"<a href=\"" + appURL + "\"><pre>" + appURL + "</pre></a>");
		}
		tc.removeToken(token);

		PrincipalManager pm = getPM();
		URI authUser = pm.getAuthenticatedUserURI();
		try {
			pm.setAuthenticatedUserURI(pm.getAdminUser().getURI());

			Entry userEntry = pm.getPrincipalEntry(ci.getEmail());
			if ((userEntry != null && GraphType.User.equals(userEntry.getGraphType())) ||
					pm.getUserByExternalID(ci.getEmail()) != null) {
				getResponse().setStatus(Status.CLIENT_ERROR_CONFLICT);
				return html.representation("User with submitted email address exists already.");
			}

			// Create user
			Entry entry = pm.createResource(null, GraphType.User, null, null);
			if (entry == null) {
				if (ci.getUrlFailure() != null) {
					getResponse().redirectTemporary(URLDecoder.decode(ci.getUrlFailure(), StandardCharsets.UTF_8));
					return new EmptyRepresentation();
				} else {
					getResponse().setStatus(Status.SERVER_ERROR_INTERNAL);
				}
				return html.representation("Unable to create user.");
			}

			// Set alias, metadata and password
			pm.setPrincipalName(entry.getResourceURI(), ci.getEmail());
			Signup.setFoafMetadata(entry, new org.restlet.security.User("", "", ci.getFirstName(), ci.getLastName(), ci.getEmail()));
			User u = (User) entry.getResource();
			u.setSaltedHashedSecret(ci.getSaltedHashedPassword());
			if (ci.getCustomProperties() != null) {
				u.setCustomProperties(ci.getCustomProperties());
			}
			log.info("Created user " + u.getURI());

			if ("on".equalsIgnoreCase(getRM().getConfiguration().getString(Settings.SIGNUP_CREATE_HOME_CONTEXT, "off"))) {
				// Create context and set ACL and alias
				Entry homeContext = getCM().createResource(null, GraphType.Context, null, null);
				homeContext.addAllowedPrincipalsFor(PrincipalManager.AccessProperty.Administer, u.getURI());
				getCM().setName(homeContext.getEntryURI(), ci.getEmail());
				log.info("Created context " + homeContext.getResourceURI());

				// Set home context of user
				u.setHomeContext((Context) homeContext.getResource());
				log.info("Set home context of user " + u.getURI() + " to " + homeContext.getResourceURI());
			}
		} finally {
			pm.setAuthenticatedUserURI(authUser);
		}

		if (ci.getUrlSuccess() != null) {
			getResponse().redirectTemporary(URLDecoder.decode(ci.getUrlSuccess(), StandardCharsets.UTF_8));
			return new EmptyRepresentation();
		}
		getResponse().setStatus(Status.SUCCESS_CREATED);
		return html.representation("Sign-up successful.");

	}

	@Post
	public void acceptRepresentation(Representation r) {
		if (HttpUtil.isLargerThan(r, 32768)) {
			log.warn("The size of the representation is larger than 32KB or unknown, request blocked");
			getResponse().setStatus(CLIENT_ERROR_REQUEST_ENTITY_TOO_LARGE);
			return;
		}

		SignupInfo ci = new SignupInfo(getRM());
		ci.setExpirationDate(new Date(new Date().getTime() + (24 * 3600 * 1000))); // 24 hours later
		ci.setCustomProperties(new HashMap<>());
		String rcChallenge = null;
		String rcResponse = null;
		String rcResponseV2 = null;
		String customPropPrefix = "custom_";
		String password = null;

		if (MediaType.APPLICATION_JSON.equals(r.getMediaType())) {
			try {
				JSONObject siJson = new JSONObject(r.getText());
				if (siJson.has("firstname")) {
					ci.setFirstName(siJson.getString("firstname"));
				}
				if (siJson.has("lastname")) {
					ci.setLastName(siJson.getString("lastname"));
				}
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

				// Extract custom properties
				Iterator<String> siJsonKeyIt = siJson.keys();
				while (siJsonKeyIt.hasNext()) {
					String key = (String) siJsonKeyIt.next();
					if (key.startsWith(customPropPrefix) && (key.length() > customPropPrefix.length())) {
						ci.getCustomProperties().put(key.substring(customPropPrefix.length()), siJson.getString(key));
					}
				}
			} catch (Exception e) {
				getResponse().setStatus(Status.CLIENT_ERROR_BAD_REQUEST);
				return;
			}
		} else {
			Form form = new Form(getRequest().getEntity());
			ci.setFirstName(form.getFirstValue("firstname", true));
			ci.setLastName(form.getFirstValue("lastname", true));
			ci.setEmail(form.getFirstValue("email", true));
			password = form.getFirstValue("password", true);
			rcChallenge = form.getFirstValue("recaptcha_challenge_field", true);
			rcResponse = form.getFirstValue("recaptcha_response_field", true);
			rcResponseV2 = form.getFirstValue("g-recaptcha-response", true);
			ci.setUrlFailure(form.getFirstValue("urlfailure", true));
			ci.setUrlSuccess(form.getFirstValue("urlsuccess", true));

			// Extract custom properties
			for (String key : form.getNames()) {
				if (key.startsWith(customPropPrefix) && (key.length() > customPropPrefix.length())) {
					ci.getCustomProperties().put(key.substring(customPropPrefix.length()), form.getFirstValue(key));
				}
			}
		}

		if (ci.getFirstName() == null || ci.getLastName() == null || ci.getEmail() == null || password == null) {
			getResponse().setStatus(Status.CLIENT_ERROR_BAD_REQUEST);
			getResponse().setEntity(html.representation("One or more parameters are missing."));
			return;
		}

		password = password.trim();

		if (isInvalidName(ci.getFirstName()) || isInvalidName(ci.getLastName())) {
			getResponse().setStatus(Status.CLIENT_ERROR_BAD_REQUEST);
			getResponse().setEntity(html.representation("Invalid name."));
			return;
		}

		if (!Password.conformsToRules(password)) {
			getResponse().setStatus(Status.CLIENT_ERROR_BAD_REQUEST);
			getResponse().setEntity(html.representation("The password must conform to the configured rules."));
			return;
		}

		if (!EmailValidator.getInstance().isValid(ci.getEmail())) {
			getResponse().setStatus(Status.CLIENT_ERROR_BAD_REQUEST);
			getResponse().setEntity(html.representation("Invalid email address: " + ci.getEmail()));
			return;
		}

		if (!domainWhitelist.isEmpty()) {
			String emailDomain = ci.getEmail().substring(ci.getEmail().indexOf("@") + 1).toLowerCase();
			if (!domainWhitelist.contains(emailDomain)) {
				getResponse().setStatus(Status.CLIENT_ERROR_EXPECTATION_FAILED);
				getResponse().setEntity(html.representation("The email domain is not allowed for sign-up: " + emailDomain));
				return;
			}
		}

		Config config = getRM().getConfiguration();

		log.info("Received sign-up request for " + ci.getEmail());

		if ("on".equalsIgnoreCase(config.getString(Settings.AUTH_RECAPTCHA, "off"))
				&& config.getString(Settings.AUTH_RECAPTCHA_PRIVATE_KEY) != null) {
			if ((rcChallenge == null || rcResponse == null) && rcResponseV2 == null) {
				getResponse().setStatus(Status.CLIENT_ERROR_BAD_REQUEST);
				getResponse().setEntity(html.representation("reCaptcha information missing"));
				return;
			}
			log.info("Checking reCaptcha for " + ci.getEmail());

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
				log.info("Valid reCaptcha for " + ci.getEmail());
			} else {
				log.info("Invalid reCaptcha for " + ci.getEmail());
				getResponse().setStatus(Status.CLIENT_ERROR_EXPECTATION_FAILED);
				getResponse().setEntity(html.representation("Invalid reCaptcha received."));
				return;
			}
		}

		String token = RandomStringUtils.random(16, 0, 0, true, true, null, new SecureRandom());
		String confirmationLink = getRM().getRepositoryURL().toExternalForm() + "auth/signup?confirm=" + token;
		log.info("Generated sign-up token for " + ci.getEmail());

		boolean sendSuccessful = Email.sendSignupConfirmation(getRM().getConfiguration(), ci.getFirstName() + " " + ci.getLastName(), ci.getEmail(), confirmationLink);
		if (sendSuccessful) {
			ci.setSaltedHashedPassword(Password.getSaltedHash(password));
			SignupTokenCache.getInstance().putToken(token, ci);
			log.info("Sent confirmation request to " + ci.getEmail());
		} else {
			log.info("Failed to send confirmation request to " + ci.getEmail());
			getResponse().setStatus(Status.SERVER_ERROR_INTERNAL);
			return;
		}

		getResponse().setStatus(Status.SUCCESS_OK);
		getResponse().setEntity(html.representation("A confirmation message was sent to " + ci.getEmail()));
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
				// reCaptcha 2.0
				sb.append("<script src=\"https://www.google.com/recaptcha/api.js\" async defer></script>\n");
				sb.append("<p>\n<div class=\"g-recaptcha\" data-sitekey=\"").append(siteKey).append("\"></div>\n</p>\n");
			}
		}
		sb.append("<br/>\n<input type=\"submit\" value=\"Sign-up\" />\n");
		sb.append("</form>\n");
		sb.append(html.footer());

		return sb.toString();
	}

	boolean isInvalidName(String name) {
		// must not be null or too short
		if (name == null || name.length() < 2) {
			return true;
		}
		// must not be a URL (covers mailto: and others with slash)
		if (name.contains(":") || name.contains("/")) {
			return true;
		}
		// must not consist of more than five words (counting spaces in between words)
		return StringUtils.countMatches(name, " ") >= 5;
	}

}