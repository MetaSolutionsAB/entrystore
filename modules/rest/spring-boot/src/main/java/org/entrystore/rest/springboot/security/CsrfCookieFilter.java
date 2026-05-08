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
import org.jetbrains.annotations.NotNull;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.WebUtils;

import java.io.IOException;
import java.util.Set;

/**
 * Forces deferred CsrfToken resolution so the XSRF-TOKEN cookie is emitted on responses a SPA
 * actually needs to read — without burning a SecureRandom UUID on every server-to-server GET.
 * The token is resolved when:
 *   1. the request method is unsafe (POST/PUT/PATCH/DELETE), so a SPA logging in via
 *      POST /auth/cookie or making any cookie-authenticated mutation gets/refreshes its token; or
 *   2. the request already carries an XSRF-TOKEN cookie, so an authenticated SPA's GETs keep
 *      the token cookie alive on the client.
 * <p>
 * Harvesters, monitoring probes, and Basic-auth API clients send GETs without the cookie and
 * therefore skip token generation entirely.
 */
@Component
public class CsrfCookieFilter extends OncePerRequestFilter {

	private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "TRACE", "OPTIONS");
	private static final String CSRF_COOKIE_NAME = "XSRF-TOKEN";

	@Override
	protected void doFilterInternal(@NotNull HttpServletRequest request, @NotNull HttpServletResponse response, @NotNull FilterChain filterChain)
			throws ServletException, IOException {

		if (shouldEmitToken(request)) {
			CsrfToken csrfToken = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
			if (csrfToken != null) {
				csrfToken.getToken();
			}
		}

		filterChain.doFilter(request, response);
	}

	private static boolean shouldEmitToken(HttpServletRequest request) {
		if (!SAFE_METHODS.contains(request.getMethod())) {
			return true;
		}
		return WebUtils.getCookie(request, CSRF_COOKIE_NAME) != null;
	}
}
