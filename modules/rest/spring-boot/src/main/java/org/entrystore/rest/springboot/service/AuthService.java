/*
 * Copyright (c) 2007-2026 MetaSolutions AB
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

package org.entrystore.rest.springboot.service;

import com.google.common.base.Joiner;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.RandomStringUtils;
import org.apache.commons.lang3.StringUtils;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Model;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.entrystore.Context;
import org.entrystore.ContextManager;
import org.entrystore.Entry;
import org.entrystore.GraphType;
import org.entrystore.PrincipalManager;
import org.entrystore.User;
import org.entrystore.config.Config;
import org.entrystore.impl.RepositoryManagerImpl;
import org.entrystore.repository.config.Settings;
import org.entrystore.repository.security.Password;
import org.entrystore.repository.util.NS;
import org.entrystore.rest.springboot.model.api.PwResetRequestBody;
import org.entrystore.rest.springboot.model.api.SignupRequestBody;
import org.entrystore.rest.springboot.model.auth.SignupInfo;
import org.entrystore.rest.springboot.model.exception.BadRequestHtmlException;
import org.entrystore.rest.springboot.model.exception.DataConflictHtmlException;
import org.entrystore.rest.springboot.model.exception.ExpectationFailedHtmlException;
import org.entrystore.rest.springboot.model.exception.ForbiddenException;
import org.entrystore.rest.springboot.model.exception.InternalServerErrorException;
import org.entrystore.rest.springboot.model.exception.PwResetEntityNotFoundHtmlException;
import org.entrystore.rest.springboot.model.exception.RedirectTemporaryException;
import org.entrystore.rest.springboot.service.auth.EmailValidator;
import org.entrystore.rest.springboot.service.auth.RecaptchaVerifier;
import org.entrystore.rest.springboot.service.auth.RedirectUrlValidator;
import org.entrystore.rest.springboot.service.auth.SignupRateLimiter;
import org.entrystore.rest.springboot.service.auth.SignupTokenCache;
import org.entrystore.rest.springboot.util.Email;
import org.entrystore.rest.springboot.util.HttpUtil;
import org.springframework.security.core.session.SessionInformation;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.security.SecureRandom;
import java.util.function.Consumer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

	private static final int TTL = 24 * 3600 * 1000;

	private static final String POST_SUCCESS_MESSAGE = "A confirmation message was sent to {}, if the user exists.";
	private static final String CONFIRM_PASSWORD_RESET_SUCCESS_MESSAGE = "Password reset was successful.";
	private static final String CONFIRM_SIGNUP_SUCCESS_MESSAGE = "Sign-up successful.";
	private static final String PARAMETERS_MISSING_MESSAGE = "One or more parameters are missing.";
	private static final String SHORT_PASSWORD_MESSAGE = "The password has to consist of at least 8 characters.";
	private static final String BAD_PASSWORD_FORMAT_MESSAGE = "The password must conform to the configured rules.";
	private static final String INVALID_EMAIL_MESSAGE = "Invalid email address: {}.";
	private static final String INVALID_NAME_MESSAGE = "Invalid name.";
	private static final String RECAPTCHA_MISSING_MESSAGE = "reCaptcha information missing.";
	private static final String RECAPTCHA_INVALID_MESSAGE = "Invalid reCaptcha received.";
	private static final String FAILED_TO_SEND_EMAIL_MESSAGE = "Failed to send confirmation request to {}.";
	private static final String INVALID_TOKEN_MESSAGE = "The confirmation token is invalid or has been used already.";
	private static final String USER_NOT_FOUND_MESSAGE = "User with provided email address does not exist.";
	private static final String USER_ALREADY_EXISTS_MESSAGE = "User with submitted email address exists already.";
	private static final String INTERNAL_ERROR_MESSAGE = "Unable to reset password due to internal error.";
	private static final String DOMAIN_NOT_WHITELISTED_MESSAGE = "The email domain is not allowed for sign-up: {}";
	private static final String INVALID_SIGNUP_TOKEN_MESSAGE = "Invalid confirmation link. " +
			"This may be because you already clicked the link and have an account, " +
			"the confirmation link has expired, or the token never existed. " +
			"Visit the link below to sign up again and receive a new confirmation link.";
	private static final String UNABLE_TO_CREATE_USER_MESSAGE = "Unable to create user.";

	private final RepositoryManagerImpl repositoryManager;
	private final PrincipalManager principalManager;
	private final ContextManager contextManager;
	private final RecaptchaVerifier rcVerifier;
	private final SignupTokenCache signupTokenCache;
	private final RedirectUrlValidator redirectUrlValidator;
	private final Config config;
	private final SessionRegistry sessionRegistry;
	private final SignupRateLimiter signupRateLimiter;

	private static final Object mutex = new Object();
	private static Set<String> domainWhitelist = null;

	@PostConstruct
	public void init() {
		synchronized (mutex) {
			if (domainWhitelist == null) {
				List<String> tmpDomainWhitelist = config.getStringList(Settings.SIGNUP_WHITELIST, new ArrayList<>());
				domainWhitelist = new HashSet<>();
				// we normalize the list to lower case and to not contain null
				for (String domain : tmpDomainWhitelist) {
					if (domain != null) {
						domainWhitelist.add(domain.toLowerCase());
					}
				}
				if (!domainWhitelist.isEmpty()) {
					log.info("Sign-up whitelist initialized with following domains: {}", Joiner.on(", ").join(domainWhitelist));
				} else {
					log.info("No domains provided for sign-up whitelist; sign-ups for any domain are allowed");
				}
			}
		}
	}

	public List<SessionInformation> getAllUserSessions(URI userURI, boolean includeExpiredSessions) {

		List<SessionInformation> sessionsList = new ArrayList<>();

		User user = principalManager.getUser(userURI);
		if (user == null) {
			log.warn("No user found for URI: {}", userURI);
			return sessionsList;
		}

		Entry entry = user.getEntry();
		if (entry == null) {
			log.warn("No entry found for user: {}", userURI);
			return sessionsList;
		}

		URI resourceURI = entry.getResourceURI();
		if (resourceURI == null) {
			log.warn("No resource URI found for user entry: {}", userURI);
			return sessionsList;
		}

		String username = resourceURI.toString();
		for (Object principal : sessionRegistry.getAllPrincipals()) {
			if (principal instanceof UserDetails userDetails && userDetails.getUsername().equals(username)) {
				sessionsList = sessionRegistry.getAllSessions(userDetails, includeExpiredSessions);
				break;
			}
		}

		return sessionsList;
	}

	public String confirmPassword(String token, String title) {
		SignupInfo ci = signupTokenCache.getTokenValue(token);
		if (ci == null) {
			throw new BadRequestHtmlException(INVALID_TOKEN_MESSAGE, title);
		}
		signupTokenCache.removeToken(token);

		URI authUser = principalManager.getAuthenticatedUserURI();
		try {
			principalManager.setAuthenticatedUserURI(principalManager.getAdminUser().getURI());

			Entry userEntry = principalManager.getPrincipalEntry(ci.getEmail());
			User u;
			if (userEntry != null) {
				log.debug("Loaded user entry via email adress");
				u = (User) userEntry.getResource();
			} else {
				log.debug("Trying to load user entry via external ID");
				u = principalManager.getUserByExternalID(ci.getEmail());
			}
			if (u == null) {
				if (ci.getUrlFailure() != null) {
					handleUrlRedirect(ci.getUrlFailure());
				} else {
					throw new PwResetEntityNotFoundHtmlException(USER_NOT_FOUND_MESSAGE, title);
				}
			} else {
				// Reset password
				if (u.setSaltedHashedSecret(ci.getSaltedHashedPassword())) {
					signupTokenCache.removeAllTokens(ci.getEmail());
					log.debug("Removed any authentication tokens belonging to user {}", u.getURI());

					List<Object> allPrincipals = sessionRegistry.getAllPrincipals();
					for (Object principal : allPrincipals) {
						if (principal instanceof UserDetails user && user.getUsername().equals(u.getEntry().getResourceURI().toString())) {
							for (SessionInformation session : sessionRegistry.getAllSessions(principal, false)) {
								session.expireNow();
							}
						}
					}
					Email.sendPasswordChangeConfirmation(config, u.getEntry());
					log.info("Reset password for user {}", u.getURI());
				} else {
					log.error("Error when resetting password for user {}", u.getURI());
					if (ci.getUrlFailure() != null) {
						handleUrlRedirect(ci.getUrlFailure());
					} else {
						throw new InternalServerErrorException(INTERNAL_ERROR_MESSAGE);
					}
				}
			}
		} finally {
			principalManager.setAuthenticatedUserURI(authUser);
		}

		if (ci.getUrlSuccess() != null) {
			handleUrlRedirect(ci.getUrlSuccess());
		}

		return CONFIRM_PASSWORD_RESET_SUCCESS_MESSAGE;
	}

	public String pwReset(HttpServletRequest request, PwResetRequestBody requestBody, String title) {
		SignupInfo ci = new SignupInfo();
		ci.setExpirationDate(new Date(new Date().getTime() + TTL)); // 24 hours later

		String rcResponseV2;
		String password;

		if (StringUtils.isNotEmpty(requestBody.email())) {
			ci.setEmail(requestBody.email());
		} else {
			throw new BadRequestHtmlException(PARAMETERS_MISSING_MESSAGE, title);
		}

		if (!EmailValidator.getInstance().isValid(ci.getEmail())) {
			throw new BadRequestHtmlException(INVALID_EMAIL_MESSAGE.replace("{}", ci.getEmail()), title);
		}

		if (StringUtils.isNotEmpty(requestBody.password())) {
			password = requestBody.password().trim();
			if (password.length() < 8) {
				throw new BadRequestHtmlException(SHORT_PASSWORD_MESSAGE, title);
			}
		} else {
			throw new BadRequestHtmlException(PARAMETERS_MISSING_MESSAGE, title);
		}

		setRedirectUrlIfPermitted(requestBody.urlFailure(), ci::setUrlFailure, "failure");
		setRedirectUrlIfPermitted(requestBody.urlSuccess(), ci::setUrlSuccess, "success");

		log.info("Received password reset request for {}", ci.getEmail());

		if ("on".equalsIgnoreCase(config.getString(Settings.AUTH_RECAPTCHA, "off"))
				&& config.getString(Settings.AUTH_RECAPTCHA_PRIVATE_KEY) != null) {
			if (StringUtils.isNotEmpty(requestBody.rcResponseV2())) {
				log.info("Checking reCaptcha for {}", ci.getEmail());
				rcResponseV2 = requestBody.rcResponseV2();
				String remoteAddr = request.getRemoteAddr();

				if (rcVerifier.verify(rcResponseV2, remoteAddr)) {
					log.info("Valid reCaptcha for {}", ci.getEmail());
				} else {
					log.info("Invalid reCaptcha for {}", ci.getEmail());
					throw new BadRequestHtmlException(RECAPTCHA_INVALID_MESSAGE, title);
				}
			} else {
				throw new BadRequestHtmlException(RECAPTCHA_MISSING_MESSAGE, title);
			}
		}

		URI authUser = principalManager.getAuthenticatedUserURI();

		try {
			principalManager.setAuthenticatedUserURI(principalManager.getAdminUser().getURI());

			Entry userEntry = principalManager.getPrincipalEntry(ci.getEmail());
			User u;
			if (userEntry != null) {
				log.debug("Loaded user entry via email adress");
				u = (User) userEntry.getResource();
			} else {
				log.debug("Trying to load user entry via external ID");
				u = principalManager.getUserByExternalID(ci.getEmail());
			}

			// to avoid spamming, etc., we only send emails to users that exist
			if (u != null) {
				if (u.isDisabled()) {
					log.info("User {} is disabled, not allowing password reset", ci.getEmail());
					throw new ForbiddenException(FAILED_TO_SEND_EMAIL_MESSAGE.replace("{}", ci.getEmail()));
				}

				String token = RandomStringUtils.random(16, 0, 0, true, true, null, new SecureRandom());
				String confirmationLink = repositoryManager.getRepositoryURL().toExternalForm() + "auth/pwreset?confirm=" + token;
				log.info("Generated password reset token for {}", ci.getEmail());

				boolean sendSuccessful = Email.sendPasswordResetConfirmation(config, ci.getEmail(), confirmationLink);
				if (sendSuccessful) {
					ci.setSaltedHashedPassword(Password.getSaltedHash(password));
					signupTokenCache.putToken(token, ci);
					log.info("Sent confirmation request to {}", ci.getEmail());
				} else {
					throw new BadRequestHtmlException(FAILED_TO_SEND_EMAIL_MESSAGE.replace("{}", ci.getEmail()), title);
				}
			} else {
				log.info("Ignoring password reset attempt for non-existing user {}", ci.getEmail());
			}
		} finally {
			principalManager.setAuthenticatedUserURI(authUser);
		}

		return POST_SUCCESS_MESSAGE.replace("{}", ci.getEmail());
	}

	public String confirmSignup(String token, String title) {
		SignupInfo signupInfo = signupTokenCache.getTokenValue(token);
		if (signupInfo == null) {
			URL bURL = repositoryManager.getRepositoryURL();
			String appURL = bURL.getProtocol() + "://" + bURL.getHost() + (Arrays.asList(-1, 80, 443).contains(bURL.getPort()) ? "" : ":" + bURL.getPort());
			throw new BadRequestHtmlException(INVALID_SIGNUP_TOKEN_MESSAGE, title, appURL);
		}
		signupTokenCache.removeToken(token);

		URI authUser = principalManager.getAuthenticatedUserURI();
		try {
			principalManager.setAuthenticatedUserURI(principalManager.getAdminUser().getURI());

			Entry userEntry = principalManager.getPrincipalEntry(signupInfo.getEmail());

			if ((userEntry != null && GraphType.User.equals(userEntry.getGraphType())) ||
					principalManager.getUserByExternalID(signupInfo.getEmail()) != null) {
				if (signupInfo.getUrlFailure() != null) {
					handleUrlRedirect(signupInfo.getUrlFailure());
					return null;
				} else {
					throw new DataConflictHtmlException(USER_ALREADY_EXISTS_MESSAGE, title);
				}
			}

			// Create user
			Entry entry = principalManager.createResource(null, GraphType.User, null, null);
			if (entry == null) {
				log.error("Error when creating new user during sign-up ");
				if (signupInfo.getUrlFailure() != null) {
					handleUrlRedirect(signupInfo.getUrlFailure());
					return null;
				} else {
					throw new InternalServerErrorException(UNABLE_TO_CREATE_USER_MESSAGE);
				}
			} else {
				// Set alias, metadata and password
				principalManager.setPrincipalName(entry.getResourceURI(), signupInfo.getEmail());
				setFoafMetadata(entry, signupInfo);
				User u = (User) entry.getResource();
				u.setSaltedHashedSecret(signupInfo.getSaltedHashedPassword());
				if (signupInfo.getCustomProperties() != null) {
					u.setCustomProperties(signupInfo.getCustomProperties());
				}
				log.info("Created user {}", u.getURI());

				if ("on".equalsIgnoreCase(repositoryManager.getConfiguration().getString(Settings.SIGNUP_CREATE_HOME_CONTEXT, "off"))) {
					// Create context and set ACL and alias
					Entry homeContext = contextManager.createResource(null, GraphType.Context, null, null);
					homeContext.addAllowedPrincipalsFor(PrincipalManager.AccessProperty.Administer, u.getURI());
					contextManager.setName(homeContext.getEntryURI(), signupInfo.getEmail());
					log.info("Created context {}", homeContext.getResourceURI());

					// Set home context of user
					u.setHomeContext((Context) homeContext.getResource());
					log.info("Set home context of user {} to {}", u.getURI(), homeContext.getResourceURI());
				}
			}

		} finally {
			principalManager.setAuthenticatedUserURI(authUser);
		}

		if (signupInfo.getUrlSuccess() != null) {
			handleUrlRedirect(signupInfo.getUrlSuccess());
		}

		return CONFIRM_SIGNUP_SUCCESS_MESSAGE;
	}

	public String signup(HttpServletRequest request, SignupRequestBody requestBody, Map<String, String> extraProperties, String title) {
		signupRateLimiter.acquirePermit(HttpUtil.getClientIpAddress(request));

		SignupInfo ci = new SignupInfo();
		ci.setExpirationDate(new Date(new Date().getTime() + TTL)); // 24 hours later

		String rcResponseV2;
		String password;

		if (StringUtils.isNotEmpty(requestBody.email())) {
			ci.setEmail(requestBody.email());
		} else {
			throw new BadRequestHtmlException(PARAMETERS_MISSING_MESSAGE, title);
		}

		if (!EmailValidator.getInstance().isValid(ci.getEmail())) {
			throw new BadRequestHtmlException(INVALID_EMAIL_MESSAGE.replace("{}", ci.getEmail()), title);
		}

		if (StringUtils.isNotEmpty(requestBody.password())) {
			password = requestBody.password().trim();
			if (password.length() < 8) {
				throw new BadRequestHtmlException(BAD_PASSWORD_FORMAT_MESSAGE, title);
			}
		} else {
			throw new BadRequestHtmlException(PARAMETERS_MISSING_MESSAGE, title);
		}

		if (StringUtils.isNotEmpty(requestBody.firstName()) && StringUtils.isNotEmpty(requestBody.lastName())) {
			ci.setFirstName(requestBody.firstName());
			ci.setLastName(requestBody.lastName());
		} else {
			throw new BadRequestHtmlException(PARAMETERS_MISSING_MESSAGE, title);
		}

		if (isInvalidName(ci.getFirstName()) || isInvalidName(ci.getLastName())) {
			throw new BadRequestHtmlException(INVALID_NAME_MESSAGE, title);
		}

		setRedirectUrlIfPermitted(requestBody.urlFailure(), ci::setUrlFailure, "failure");
		setRedirectUrlIfPermitted(requestBody.urlSuccess(), ci::setUrlSuccess, "success");

		if (!extraProperties.isEmpty()) {
			ci.setCustomProperties(new HashMap<>());
			extraProperties.forEach((key, value) -> {
				if (key.startsWith("custom_")) {
					ci.getCustomProperties().put(key.substring(7), value);
				}
			});
		}

		if (!domainWhitelist.isEmpty()) {
			String emailDomain = ci.getEmail().substring(ci.getEmail().indexOf("@") + 1).toLowerCase();
			if (!domainWhitelist.contains(emailDomain)) {
				throw new ExpectationFailedHtmlException(DOMAIN_NOT_WHITELISTED_MESSAGE.replace("{}", emailDomain), title);
			}
		}

		log.info("Received sign-up request for {}", ci.getEmail());

		if ("on".equalsIgnoreCase(config.getString(Settings.AUTH_RECAPTCHA, "off"))
				&& config.getString(Settings.AUTH_RECAPTCHA_PRIVATE_KEY) != null) {
			if (StringUtils.isNotEmpty(requestBody.rcResponseV2())) {
				log.info("Checking reCaptcha for {}", ci.getEmail());
				rcResponseV2 = requestBody.rcResponseV2();
				String remoteAddr = request.getRemoteAddr();

				if (rcVerifier.verify(rcResponseV2, remoteAddr)) {
					log.info("Valid reCaptcha for {}", ci.getEmail());
				} else {
					log.info("Invalid reCaptcha for {}", ci.getEmail());
					throw new BadRequestHtmlException(RECAPTCHA_INVALID_MESSAGE, title);
				}
			} else {
				throw new BadRequestHtmlException(RECAPTCHA_MISSING_MESSAGE, title);
			}
		}

		String token = RandomStringUtils.random(16, 0, 0, true, true, null, new SecureRandom());
		String confirmationLink = repositoryManager.getRepositoryURL().toExternalForm() + "auth/signup?confirm=" + token;
		log.info("Generated sign-up token for {}", ci.getEmail());

		boolean sendSuccessful = Email.sendSignupConfirmation(config, ci.getFirstName() + " " + ci.getLastName(), ci.getEmail(), confirmationLink);
		if (sendSuccessful) {
			ci.setSaltedHashedPassword(Password.getSaltedHash(password));
			signupTokenCache.putToken(token, ci);
			log.info("Sent confirmation request to {}", ci.getEmail());
		} else {
			throw new BadRequestHtmlException(FAILED_TO_SEND_EMAIL_MESSAGE.replace("{}", ci.getEmail()), title);
		}

		return POST_SUCCESS_MESSAGE.replace("{}", ci.getEmail());
	}

	private void setRedirectUrlIfPermitted(String url, Consumer<String> setter, String label) {
		if (StringUtils.isNotEmpty(url)) {
			if (redirectUrlValidator.isPermitted(url)) {
				setter.accept(url);
			} else {
				log.warn("Redirect URL ({}) is not permitted and will be ignored: {}", label, url);
			}
		}
	}

	private void handleUrlRedirect(String url) {
		if (!redirectUrlValidator.isPermitted(url)) {
			throw new InternalServerErrorException("Redirect to non-permitted URL blocked: " + url);
		}
		try {
			throw new RedirectTemporaryException(new URI(url));
		} catch (URISyntaxException ex) {
			throw new InternalServerErrorException("Invalid redirect URL", ex);
		}
	}

	private boolean isInvalidName(String name) {
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

	private void setFoafMetadata(Entry entry, SignupInfo signupInfo) {
		Model graph = entry.getLocalMetadata().getGraph();
		ValueFactory vf = SimpleValueFactory.getInstance();
		IRI resourceURI = vf.createIRI(entry.getResourceURI().toString());
		String fullname = null;
		if (signupInfo.getFirstName() != null) {
			fullname = signupInfo.getFirstName();
			graph.add(vf.createStatement(resourceURI, vf.createIRI(NS.foaf, "givenName"), vf.createLiteral(signupInfo.getFirstName())));
		}
		if (signupInfo.getLastName() != null) {
			if (fullname != null) {
				fullname = fullname + " " + signupInfo.getLastName();
			} else {
				fullname = signupInfo.getLastName();
			}
			graph.add(vf.createStatement(resourceURI, vf.createIRI(NS.foaf, "familyName"), vf.createLiteral(signupInfo.getLastName())));
		}
		if (fullname != null) {
			graph.add(vf.createStatement(resourceURI, vf.createIRI(NS.foaf, "name"), vf.createLiteral(fullname)));
		}
		if (signupInfo.getEmail() != null) {
			graph.add(vf.createStatement(resourceURI, vf.createIRI(NS.foaf, "mbox"), vf.createIRI("mailto:", signupInfo.getEmail())));
		}

		entry.getLocalMetadata().setGraph(graph);
	}
}
