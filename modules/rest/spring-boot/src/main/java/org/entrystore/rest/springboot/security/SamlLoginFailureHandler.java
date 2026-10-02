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

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.entrystore.rest.springboot.configuration.ConditionalOnBooleanConfig;
import org.entrystore.rest.springboot.configuration.SamlCustomConfiguration;
import org.entrystore.rest.springboot.model.auth.AuthState;
import org.entrystore.rest.springboot.service.SamlAuthService;
import org.entrystore.rest.springboot.service.auth.SamlAuthStateCache;
import org.springframework.security.core.AuthenticationException;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Redirects a SAML response that Spring rejects (signature, clock skew, conditions, {@code InResponseTo}) to the
 * {@code failureurl} the caller gave when starting the login, as 5.x did, else to the default failure URL. The URL
 * is read from the relay-state entry that {@link SamlRelayStateResolver} stored after validating it, never from a
 * request parameter (open redirect, ENTRYSTORE-996), and is validated again here as
 * {@link SamlLoginSuccessHandler#resolveFailureUrl} does. A login that outlives
 * {@link SamlCustomConfiguration#requestLifetime()} has no entry left and lands on the default.
 */
@Component
@ConditionalOnBooleanConfig("entrystore.auth.saml.enabled")
public class SamlLoginFailureHandler extends SsoLoginFailureHandler {

	private final SamlAuthService samlAuthService;
	private final SamlAuthStateCache samlAuthStateCache;

	public SamlLoginFailureHandler(SamlAuthService samlAuthService, SamlAuthStateCache samlAuthStateCache,
								   SamlCustomConfiguration samlConfiguration) {
		super("SAML", samlConfiguration.redirectFailure().url());
		this.samlAuthService = samlAuthService;
		this.samlAuthStateCache = samlAuthStateCache;
	}

	@Override
	public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response,
										AuthenticationException exception) throws IOException {
		String relayState = request.getParameter("RelayState");
		AuthState authState = relayState != null ? samlAuthStateCache.getAuthState(relayState) : null;
		String failureUrl = samlAuthService.failureRedirectUrl(authState != null ? authState.failureUrl() : null);
		logFailure(request, exception);
		getRedirectStrategy().sendRedirect(request, response, failureUrl);
	}
}
