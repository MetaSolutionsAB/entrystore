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
import lombok.RequiredArgsConstructor;
import org.entrystore.rest.springboot.service.SamlAuthService;
import org.entrystore.rest.springboot.service.auth.SamlLoginRateLimiter;
import org.entrystore.rest.springboot.util.HttpUtil;
import org.jetbrains.annotations.NotNull;
import org.springframework.security.web.RedirectStrategy;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Applies {@link SamlLoginRateLimiter} to SAML login starts before {@code Saml2WebSsoAuthenticationRequestFilter}
 * writes the request caches. It matches every path under {@code /saml2/authenticate}, which covers both forms Spring
 * serves, {@code /saml2/authenticate/{registrationId}} and {@code ?registrationId=}. A login started at
 * {@code GET /auth/saml} is counted here too, as that redirects to {@code /saml2/authenticate}. A start over the limit
 * is redirected to its {@code failureurl} if the redirect whitelist admits it, else to the default failure URL, as a
 * failed SAML login is.
 */
@RequiredArgsConstructor
class SamlLoginRateLimitFilter extends OncePerRequestFilter {

	private static final RequestMatcher LOGIN_START =
			PathPatternRequestMatcher.withDefaults().matcher("/saml2/authenticate/**");

	private final SamlLoginRateLimiter rateLimiter;
	private final SamlAuthService samlAuthService;
	private final RedirectStrategy redirectStrategy = new CacheAwareRedirectStrategy();

	@Override
	protected boolean shouldNotFilter(@NotNull HttpServletRequest request) {
		return !LOGIN_START.matches(request);
	}

	@Override
	protected void doFilterInternal(@NotNull HttpServletRequest request, @NotNull HttpServletResponse response,
									@NotNull FilterChain filterChain) throws ServletException, IOException {
		if (rateLimiter.tryAcquirePermit(request.getRemoteAddr())) {
			filterChain.doFilter(request, response);
			return;
		}
		// The query, not getParameter: this path may be posted to, and a multipart body must stay unread
		String failureUrl = samlAuthService.failureRedirectUrl(HttpUtil.getQueryParameter(request, "failureurl"));
		redirectStrategy.sendRedirect(request, response, failureUrl);
	}
}
