package org.entrystore.rest.springboot.security;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.entrystore.rest.springboot.service.auth.LoginAttemptService;
import org.entrystore.rest.springboot.util.HttpUtil;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Slf4j
@Component
@RequiredArgsConstructor
public class FormLoginAuthenticationFailureHandler implements AuthenticationFailureHandler {

	private final LoginAttemptService loginAttemptService;

	// Emits a single normalized 401 + JSON ErrorResponse for every authentication failure
	// (bad credentials, unknown user, disabled account, etc.) so the response does not leak
	// which condition occurred. The underlying reason is only logged.
	@Override
	public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response, AuthenticationException exception) throws ServletException, IOException {
		// Credential-style failures are routine (typo, brute-force probing); other AuthenticationException
		// subclasses usually indicate an infrastructure fault and should reach an operator with the cause.
		if (exception instanceof BadCredentialsException || exception instanceof UsernameNotFoundException) {
			log.info("Login failed at '{}' ({}): {}", request.getRequestURI(), exception.getClass().getSimpleName(), exception.getMessage());
		} else {
			log.warn("Login failed at '{}' ({}): {}", request.getRequestURI(), exception.getClass().getSimpleName(), exception.getMessage(), exception);
		}

		String username = request.getParameter("auth_username");
		if (username != null) {
			try {
				loginAttemptService.recordFailure(username);
			} catch (RuntimeException e) {
				// Lockout bookkeeping must never break the normalized response contract. The
				// counter increment makes sustained Caffeine degradation alertable via metrics
				// rather than log scraping — without it the enumeration oracle silently re-opens.
				loginAttemptService.getRecordFailureErrorCounter().increment();
				log.warn("Failed to record login attempt for [{}]", HttpUtil.sanitizeForLog(username), e);
			}
		}

		HttpUtil.writeUnauthorizedAsJson(response, request);
	}
}
