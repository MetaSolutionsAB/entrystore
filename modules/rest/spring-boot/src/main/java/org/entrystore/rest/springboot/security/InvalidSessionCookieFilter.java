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
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.http.HttpHeaders;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Expires an unknown or expired session cookie and lets the request continue as guest. Registered only when
 * {@code entrystore.auth.cookie.invalid-token-error} is false; otherwise the invalid-session strategy in
 * {@link SecurityConfig} expires the cookie and answers 401.
 * <p>
 * A guest GET or HEAD continues without {@code If-None-Match}, {@code If-Modified-Since}, {@code Range} and
 * {@code If-Range}: their validators may come from a response the session was allowed to see, and a 304 or 206
 * would let the browser show or complete that body for the guest. {@code If-Match} and {@code If-Unmodified-Since}
 * stay, since on a GET or HEAD Spring ignores the one and the other can only give 412, and writes keep all their
 * preconditions, so a conflicting write still gets 412.
 */
@RequiredArgsConstructor
class InvalidSessionCookieFilter extends OncePerRequestFilter {

	private static final Set<String> READ_METHODS = Set.of("GET", "HEAD");

	private final AuthTokenCookies authTokenCookies;

	@Override
	protected void doFilterInternal(@NotNull HttpServletRequest request, @NotNull HttpServletResponse response,
									@NotNull FilterChain filterChain) throws ServletException, IOException {
		if (request.getRequestedSessionId() != null && !request.isRequestedSessionIdValid()) {
			authTokenCookies.expireAll(request, response);
			if (READ_METHODS.contains(request.getMethod())) {
				filterChain.doFilter(new UnconditionalRequest(request), response);
				return;
			}
		}
		filterChain.doFilter(request, response);
	}

	/** Hides the revalidation and range request headers, so the request is answered in full. */
	static final class UnconditionalRequest extends HttpServletRequestWrapper {

		private static final Set<String> HIDDEN_HEADERS = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);

		static {
			HIDDEN_HEADERS.addAll(List.of(HttpHeaders.IF_NONE_MATCH, HttpHeaders.IF_MODIFIED_SINCE,
					HttpHeaders.RANGE, HttpHeaders.IF_RANGE));
		}

		UnconditionalRequest(HttpServletRequest request) {
			super(request);
		}

		@Override
		public String getHeader(String name) {
			return HIDDEN_HEADERS.contains(name) ? null : super.getHeader(name);
		}

		@Override
		public Enumeration<String> getHeaders(String name) {
			return HIDDEN_HEADERS.contains(name) ? Collections.emptyEnumeration() : super.getHeaders(name);
		}

		@Override
		public Enumeration<String> getHeaderNames() {
			return Collections.enumeration(Collections.list(super.getHeaderNames()).stream()
					.filter(name -> !HIDDEN_HEADERS.contains(name))
					.toList());
		}

		@Override
		public long getDateHeader(String name) {
			return HIDDEN_HEADERS.contains(name) ? -1 : super.getDateHeader(name);
		}
	}
}
