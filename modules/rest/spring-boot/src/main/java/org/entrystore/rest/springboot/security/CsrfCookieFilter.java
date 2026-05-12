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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.WebUtils;

import java.io.IOException;

/**
 * Forces deferred CsrfToken resolution so the XSRF-TOKEN cookie is emitted on responses a SPA
 * actually needs to read — without burning a SecureRandom UUID on every server-to-server GET.
 * The token is resolved when:
 *   1. the request method is unsafe (POST/PUT/PATCH/DELETE), so a SPA logging in via
 *      POST /auth/cookie or making any cookie-authenticated mutation gets/refreshes its token;
 *   2. the request already carries an XSRF-TOKEN cookie, so an authenticated SPA's GETs keep
 *      the token cookie alive on the client; or
 *   3. the request carries the session cookie but no XSRF-TOKEN cookie — bootstrap path for
 *      sessions that pre-date this rollout, or whose token cookie was cleared while the session
 *      cookie survived. Without this arm, such sessions would be stuck unable to mutate or log
 *      out until they re-login or clear cookies, because every mutation would be rejected by
 *      CsrfFilter before this filter could mint a replacement token.
 * <p>
 * Harvesters, monitoring probes, and Basic-auth API clients send GETs without either cookie and
 * therefore skip token generation entirely.
 */
@Component
public class CsrfCookieFilter extends OncePerRequestFilter {

	private final String sessionCookieName;
	private final String csrfCookieName;

	public CsrfCookieFilter(@Value("${server.servlet.session.cookie.name:auth_token}") String sessionCookieName,
							@Value("${entrystore.csrf.cookie-name:XSRF-TOKEN}") String csrfCookieName) {
		this.sessionCookieName = sessionCookieName;
		this.csrfCookieName = csrfCookieName;
	}

	@Override
	protected void doFilterInternal(@NotNull HttpServletRequest request, @NotNull HttpServletResponse response, @NotNull FilterChain filterChain)
			throws ServletException, IOException {

		if (shouldEmitToken(request)) {
			CsrfToken csrfToken = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
			if (csrfToken != null) {
				csrfToken.getToken();
			} else if (logger.isDebugEnabled()) {
				// Defensive — surfaces filter-ordering drift (Spring upgrade, profile that disables CsrfFilter,
				// attribute key change). Without this branch the SPA silently never sees Set-Cookie: XSRF-TOKEN
				// and every subsequent cookie-auth mutation fails 401 with no log explaining why.
				logger.debug("CsrfToken attribute missing on " + request.getMethod() + " " + request.getRequestURI()
						+ "; XSRF-TOKEN cookie will not be emitted");
			}
		}

		filterChain.doFilter(request, response);
	}

	private boolean shouldEmitToken(HttpServletRequest request) {
		if (!CsrfRequestMatcher.SAFE_METHODS.contains(request.getMethod())) {
			return true;
		}
		if (WebUtils.getCookie(request, csrfCookieName) != null) {
			return true;
		}
		return WebUtils.getCookie(request, sessionCookieName) != null;
	}
}
