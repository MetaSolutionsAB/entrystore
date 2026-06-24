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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.stereotype.Component;
import org.springframework.web.util.WebUtils;

import java.util.Set;

/**
 * Matches requests that must carry a CSRF token. Three predicates compose:
 *   1. method is unsafe (POST/PUT/PATCH/DELETE)
 *   2. request carries the session cookie (auth_token by default) — Basic-auth and
 *      other token-based flows that don't establish a session are bypassed
 *   3. path is not on the explicit exemption list (login, signup, password reset,
 *      and the SAML ACS callback)
 *
 * <p>Mutating endpoints under {@code /management/**} (logging, solr reindex) are
 * intentionally NOT exempt: a cookie-authenticated user — including a regular user able
 * to POST {@code /management/solr} — is in scope for CSRF. The path-pattern protection
 * extends to any future mutating {@code /management/**} endpoint without needing matcher
 * changes. Read-only actuator/status calls remain exempt by virtue of being GETs (see
 * {@link #SAFE_METHODS}).
 * Ops scripts that drive these endpoints should authenticate via Basic auth (which sets
 * no session cookie and therefore skips CSRF) or forward {@code X-XSRF-TOKEN} like other
 * cookie-authenticated clients.
 */
@Component
public class CsrfRequestMatcher implements RequestMatcher {

	// Shared with CsrfCookieFilter — both filter and matcher agree on the same notion of "safe".
	public static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "TRACE", "OPTIONS");

	private final String sessionCookieName;
	private final RequestMatcher exemptPaths;

	public CsrfRequestMatcher(@Value("${server.servlet.session.cookie.name:auth_token}") String sessionCookieName) {
		this.sessionCookieName = sessionCookieName;
		var pathMatcher = PathPatternRequestMatcher.withDefaults();
		this.exemptPaths = new OrRequestMatcher(
				pathMatcher.matcher(HttpMethod.POST, "/auth/cookie"),
				pathMatcher.matcher(HttpMethod.POST, "/auth/signup"),
				pathMatcher.matcher(HttpMethod.POST, "/auth/signup/confirm"),
				pathMatcher.matcher(HttpMethod.POST, "/auth/pwreset"),
				pathMatcher.matcher(HttpMethod.POST, "/auth/pwreset/confirm"),
				pathMatcher.matcher(HttpMethod.POST, "/login/saml2/sso/**")
		);
	}

	@Override
	public boolean matches(HttpServletRequest request) {
		if (SAFE_METHODS.contains(request.getMethod())) {
			return false;
		}
		if (WebUtils.getCookie(request, sessionCookieName) == null) {
			return false;
		}
		return !exemptPaths.matches(request);
	}
}
