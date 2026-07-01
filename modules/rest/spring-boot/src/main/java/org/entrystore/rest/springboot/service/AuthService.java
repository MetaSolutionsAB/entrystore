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
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
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
import org.entrystore.rest.springboot.configuration.SignupWhitelistProperties;
import org.entrystore.rest.springboot.model.api.PwResetRequestBody;
import org.entrystore.rest.springboot.model.api.SignupRequestBody;
import org.entrystore.rest.springboot.model.auth.ConfirmAttemptResult;
import org.entrystore.rest.springboot.model.auth.ConfirmationResult;
import org.entrystore.rest.springboot.model.auth.SignupInfo;
import org.entrystore.rest.springboot.model.exception.BadRequestHtmlException;
import org.entrystore.rest.springboot.model.exception.DataConflictHtmlException;
import org.entrystore.rest.springboot.model.exception.ExpectationFailedHtmlException;
import org.entrystore.rest.springboot.model.exception.InternalServerErrorException;
import org.entrystore.rest.springboot.model.exception.PwResetEntityNotFoundHtmlException;
import org.entrystore.rest.springboot.model.exception.RedirectTemporaryException;
import org.entrystore.rest.springboot.service.auth.EmailValidator;
import org.entrystore.rest.springboot.service.auth.RecaptchaVerifier;
import org.entrystore.rest.springboot.service.auth.PasswordResetRateLimiter;
import org.entrystore.rest.springboot.service.auth.RedirectUrlValidator;
import org.entrystore.rest.springboot.service.auth.SignupRateLimiter;
import org.entrystore.rest.springboot.service.auth.SignupTokenCache;
import org.entrystore.rest.springboot.util.Email;
import org.entrystore.rest.springboot.util.HttpUtil;
import org.entrystore.rest.springboot.util.PrincipalManagerUtil;
import org.springframework.beans.factory.annotation.Value;
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
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

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
	private static final String FAILED_TO_SEND_SIGNUP_EMAIL_MESSAGE = "Failed to send confirmation request to {}.";
	private static final String INVALID_TOKEN_MESSAGE = "The confirmation token is invalid or has been used already.";
	private static final String USER_NOT_FOUND_MESSAGE = "User with provided email address does not exist.";
	private static final String USER_ALREADY_EXISTS_MESSAGE = "User with submitted email address exists already.";
	private static final String INTERNAL_ERROR_MESSAGE = "Unable to reset password due to internal error.";
	private static final String DOMAIN_NOT_WHITELISTED_MESSAGE = "The email domain is not allowed for sign-up: {}";
	private static final String INVALID_SIGNUP_TOKEN_MESSAGE = "Invalid confirmation link. " +
			"This may be because you already clicked the link and have an account, " +
			"the confirmation link has expired, or the token never existed. " +
			"Visit the link below to sign up again and receive a new confirmation link.";
	private static final String SIGNUP_TOKEN_INVALIDATED_MESSAGE = "Too many failed attempts. " +
			"This confirmation link has been invalidated for security reasons. " +
			"Visit the link below to sign up again and receive a new confirmation link.";
	private static final String PASSWORD_RESET_TOKEN_INVALIDATED_MESSAGE = "Too many failed attempts. " +
			"This confirmation link has been invalidated for security reasons. Please request a new password reset.";
	private static final String UNABLE_TO_CREATE_USER_MESSAGE = "Unable to create user.";

	private final RepositoryManagerImpl repositoryManager;
	private final PrincipalManager principalManager;
	private final ContextManager contextManager;
	private final RecaptchaVerifier rcVerifier;
	private final SignupTokenCache signupTokenCache;
	private final RedirectUrlValidator redirectUrlValidator;
	private final EmailValidator emailValidator;
	private final Config config;
	private final SessionRegistry sessionRegistry;
	private final SignupRateLimiter signupRateLimiter;
	private final PasswordResetRateLimiter passwordResetRateLimiter;
	private final MeterRegistry meterRegistry;
	private final SignupWhitelistProperties signupWhitelistProperties;

	@Value("${entrystore.auth.confirmation.legacy:true}")
	private boolean confirmationLegacy;

	// Bound as a String (not int) so a non-numeric value falls back to the default at read time in
	// maxConfirmationAttempts() rather than failing application startup on bind — see that method.
	@Value("${entrystore.auth.confirmation.max-attempts:3}")
	private String confirmationMaxAttempts;

	@Value("${entrystore.auth.recaptcha:off}")
	private String recaptcha;

	@Value("${entrystore.auth.recaptcha.private-key:#{null}}")
	private String recaptchaPrivateKey;

	@Value("${entrystore.auth.signup.create-home-context:off}")
	private String signupCreateHomeContext;

	@Value("${entrystore.trust.x-forwarded-for:false}")
	private boolean trustForwardedFor;

	private static final Object mutex = new Object();
	private static Set<String> domainWhitelist = null;

	// Shared SecureRandom — token generation runs from the executor's worker threads and these never
	// construct one per call. Reseeding a fresh SecureRandom every request is an unnecessary entropy
	// hit and was also a residual timing discriminator before token generation moved off the request
	// thread (see dispatchPasswordResetEmail).
	private static final SecureRandom SECURE_RANDOM = new SecureRandom();

	// Pool size is intentionally small: pwReset throughput is bounded by PasswordResetRateLimiter,
	// so two daemon threads are enough to absorb concurrent dispatches without blocking the request
	// path. Threads are daemons so a shutdown that misses the @PreDestroy still does not block JVM exit.
	// The queue is bounded to cap heap growth under distributed credential-stuffing or an SMTP outage:
	// at queue saturation `execute` throws RejectedExecutionException, which pwReset catches and treats
	// as a client-silent drop — the response stays at the generic 200 so the timing-equivalence
	// guarantee is preserved, but the rejection is logged at ERROR and increments the
	// `auth.pwreset.rejected` Micrometer counter so operators can alert on sustained drops via
	// monitoring rather than log scraping. A CallerRunsPolicy would have run the rejected task on the
	// request thread, which re-opens the timing oracle this whole executor exists to close.
	private static final int PASSWORD_RESET_POOL_SIZE = 2;
	private static final int PASSWORD_RESET_QUEUE_CAPACITY = 100;
	private ExecutorService passwordResetExecutor;
	private Counter passwordResetRejectedCounter;

	@PostConstruct
	public void init() {
		synchronized (mutex) {
			if (domainWhitelist == null) {
				Collection<String> tmpDomainWhitelist = signupWhitelistProperties.whitelist().values();
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

		AtomicInteger threadIndex = new AtomicInteger();
		ThreadFactory threadFactory = r -> {
			Thread t = new Thread(r, "password-reset-async-" + threadIndex.incrementAndGet());
			t.setDaemon(true);
			// An Error escaping the worker would otherwise be swallowed by ThreadPoolExecutor's default
			// Worker.run path; ensure it always reaches the logs so a regression is observable.
			t.setUncaughtExceptionHandler((thread, ex) ->
					log.error("Uncaught error in password-reset worker {}", thread.getName(), ex));
			return t;
		};
		this.passwordResetExecutor = new ThreadPoolExecutor(
				PASSWORD_RESET_POOL_SIZE, PASSWORD_RESET_POOL_SIZE,
				0L, TimeUnit.MILLISECONDS,
				new ArrayBlockingQueue<>(PASSWORD_RESET_QUEUE_CAPACITY),
				threadFactory);

		this.passwordResetRejectedCounter = Counter.builder("auth.pwreset.rejected")
				.description("Password-reset dispatches dropped because the executor queue was saturated or shutting down")
				.register(meterRegistry);
	}

	@PreDestroy
	public void shutdown() {
		if (passwordResetExecutor == null) {
			return;
		}
		passwordResetExecutor.shutdown();
		try {
			if (!passwordResetExecutor.awaitTermination(30, TimeUnit.SECONDS)) {
				log.warn("Password reset executor did not drain within 30s; forcing shutdown");
				passwordResetExecutor.shutdownNow();
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			passwordResetExecutor.shutdownNow();
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

	/**
	 * Legacy password-reset confirmation: clicking the emailed link immediately applies the password
	 * the requester chose at request time. Used when {@code entrystore.auth.confirmation.legacy=true}.
	 */
	public String confirmPasswordLegacy(String token, String title) {
		SignupInfo ci = signupTokenCache.getTokenValue(token);
		if (ci == null) {
			throw new BadRequestHtmlException(INVALID_TOKEN_MESSAGE, title);
		}
		signupTokenCache.removeToken(token);
		return applyPasswordReset(ci, title);
	}

	/**
	 * New-mode password-reset confirmation (ENTRYSTORE-529). After clicking the emailed link the user
	 * must re-enter the account's email and choose a new password on the confirmation form. The new
	 * password is applied only when the supplied email matches the one the reset was requested for — a
	 * wrong recipient or email scanner that clicks the link does not know which account it is for and
	 * cannot set a password. The token is invalidated after the configured number of failed email
	 * attempts. The new password is collected here (not at request time) so the requester never picks
	 * the password that ends up on the account. It is format-validated (non-empty, at least 8 characters)
	 * before the token is touched, so a malformed password neither consumes the token nor counts as a
	 * failed email attempt.
	 */
	public ConfirmationResult confirmPassword(HttpServletRequest request, String token, String email, String newPassword, String title) {
		passwordResetRateLimiter.acquirePermit(clientIp(request));
		// Validate the chosen password before touching the token so a malformed password neither
		// consumes the token nor counts as a failed email attempt.
		String validatedPassword = validatePasswordFormat(newPassword, title);
		ConfirmAttemptResult attempt = signupTokenCache.confirmAttempt(token,
				ci -> emailMatches(ci, email), maxConfirmationAttempts());
		return switch (attempt.status()) {
			case TOKEN_NOT_FOUND -> throw new BadRequestHtmlException(INVALID_TOKEN_MESSAGE, title);
			case TOKEN_INVALIDATED -> throw new BadRequestHtmlException(PASSWORD_RESET_TOKEN_INVALIDATED_MESSAGE, title);
			case INVALID_CREDENTIALS -> ConfirmationResult.retry(attempt.remainingAttempts());
			case VALID -> {
				SignupInfo ci = attempt.info();
				ci.setSaltedHashedPassword(Password.getSaltedHash(validatedPassword));
				yield ConfirmationResult.success(applyPasswordReset(ci, title));
			}
		};
	}

	private String applyPasswordReset(SignupInfo ci, String title) {
		URI authUser = principalManager.getAuthenticatedUserURI();
		Throwable primary = null;
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
		} catch (Throwable t) {
			primary = t;
			throw t;
		} finally {
			PrincipalManagerUtil.restoreAuthenticatedUserSafely(principalManager, authUser, primary);
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

		validateAndSetEmail(requestBody.email(), ci, title);

		// Legacy mode collects the new password now and applies it on link click. New mode collects the
		// new password on the confirmation form instead, so the requester never picks the password that
		// ends up on the account (ENTRYSTORE-529); only the email is needed to start the flow.
		String password = null;
		if (isLegacyConfirmationMode()) {
			if (StringUtils.isNotEmpty(requestBody.password())) {
				password = requestBody.password().trim();
				if (password.length() < 8) {
					throw new BadRequestHtmlException(SHORT_PASSWORD_MESSAGE, title);
				}
			} else {
				throw new BadRequestHtmlException(PARAMETERS_MISSING_MESSAGE, title);
			}
		}

		setRedirectUrlIfPermitted(requestBody.urlFailure(), ci::setUrlFailure, "failure");
		setRedirectUrlIfPermitted(requestBody.urlSuccess(), ci::setUrlSuccess, "success");

		log.info("Received password reset request for {}", ci.getEmail());

		passwordResetRateLimiter.acquirePermit(clientIp(request));

		if ("on".equalsIgnoreCase(recaptcha)
				&& recaptchaPrivateKey != null) {
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
		boolean shouldSend = false;
		Throwable primary = null;

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

			// Nonexistent and disabled users receive the same success response as a real send so the
			// endpoint does not leak which usernames exist or are active; the actual outcome is only logged.
			if (u == null) {
				log.info("Ignoring password reset attempt for non-existing user {}", HttpUtil.sanitizeForLog(ci.getEmail()));
			} else if (u.isDisabled()) {
				log.info("Ignoring password reset attempt for disabled user {}", HttpUtil.sanitizeForLog(ci.getEmail()));
			} else {
				shouldSend = true;
				log.info("Resolved active user for password reset attempt {}", HttpUtil.sanitizeForLog(ci.getEmail()));
			}
		} catch (Throwable t) {
			primary = t;
			throw t;
		} finally {
			PrincipalManagerUtil.restoreAuthenticatedUserSafely(principalManager, authUser, primary);
		}

		// The expensive work (token generation + bcrypt + SMTP send) runs on a background thread so
		// all three branches (nonexistent / disabled / active) return to the client without the
		// bcrypt/SMTP timing gap, closing the timing-based account-enumeration side channel that a
		// synchronous send introduces. Remaining synchronous work in the active branch is the boolean
		// gate flip and the executor submit — both sub-microsecond and not intended as a hard
		// timing-equality guarantee. Do not add further synchronous work to the active branch.
		if (shouldSend) {
			submitPasswordResetDispatch(ci, password);
		}

		return POST_SUCCESS_MESSAGE.replace("{}", ci.getEmail());
	}

	// Package-private for unit testing the rejected-execution path.
	void submitPasswordResetDispatch(SignupInfo ci, String password) {
		try {
			passwordResetExecutor.execute(() -> dispatchPasswordResetEmail(ci, password));
		} catch (RejectedExecutionException rex) {
			// Bounded queue saturated (likely SMTP outage backing up dispatches) or executor
			// shutting down. We stay on the generic 200 path so attackers cannot distinguish
			// "user exists, queue full" from "user does not exist"; the user can retry. The
			// rejection is recorded in a Micrometer counter and logged at ERROR so operators
			// can alert on sustained drops.
			passwordResetRejectedCounter.increment();
			log.error("Password reset executor rejected dispatch for {} (queue saturated or shutting down)",
					HttpUtil.sanitizeForLog(ci.getEmail()), rex);
		}
	}

	// Runs on the passwordResetExecutor. Storing the token in the cache BEFORE sending the email
	// guarantees the link is usable by the time it lands in the recipient's inbox; if the SMTP send
	// fails we remove the token so an attacker who somehow learned it cannot complete the reset.
	private void dispatchPasswordResetEmail(SignupInfo ci, String password) {
		String emailLog = HttpUtil.sanitizeForLog(ci.getEmail());
		String token = RandomStringUtils.random(16, 0, 0, true, true, null, SECURE_RANDOM);
		String confirmationLink = repositoryManager.getRepositoryURL().toExternalForm() + "auth/pwreset?confirm=" + token;
		log.info("Generated password reset token for {}", emailLog);

		// Bcrypt and putToken run outside the try block — a Throwable here propagates to the worker's
		// UncaughtExceptionHandler (which already logs at ERROR) and no cleanup is needed because the
		// token was never stored. Once we reach the try block, putToken has returned successfully, so
		// the catch can unconditionally remove the token without an extra flag.
		// In new mode the password is null here — it is chosen on the confirmation form instead, so we
		// only hash and store it for legacy mode.
		if (password != null) {
			ci.setSaltedHashedPassword(Password.getSaltedHash(password));
		}
		signupTokenCache.putToken(token, ci);

		try {
			boolean sendSuccessful = Email.sendPasswordResetConfirmation(config, ci.getEmail(), confirmationLink);
			if (sendSuccessful) {
				log.info("Sent confirmation request to {}", emailLog);
			} else {
				signupTokenCache.removeToken(token);
				// Stays in the generic success response so attackers cannot distinguish
				// "user exists AND mail failed" from "user does not exist".
				log.error("Failed to send password reset email to {}", emailLog);
			}
		} catch (Throwable t) {
			// Catch Throwable so an Error subclass (OOM, NoClassDefFoundError, StackOverflowError) is
			// observable in the logs and the token is cleaned up. Errors are rethrown so the JVM's
			// normal handling continues — on `-XX:+ExitOnOutOfMemoryError` the JVM terminates; otherwise
			// the worker thread dies and ThreadPoolExecutor spawns a replacement on the next submit.
			signupTokenCache.removeToken(token);
			log.error("Async password reset dispatch failed for {}", emailLog, t);
			if (t instanceof Error err) {
				throw err;
			}
		}
	}

	/**
	 * Legacy sign-up confirmation: clicking the emailed link immediately creates the account using the
	 * credentials chosen at sign-up time. Used when {@code entrystore.auth.confirmation.legacy=true}.
	 */
	public String confirmSignupLegacy(String token, String title) {
		SignupInfo signupInfo = signupTokenCache.getTokenValue(token);
		if (signupInfo == null) {
			throw new BadRequestHtmlException(INVALID_SIGNUP_TOKEN_MESSAGE, title, appBaseUrl());
		}
		signupTokenCache.removeToken(token);
		return createUserFromSignup(signupInfo, title);
	}

	/**
	 * New-mode sign-up confirmation (ENTRYSTORE-529): only creates the account if the supplied email and
	 * password match the credentials the requester chose at sign-up time. This proves the person clicking
	 * the emailed link is the one who initiated the sign-up — a mistyped recipient or email scanner that
	 * clicks the link does not know the chosen password and cannot activate the account. The token is
	 * invalidated after the configured number of failed attempts.
	 */
	public ConfirmationResult confirmSignup(HttpServletRequest request, String token, String email, String password, String title) {
		signupRateLimiter.acquirePermit(clientIp(request));
		ConfirmAttemptResult attempt = signupTokenCache.confirmAttempt(token,
				ci -> credentialsMatch(ci, email, password), maxConfirmationAttempts());
		return switch (attempt.status()) {
			case TOKEN_NOT_FOUND -> throw new BadRequestHtmlException(INVALID_SIGNUP_TOKEN_MESSAGE, title, appBaseUrl());
			case TOKEN_INVALIDATED -> throw new BadRequestHtmlException(SIGNUP_TOKEN_INVALIDATED_MESSAGE, title, appBaseUrl());
			case INVALID_CREDENTIALS -> ConfirmationResult.retry(attempt.remainingAttempts());
			case VALID -> ConfirmationResult.success(createUserFromSignup(attempt.info(), title));
		};
	}

	private String createUserFromSignup(SignupInfo signupInfo, String title) {
		URI authUser = principalManager.getAuthenticatedUserURI();
		Throwable primary = null;
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

				if ("on".equalsIgnoreCase(signupCreateHomeContext)) {
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

		} catch (Throwable t) {
			primary = t;
			throw t;
		} finally {
			PrincipalManagerUtil.restoreAuthenticatedUserSafely(principalManager, authUser, primary);
		}

		if (signupInfo.getUrlSuccess() != null) {
			handleUrlRedirect(signupInfo.getUrlSuccess());
		}

		return CONFIRM_SIGNUP_SUCCESS_MESSAGE;
	}

	/**
	 * Verifies that there is still a pending sign-up for the token without consuming it, so the GET
	 * handler can render the confirmation form. Throws if the token is unknown, expired, or used.
	 */
	public void assertSignupTokenValid(String token, String title) {
		if (signupTokenCache.getTokenValue(token) == null) {
			throw new BadRequestHtmlException(INVALID_SIGNUP_TOKEN_MESSAGE, title, appBaseUrl());
		}
	}

	/**
	 * Verifies that there is still a pending password reset for the token without consuming it, so the
	 * GET handler can render the confirmation form. Throws if the token is unknown, expired, or used.
	 */
	public void assertPasswordResetTokenValid(String token, String title) {
		if (signupTokenCache.getTokenValue(token) == null) {
			throw new BadRequestHtmlException(INVALID_TOKEN_MESSAGE, title);
		}
	}

	/**
	 * Whether the legacy confirmation behaviour is active (clicking the emailed link completes the
	 * action immediately). Defaults to {@code true} so existing deployments keep working until an
	 * operator opts into the credential-confirmation flow (ENTRYSTORE-529).
	 */
	public boolean isLegacyConfirmationMode() {
		return confirmationLegacy;
	}

	private int maxConfirmationAttempts() {
		int configured;
		try {
			configured = Integer.parseInt(confirmationMaxAttempts.trim());
		} catch (NumberFormatException e) {
			log.warn("{} is not a valid integer; falling back to the default of 3.", Settings.AUTH_CONFIRMATION_MAX_ATTEMPTS);
			return 3;
		}
		if (configured < 1) {
			log.warn("{} is {} (< 1); clamping to 1. A value below 1 would invalidate every confirmation token on the first attempt.",
					Settings.AUTH_CONFIRMATION_MAX_ATTEMPTS, configured);
			return 1;
		}
		return configured;
	}

	private boolean credentialsMatch(SignupInfo ci, String email, String password) {
		if (StringUtils.isEmpty(email) || StringUtils.isEmpty(password)) {
			return false;
		}
		if (!ci.getEmail().equalsIgnoreCase(email.trim())) {
			return false;
		}
		try {
			return Password.check(password.trim(), ci.getSaltedHashedPassword());
		} catch (IllegalArgumentException e) {
			// Password.check rejects empty/oversized input; treat as a failed attempt rather than an error.
			// Logged (without the value) so an over-long paste is distinguishable from a genuine mismatch.
			log.info("Sign-up confirmation attempt rejected: supplied password failed format validation (empty or oversized).");
			return false;
		}
	}

	private boolean emailMatches(SignupInfo ci, String email) {
		return StringUtils.isNotEmpty(email) && ci.getEmail().equalsIgnoreCase(email.trim());
	}

	private String validatePasswordFormat(String password, String title) {
		if (StringUtils.isEmpty(password)) {
			throw new BadRequestHtmlException(PARAMETERS_MISSING_MESSAGE, title);
		}
		String trimmed = password.trim();
		if (trimmed.length() < 8) {
			throw new BadRequestHtmlException(SHORT_PASSWORD_MESSAGE, title);
		}
		return trimmed;
	}

	private String appBaseUrl() {
		URL url = repositoryManager.getRepositoryURL();
		boolean isDefaultPort = url.getPort() == -1 || url.getPort() == 80 || url.getPort() == 443;
		return url.getProtocol() + "://" + url.getHost() + (isDefaultPort ? "" : ":" + url.getPort());
	}

	public String signup(HttpServletRequest request, SignupRequestBody requestBody, Map<String, String> extraProperties, String title) {
		SignupInfo ci = new SignupInfo();
		ci.setExpirationDate(new Date(new Date().getTime() + TTL)); // 24 hours later

		String rcResponseV2;
		String password;

		validateAndSetEmail(requestBody.email(), ci, title);

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

		signupRateLimiter.acquirePermit(clientIp(request));

		if ("on".equalsIgnoreCase(recaptcha)
				&& recaptchaPrivateKey != null) {
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
			throw new BadRequestHtmlException(FAILED_TO_SEND_SIGNUP_EMAIL_MESSAGE.replace("{}", ci.getEmail()), title);
		}

		return POST_SUCCESS_MESSAGE.replace("{}", ci.getEmail());
	}

	private void validateAndSetEmail(String email, SignupInfo ci, String title) {
		if (StringUtils.isNotEmpty(email)) {
			ci.setEmail(email);
		} else {
			throw new BadRequestHtmlException(PARAMETERS_MISSING_MESSAGE, title);
		}

		if (!emailValidator.isValid(ci.getEmail())) {
			throw new BadRequestHtmlException(INVALID_EMAIL_MESSAGE.replace("{}", ci.getEmail()), title);
		}
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

	private String clientIp(HttpServletRequest request) {
		return HttpUtil.getClientIpAddress(request, trustForwardedFor);
	}
}
