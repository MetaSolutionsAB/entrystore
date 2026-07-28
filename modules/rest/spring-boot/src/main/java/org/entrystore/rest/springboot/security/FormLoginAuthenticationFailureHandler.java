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

package org.entrystore.rest.springboot.security;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.entrystore.rest.springboot.service.auth.LoginAttemptService;
import org.entrystore.rest.springboot.util.ErrorResponseWriter;
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
	private final ErrorResponseWriter errorResponseWriter;

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

		errorResponseWriter.writeUnauthorizedAsJson(response, request.getRequestURI());
	}
}
