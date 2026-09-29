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
import org.jetbrains.annotations.NotNull;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Expires an unknown or expired session cookie and lets the request continue as guest. Registered only when
 * {@code entrystore.auth.cookie.invalid-token-error} is false; otherwise the invalid-session strategy in
 * {@link SecurityConfig} expires the cookie and answers 401.
 */
@RequiredArgsConstructor
class InvalidSessionCookieFilter extends OncePerRequestFilter {

	private final AuthTokenCookies authTokenCookies;

	@Override
	protected void doFilterInternal(@NotNull HttpServletRequest request, @NotNull HttpServletResponse response,
									@NotNull FilterChain filterChain) throws ServletException, IOException {
		if (request.getRequestedSessionId() != null && !request.isRequestedSessionIdValid()) {
			authTokenCookies.expireAll(request, response);
		}
		filterChain.doFilter(request, response);
	}
}
