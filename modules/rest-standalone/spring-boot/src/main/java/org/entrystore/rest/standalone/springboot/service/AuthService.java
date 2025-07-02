package org.entrystore.rest.standalone.springboot.service;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.RandomStringUtils;
import org.entrystore.Entry;
import org.entrystore.PrincipalManager;
import org.entrystore.User;
import org.entrystore.config.Config;
import org.entrystore.impl.RepositoryManagerImpl;
import org.entrystore.repository.config.Settings;
import org.entrystore.repository.security.Password;
import org.entrystore.rest.standalone.springboot.model.api.PwResetRequestBody;
import org.entrystore.rest.standalone.springboot.model.exception.ForbiddenException;
import org.entrystore.rest.standalone.springboot.model.exception.PwResetBadRequestHtmlException;
import org.entrystore.rest.standalone.springboot.service.auth.EmailValidator;
import org.entrystore.rest.standalone.springboot.service.auth.RecaptchaVerifier;
import org.entrystore.rest.standalone.springboot.service.auth.SignupInfo;
import org.entrystore.rest.standalone.springboot.service.auth.SignupTokenCache;
import org.entrystore.rest.standalone.springboot.util.Email;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.security.SecureRandom;
import java.util.Date;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

	private final String successMessage = "A confirmation message was sent to {}, if the user exists.";
	private final String parametersMissingMessage = "One or more parameters are missing.";
	private final String shortPasswordMessage = "The password has to consist of at least 8 characters.";
	private final String invalidEmailMessage = "Invalid email address: {}.";
	private final String recaptchaMissingMessage = "reCaptcha information missing.";
	private final String recaptchaInvalidMessage = "Invalid reCaptcha received.";
	private final String failedToSendEmailMessage = "Failed to send confirmation request to {}.";

	private final String RECAPTCHA_URL_DEFAULT = "https://www.google.com/recaptcha/api/siteverify";

	private final RepositoryManagerImpl repositoryManager;
	private final PrincipalManager principalManager;

	public String pwReset(HttpServletRequest request, PwResetRequestBody requestBody) {
		SignupInfo ci = new SignupInfo(repositoryManager);
		ci.setExpirationDate(new Date(new Date().getTime() + (24 * 3600 * 1000))); // 24 hours later

		String rcResponseV2;
		String password;

		if (requestBody.email() != null && !requestBody.email().isEmpty()) {
			ci.setEmail(requestBody.email());
		} else {
			throw new PwResetBadRequestHtmlException(parametersMissingMessage);
		}

		if (!EmailValidator.getInstance().isValid(ci.getEmail())) {
			throw new PwResetBadRequestHtmlException(invalidEmailMessage.replace("{}", ci.getEmail()));
		}

		if (requestBody.password() != null && !requestBody.password().isEmpty()) {
			password = requestBody.password();
		} else {
			throw new PwResetBadRequestHtmlException(parametersMissingMessage);
		}

		if (password.trim().length() < 8) {
			throw new PwResetBadRequestHtmlException(shortPasswordMessage);
		}

		if (requestBody.urlFailure() != null && !requestBody.urlFailure().isEmpty()) {
			ci.setUrlFailure(requestBody.urlFailure());
		}
		if (requestBody.urlSuccess() != null && !requestBody.urlSuccess().isEmpty()) {
			ci.setUrlSuccess(requestBody.urlSuccess());
		}

		Config config = repositoryManager.getConfiguration();

		log.info("Received password reset request for {}", ci.getEmail());

		if ("on".equalsIgnoreCase(config.getString(Settings.AUTH_RECAPTCHA, "off"))
			&& config.getString(Settings.AUTH_RECAPTCHA_PRIVATE_KEY) != null) {
			if (requestBody.rcResponseV2() != null && !requestBody.rcResponseV2().isEmpty()) {
				log.info("Checking reCaptcha for {}", ci.getEmail());
				rcResponseV2 = requestBody.rcResponseV2();
				String remoteAddr = request.getRemoteAddr();

				RecaptchaVerifier rcVerifier = new RecaptchaVerifier(
					config.getString(Settings.AUTH_RECAPTCHA_URL, RECAPTCHA_URL_DEFAULT),
					config.getString(Settings.AUTH_RECAPTCHA_PRIVATE_KEY));

				if (rcVerifier.verify(rcResponseV2, remoteAddr)) {
					log.info("Valid reCaptcha for {}", ci.getEmail());
				} else {
					log.info("Invalid reCaptcha for {}", ci.getEmail());
					throw new PwResetBadRequestHtmlException(recaptchaInvalidMessage);
				}
			} else {
				throw new PwResetBadRequestHtmlException(recaptchaMissingMessage);
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
					SignupTokenCache.getInstance().putToken(token, ci);
					log.info("Sent confirmation request to {}", ci.getEmail());
				} else {
					throw new PwResetBadRequestHtmlException(failedToSendEmailMessage.replace("{}", ci.getEmail()));
				}
			} else {
				log.info("Ignoring password reset attempt for non-existing user {}", ci.getEmail());
			}
		} finally {
			principalManager.setAuthenticatedUserURI(authUser);
		}

		return successMessage.replace("{}", ci.getEmail());
	}
}
