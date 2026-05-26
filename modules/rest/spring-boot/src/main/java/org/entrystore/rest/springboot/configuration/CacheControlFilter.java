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

package org.entrystore.rest.springboot.configuration;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.jetbrains.annotations.NotNull;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.WebUtils;

import java.io.IOException;

/**
 * Marks responses to authenticated requests as uncacheable by shared infrastructure
 * (CDNs, forward proxies) unless a prior filter or controller has already set
 * {@code Cache-Control} — defensive hardening against shared-cache poisoning when
 * {@code X-Forwarded-Prefix} is spoofable upstream.
 * <p>
 * A request is treated as authenticated when it carries the session cookie or an
 * {@code Authorization: Basic} header. The filter yields to any {@code Cache-Control}
 * a prior filter or controller already set, so a deliberate {@code no-store} from the
 * Basic-Auth challenge entry point or an explicit cache directive from a controller
 * wins. A controller running after this filter can still override with
 * {@code setHeader}.
 */
@Component
public class CacheControlFilter extends OncePerRequestFilter {

	static final String CACHE_CONTROL_AUTHENTICATED = "private, no-store";

	private static final String BASIC_AUTH_SCHEME_PREFIX = "Basic ";

	private final String sessionCookieName;

	public CacheControlFilter(@Value("${server.servlet.session.cookie.name:auth_token}") String sessionCookieName) {
		this.sessionCookieName = sessionCookieName;
	}

	@Override
	protected void doFilterInternal(@NotNull HttpServletRequest request,
									@NotNull HttpServletResponse response,
									@NotNull FilterChain filterChain)
			throws ServletException, IOException {
		if (isAuthenticatedRequest(request)
				&& response.getHeader(HttpHeaders.CACHE_CONTROL) == null) {
			response.setHeader(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL_AUTHENTICATED);
		}
		filterChain.doFilter(request, response);
	}

	private boolean isAuthenticatedRequest(HttpServletRequest request) {
		if (WebUtils.getCookie(request, sessionCookieName) != null) {
			return true;
		}
		String authHeader = request.getHeader(HttpHeaders.AUTHORIZATION);
		// RFC 7235: auth-scheme is case-insensitive.
		return authHeader != null
				&& authHeader.regionMatches(true, 0, BASIC_AUTH_SCHEME_PREFIX, 0, BASIC_AUTH_SCHEME_PREFIX.length());
	}
}
