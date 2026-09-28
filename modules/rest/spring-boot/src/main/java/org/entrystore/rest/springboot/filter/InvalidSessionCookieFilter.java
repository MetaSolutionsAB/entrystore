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

package org.entrystore.rest.springboot.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.AuthenticationTrustResolverImpl;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/** Clears stale session cookies in guest-fallback mode without discarding another authentication mechanism. */
public final class InvalidSessionCookieFilter extends OncePerRequestFilter {

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		var authentication = SecurityContextHolder.getContext().getAuthentication();
		if (request.isRequestedSessionIdFromCookie() && !request.isRequestedSessionIdValid()
				&& !new AuthenticationTrustResolverImpl().isAuthenticated(authentication)) {
			var settings = request.getServletContext().getSessionCookieConfig();
			var cookie = new Cookie(settings.getName() == null ? "auth_token" : settings.getName(), "");
			String path = settings.getPath();
			cookie.setPath(path != null ? path : request.getContextPath().isEmpty() ? "/" : request.getContextPath());
			if (settings.getDomain() != null) cookie.setDomain(settings.getDomain());
			cookie.setHttpOnly(settings.isHttpOnly());
			cookie.setSecure(settings.isSecure());
			cookie.setMaxAge(0);
			response.addCookie(cookie);
		}
		chain.doFilter(request, response);
	}
}
