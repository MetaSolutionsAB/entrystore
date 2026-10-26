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
import lombok.extern.slf4j.Slf4j;
import org.entrystore.rest.springboot.configuration.ConditionalOnBooleanConfig;
import org.entrystore.rest.springboot.configuration.OidcCustomConfiguration;
import org.entrystore.rest.springboot.model.auth.AuthState;
import org.entrystore.rest.springboot.service.OidcAuthService;
import org.entrystore.rest.springboot.service.auth.OidcAuthStateCache;
import org.entrystore.rest.springboot.util.HttpUtil;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Redirects an OIDC callback that Spring rejects (an IdP error such as {@code access_denied}, an unknown or consumed
 * {@code state}, a failed token exchange) to the {@code failureurl} the caller gave when starting the login, else to
 * the default failure URL. The URL is read from the {@code state}-keyed entry that
 * {@link OidcAuthorizationRequestResolver} stored after validating it, never from a request parameter (open redirect,
 * ENTRYSTORE-996), and is validated again here as {@link OidcLoginSuccessHandler#resolveFailureUrl} does. A login that
 * outlives {@link OidcCustomConfiguration#REQUEST_LIFETIME} has no entry left and lands on the default. The redirect
 * goes through {@link CacheAwareRedirectStrategy}: the 302 commits the response before {@code CacheControlFilter} runs.
 */
@Slf4j
@Component
@ConditionalOnBooleanConfig("entrystore.auth.oidc.enabled")
public class OidcLoginFailureHandler extends SsoLoginFailureHandler {

	private final OidcAuthService oidcAuthService;
	private final OidcAuthStateCache oidcAuthStateCache;

	public OidcLoginFailureHandler(OidcAuthService oidcAuthService, OidcAuthStateCache oidcAuthStateCache,
								   OidcCustomConfiguration oidcConfiguration) {
		super("OIDC", oidcConfiguration.redirectFailure().url());
		this.oidcAuthService = oidcAuthService;
		this.oidcAuthStateCache = oidcAuthStateCache;
		setRedirectStrategy(new CacheAwareRedirectStrategy());
	}

	@Override
	public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response,
										AuthenticationException exception) throws IOException {
		String state = request.getParameter(OAuth2ParameterNames.STATE);
		AuthState authState = state != null ? oidcAuthStateCache.getAuthState(state) : null;
		String requestedUrl = authState != null ? authState.failureUrl() : null;
		String failureUrl = oidcAuthService.failureRedirectUrl(requestedUrl);
		if (requestedUrl != null && !requestedUrl.equals(failureUrl)) {
			log.info("Dropping non-whitelisted OIDC failure redirect URL: {}", HttpUtil.sanitizeForLog(requestedUrl));
		}
		logFailure(request, exception);
		getRedirectStrategy().sendRedirect(request, response, failureUrl);
	}
}
