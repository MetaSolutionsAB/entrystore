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
import lombok.extern.slf4j.Slf4j;
import org.entrystore.rest.springboot.util.HttpUtil;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationFailureHandler;

import java.io.IOException;

/**
 * Redirects a failed CAS or OIDC login to the default failure URL and logs the failure at WARN;
 * {@link SamlLoginFailureHandler} reuses the logging and redirects SAML failures to the caller's failure URL instead.
 * The superclass logs at DEBUG only, which hides an IdP or CAS server that is down, a bad client secret, clock skew,
 * an expired SAML authentication request and similar faults in production.
 *
 * <p>The SAML ACS endpoint is anonymous and Spring's messages quote values from the response, so the WARN carries
 * the sanitized message only and the stack trace goes to DEBUG.
 */
@Slf4j
class SsoLoginFailureHandler extends SimpleUrlAuthenticationFailureHandler {

	private final String authTypeLabel;

	SsoLoginFailureHandler(String authTypeLabel, String defaultFailureUrl) {
		super(defaultFailureUrl);
		this.authTypeLabel = authTypeLabel;
	}

	@Override
	public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response,
										AuthenticationException exception) throws IOException, ServletException {
		logFailure(request, exception);
		super.onAuthenticationFailure(request, response, exception);
	}

	protected void logFailure(HttpServletRequest request, AuthenticationException exception) {
		log.warn("{} authentication failed at '{}': {}", authTypeLabel, request.getRequestURI(),
				HttpUtil.sanitizeForLog(exception.getMessage()));
		log.debug("{} authentication failure", authTypeLabel, exception);
	}
}
