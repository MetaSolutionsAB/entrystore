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
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.jetbrains.annotations.NotNull;
import org.springframework.security.web.authentication.logout.CookieClearingLogoutHandler;
import org.springframework.security.web.session.SessionInformationExpiredEvent;
import org.springframework.security.web.session.SessionInformationExpiredStrategy;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Arrays;

/**
 * Lets a request whose session cookie names an unknown or expired session continue as a guest, the behaviour
 * of {@code entrystore.auth.cookie.invalid-token-error=false}. The stale cookie is expired on the response and
 * hidden from the rest of the chain, so neither the invalid-session strategy (401) nor the CSRF cookie check
 * reacts to it. As the expired-session strategy it does the same for a session the session registry marked as
 * expired (a deleted token). Registered by {@code SecurityConfig} only when that setting is false.
 */
public class StaleSessionCookieFilter extends OncePerRequestFilter implements SessionInformationExpiredStrategy {

	private final String sessionCookieName;
	private final CookieClearingLogoutHandler cookieClearingHandler;

	public StaleSessionCookieFilter(String sessionCookieName) {
		this.sessionCookieName = sessionCookieName;
		this.cookieClearingHandler = new CookieClearingLogoutHandler(sessionCookieName);
	}

	@Override
	protected void doFilterInternal(@NotNull HttpServletRequest request, @NotNull HttpServletResponse response,
									@NotNull FilterChain filterChain) throws ServletException, IOException {
		boolean stale = request.getRequestedSessionId() != null && !request.isRequestedSessionIdValid();
		filterChain.doFilter(stale ? asGuest(request, response) : request, response);
	}

	@Override
	public void onExpiredSessionDetected(SessionInformationExpiredEvent event) throws IOException, ServletException {
		event.getFilterChain().doFilter(asGuest(event.getRequest(), event.getResponse()), event.getResponse());
	}

	/** Expires the session cookie and returns the request with that cookie and the requested session id hidden. */
	private HttpServletRequest asGuest(HttpServletRequest request, HttpServletResponse response) {
		cookieClearingHandler.logout(request, response, null);
		return new HttpServletRequestWrapper(request) {
			@Override
			public Cookie[] getCookies() {
				Cookie[] cookies = super.getCookies();
				if (cookies == null) {
					return null;
				}
				Cookie[] remaining = Arrays.stream(cookies)
						.filter(cookie -> !sessionCookieName.equals(cookie.getName()))
						.toArray(Cookie[]::new);
				return remaining.length == 0 ? null : remaining;
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
		};
	}
}
