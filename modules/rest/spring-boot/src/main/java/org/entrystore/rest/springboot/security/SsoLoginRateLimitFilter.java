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

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.entrystore.rest.springboot.service.ClientAddressRateLimiter;
import org.entrystore.rest.springboot.service.OidcAuthService;
import org.entrystore.rest.springboot.service.SamlAuthService;
import org.entrystore.rest.springboot.service.auth.OidcLoginRateLimiter;
import org.entrystore.rest.springboot.service.auth.SamlLoginRateLimiter;
import org.entrystore.rest.springboot.util.HttpUtil;
import org.jetbrains.annotations.NotNull;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestRedirectFilter;
import org.springframework.security.web.RedirectStrategy;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.function.UnaryOperator;

/**
 * Applies a per-client rate limiter to an SSO protocol's login starts before Spring's login-start filter writes the
 * request caches. A start over the limit is redirected to its {@code failureurl} if the protocol's redirect whitelist
 * admits it, else to the protocol's default failure URL.
 */
final class SsoLoginRateLimitFilter extends OncePerRequestFilter {

	private final RequestMatcher loginStart;
	private final ClientAddressRateLimiter rateLimiter;
	private final UnaryOperator<String> failureRedirectUrl;
	private final RedirectStrategy redirectStrategy = new CacheAwareRedirectStrategy();

	private SsoLoginRateLimitFilter(String loginStartPattern, ClientAddressRateLimiter rateLimiter,
			UnaryOperator<String> failureRedirectUrl) {
		this.loginStart = PathPatternRequestMatcher.withDefaults().matcher(loginStartPattern);
		this.rateLimiter = rateLimiter;
		this.failureRedirectUrl = failureRedirectUrl;
	}

	/**
	 * For {@code Saml2WebSsoAuthenticationRequestFilter}: every path under {@code /saml2/authenticate}, which covers
	 * both forms Spring serves, {@code /saml2/authenticate/{registrationId}} and {@code ?registrationId=}, and so also
	 * a login started at {@code GET /auth/saml}, which redirects there.
	 */
	static SsoLoginRateLimitFilter forSaml(SamlLoginRateLimiter rateLimiter, SamlAuthService samlAuthService) {
		return new SsoLoginRateLimitFilter("/saml2/authenticate/**", rateLimiter, samlAuthService::failureRedirectUrl);
	}

	/**
	 * For {@link OAuth2AuthorizationRequestRedirectFilter}: every path under the default authorization base URI
	 * {@code /oauth2/authorization}, which {@code OidcAuthorizationRequestResolver} also uses, and so also a login
	 * started at {@code GET /auth/oidc}, which redirects there. A different base URI there must be set here too, or
	 * OIDC login starts are no longer limited.
	 */
	static SsoLoginRateLimitFilter forOidc(OidcLoginRateLimiter rateLimiter, OidcAuthService oidcAuthService) {
		return new SsoLoginRateLimitFilter(OAuth2AuthorizationRequestRedirectFilter.DEFAULT_AUTHORIZATION_REQUEST_BASE_URI
				+ "/**", rateLimiter, oidcAuthService::failureRedirectUrl);
	}

	@Override
	protected boolean shouldNotFilter(@NotNull HttpServletRequest request) {
		return !loginStart.matches(request);
	}

	@Override
	protected void doFilterInternal(@NotNull HttpServletRequest request, @NotNull HttpServletResponse response,
									@NotNull FilterChain filterChain) throws ServletException, IOException {
		if (rateLimiter.tryAcquirePermit(request.getRemoteAddr())) {
			filterChain.doFilter(request, response);
			return;
		}
		// The query, not getParameter: this path may be posted to, and a multipart body must stay unread
		String failureUrl = failureRedirectUrl.apply(HttpUtil.getQueryParameter(request, "failureurl"));
		redirectStrategy.sendRedirect(request, response, failureUrl);
	}
}
