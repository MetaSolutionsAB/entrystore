package org.entrystore.rest.standalone.springboot.service;

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
import org.entrystore.rest.standalone.springboot.model.api.PwResetRequestBody;
import org.entrystore.rest.standalone.springboot.model.api.SignupRequestBody;
import org.entrystore.rest.standalone.springboot.model.auth.SignupInfo;
import org.entrystore.rest.standalone.springboot.model.exception.BadRequestHtmlException;
import org.entrystore.rest.standalone.springboot.model.exception.DataConflictHtmlException;
import org.entrystore.rest.standalone.springboot.model.exception.ExpectationFailedHtmlException;
import org.entrystore.rest.standalone.springboot.model.exception.ForbiddenException;
import org.entrystore.rest.standalone.springboot.model.exception.InternalServerErrorException;
import org.entrystore.rest.standalone.springboot.model.exception.PwResetEntityNotFoundHtmlException;
import org.entrystore.rest.standalone.springboot.model.exception.RedirectTemporaryException;
import org.entrystore.rest.standalone.springboot.service.auth.EmailValidator;
import org.entrystore.rest.standalone.springboot.service.auth.RecaptchaVerifier;
import org.entrystore.rest.standalone.springboot.service.auth.SignupTokenCache;
import org.entrystore.rest.standalone.springboot.util.Email;
import org.springframework.security.core.session.SessionInformation;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.security.SecureRandom;
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

	private final int TTL = 24 * 3600 * 1000;

	private final String postSuccessMessage = "A confirmation message was sent to {}, if the user exists.";
	private final String confirmPasswordResetSuccessMessage = "Password reset was successful.";
	private final String confirmSignupSuccessMessage = "Sign-up successful.";
	private final String parametersMissingMessage = "One or more parameters are missing.";
	private final String shortPasswordMessage = "The password has to consist of at least 8 characters.";
	private final String badPasswordFormatMessage = "The password must conform to the configured rules.";
	private final String invalidEmailMessage = "Invalid email address: {}.";
	private final String invalidNameMessage = "Invalid name.";
	private final String recaptchaMissingMessage = "reCaptcha information missing.";
	private final String recaptchaInvalidMessage = "Invalid reCaptcha received.";
	private final String failedToSendEmailMessage = "Failed to send confirmation request to {}.";
	private final String invalidTokenMessage = "The confirmation token is invalid or has been used already.";
	private final String userNotFoundMessage = "User with provided email address does not exist.";
	private final String userAlreadyExistsMessage = "User with submitted email address exists already.";
	private final String internalErrorMessage = "Unable to reset password due to internal error.";
	private final String domainNotWhitelistedMessage = "The email domain is not allowed for sign-up: {}";
	private final String invalidSignupTokenMessage = "<h4>Invalid confirmation link.</h4>" +
			"This may be due to one of the following reasons:<br/>" +
			"<ul><li>You have clicked the link twice and you already have an account.</li>" +
			"<li>The confirmation link has expired.</li>" +
			"<li>The link's confirmation token has never existed.</li></ul>" +
			"Click here to sign up again and to receive a new confirmation link:<br/>" +
			"<a href=\"{1}\"><pre>{2}</pre></a>";
	private final String unableToCreateUserMessage = "Unable to create user.";

	private final RepositoryManagerImpl repositoryManager;
	private final PrincipalManager principalManager;
	private final ContextManager contextManager;
	private final RecaptchaVerifier rcVerifier;
	private final SignupTokenCache signupTokenCache;
	private final Config config;
	private final SessionRegistry sessionRegistry;

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

		try {
			String username = principalManager.getUser(userURI).getEntry().getResourceURI().toString();
			for (Object principal : sessionRegistry.getAllPrincipals()) {
				if (principal instanceof UserDetails user && user.getUsername().equals(username)) {
					sessionsList = sessionRegistry.getAllSessions(user, includeExpiredSessions);
					break;
				}
			}
		} catch (NullPointerException e) {
			log.error(e.getMessage(), e);
		}

		return sessionsList;
	}

	public String confirmPassword(String token, String title) {
		SignupInfo ci = signupTokenCache.getTokenValue(token);
		if (ci == null) {
			throw new BadRequestHtmlException(invalidTokenMessage, title);
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
					throw new PwResetEntityNotFoundHtmlException(userNotFoundMessage, title);
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
						throw new InternalServerErrorException(internalErrorMessage);
					}
				}
			}
		} finally {
			principalManager.setAuthenticatedUserURI(authUser);
		}

		if (ci.getUrlSuccess() != null) {
			handleUrlRedirect(ci.getUrlSuccess());
		}

		return confirmPasswordResetSuccessMessage;
	}

	public String pwReset(HttpServletRequest request, PwResetRequestBody requestBody, String title) {
		SignupInfo ci = new SignupInfo(repositoryManager);
		ci.setExpirationDate(new Date(new Date().getTime() + TTL)); // 24 hours later

		String rcResponseV2;
		String password;

		if (StringUtils.isNotEmpty(requestBody.email())) {
			ci.setEmail(requestBody.email());
		} else {
			throw new BadRequestHtmlException(parametersMissingMessage, title);
		}

		if (!EmailValidator.getInstance().isValid(ci.getEmail())) {
			throw new BadRequestHtmlException(invalidEmailMessage.replace("{}", ci.getEmail()), title);
		}

		if (StringUtils.isNotEmpty(requestBody.password())) {
			password = requestBody.password().trim();
			if (password.length() < 8) {
				throw new BadRequestHtmlException(shortPasswordMessage, title);
			}
		} else {
			throw new BadRequestHtmlException(parametersMissingMessage, title);
		}

		if (StringUtils.isNotEmpty(requestBody.urlFailure())) {
			ci.setUrlFailure(requestBody.urlFailure());
		}
		if (StringUtils.isNotEmpty(requestBody.urlSuccess())) {
			ci.setUrlSuccess(requestBody.urlSuccess());
		}

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
					throw new BadRequestHtmlException(recaptchaInvalidMessage, title);
				}
			} else {
				throw new BadRequestHtmlException(recaptchaMissingMessage, title);
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
					throw new ForbiddenException(failedToSendEmailMessage);
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
					throw new BadRequestHtmlException(failedToSendEmailMessage.replace("{}", ci.getEmail()), title);
				}
			} else {
				log.info("Ignoring password reset attempt for non-existing user {}", ci.getEmail());
			}
		} finally {
			principalManager.setAuthenticatedUserURI(authUser);
		}

		return postSuccessMessage.replace("{}", ci.getEmail());
	}

	public String confirmSignup(String token, String title) {
		SignupInfo signupInfo = signupTokenCache.getTokenValue(token);
		if (signupInfo == null) {
			URL bURL = repositoryManager.getRepositoryURL();
			String appURL = bURL.getProtocol() + "://" + bURL.getHost() + (Arrays.asList(-1, 80, 443).contains(bURL.getPort()) ? "" : ":" + bURL.getPort());
			throw new BadRequestHtmlException(invalidSignupTokenMessage.replace("{1}", bURL.toString()).replace("{2}", appURL), title);
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
					throw new DataConflictHtmlException(userAlreadyExistsMessage, title);
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
					throw new InternalServerErrorException(unableToCreateUserMessage);
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

		return confirmSignupSuccessMessage;
	}

	public String signup(HttpServletRequest request, SignupRequestBody requestBody, Map<String, String> extraProperties, String title) {
		SignupInfo ci = new SignupInfo(repositoryManager);
		ci.setExpirationDate(new Date(new Date().getTime() + TTL)); // 24 hours later

		String rcResponseV2;
		String password;

		if (StringUtils.isNotEmpty(requestBody.email())) {
			ci.setEmail(requestBody.email());
		} else {
			throw new BadRequestHtmlException(parametersMissingMessage, title);
		}

		if (!EmailValidator.getInstance().isValid(ci.getEmail())) {
			throw new BadRequestHtmlException(invalidEmailMessage.replace("{}", ci.getEmail()), title);
		}

		if (StringUtils.isNotEmpty(requestBody.password())) {
			password = requestBody.password().trim();
			if (password.length() < 8) {
				throw new BadRequestHtmlException(badPasswordFormatMessage, title);
			}
		} else {
			throw new BadRequestHtmlException(parametersMissingMessage, title);
		}

		if (StringUtils.isNotEmpty(requestBody.firstName()) && StringUtils.isNotEmpty(requestBody.lastName())) {
			ci.setFirstName(requestBody.firstName());
			ci.setLastName(requestBody.lastName());
		} else {
			throw new BadRequestHtmlException(parametersMissingMessage, title);
		}

		if (isInvalidName(ci.getFirstName()) || isInvalidName(ci.getLastName())) {
			throw new BadRequestHtmlException(invalidNameMessage, title);
		}

		if (StringUtils.isNotEmpty(requestBody.urlFailure())) {
			ci.setUrlFailure(requestBody.urlFailure());
		}
		if (StringUtils.isNotEmpty(requestBody.urlSuccess())) {
			ci.setUrlSuccess(requestBody.urlSuccess());
		}

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
				throw new ExpectationFailedHtmlException(domainNotWhitelistedMessage.replace("{}", emailDomain), title);
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
					throw new BadRequestHtmlException(recaptchaInvalidMessage, title);
				}
			} else {
				throw new BadRequestHtmlException(recaptchaMissingMessage, title);
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
			throw new BadRequestHtmlException(failedToSendEmailMessage.replace("{}", ci.getEmail()), title);
		}

		return postSuccessMessage.replace("{}", ci.getEmail());
	}

	private void handleUrlRedirect(String url) {
		try {
			throw new RedirectTemporaryException(new URI(url));
		} catch (URISyntaxException ex) {
			throw new InternalServerErrorException(ex.getMessage());
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
