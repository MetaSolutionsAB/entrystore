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
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Ends a session whose login has passed its fixed lifetime. Registered only when
 * {@code entrystore.auth.cookie.refresh-expiration-on-access} is off, before the security context is loaded, so the
 * request then carries an invalid session id and is answered like any expired session (401 or guest, cookie expired).
 */
@RequiredArgsConstructor
class SessionLifetimeFilter extends OncePerRequestFilter {

	private final AuthTokenCookies authTokenCookies;

	@Override
	protected void doFilterInternal(@NotNull HttpServletRequest request, @NotNull HttpServletResponse response,
									@NotNull FilterChain filterChain) throws ServletException, IOException {
		HttpSession session = request.getSession(false);
		if (session != null) {
			try {
				if (authTokenCookies.isLoginExpired(session)) {
					session.invalidate();
				}
			} catch (IllegalStateException alreadyInvalidated) {
				// a concurrent request ended it first
			}
		}
		filterChain.doFilter(request, response);
	}
}
