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
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Expires an unknown or expired session cookie on the requests it applies to and lets them continue as guest, with
 * the requested session id hidden so that {@code SessionManagementFilter} does not answer them 401. Applies to every
 * request when {@code entrystore.auth.cookie.invalid-token-error} is false, otherwise only to requests that need no
 * existing session; see {@link SecurityConfig}.
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

	private final RequestMatcher appliesTo;

	@Override
	protected void doFilterInternal(@NotNull HttpServletRequest request, @NotNull HttpServletResponse response,
									@NotNull FilterChain filterChain) throws ServletException, IOException {
		if (request.getRequestedSessionId() != null && !request.isRequestedSessionIdValid()
				&& appliesTo.matches(request)) {
			authTokenCookies.expireAll(request, response);
			filterChain.doFilter(READ_METHODS.contains(request.getMethod())
					? new UnconditionalRequest(request)
					: new SessionlessRequest(request), response);
			return;
		}
		filterChain.doFilter(request, response);
	}

	/** Hides the requested session id, so the request reads as one that carried no session cookie. */
	static class SessionlessRequest extends HttpServletRequestWrapper {

		SessionlessRequest(HttpServletRequest request) {
			super(request);
		}

		@Override
		public String getRequestedSessionId() {
			return null;
		}

		@Override
		public boolean isRequestedSessionIdValid() {
			return false;
		}

		@Override
		public boolean isRequestedSessionIdFromCookie() {
			return false;
		}

		@Override
		public boolean isRequestedSessionIdFromURL() {
			return false;
		}
	}

	/** Also hides the revalidation and range request headers, so the request is answered in full. */
	static final class UnconditionalRequest extends SessionlessRequest {

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
