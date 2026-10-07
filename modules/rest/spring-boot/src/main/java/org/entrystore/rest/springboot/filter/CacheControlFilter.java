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
 * Marks responses to authenticated requests {@link #CACHE_CONTROL_AUTHENTICATED} unless a prior filter or
 * controller has already set {@code Cache-Control}: {@code private} keeps them out of shared caches (CDNs, forward
 * proxies), which guards against shared-cache poisoning when {@code X-Forwarded-Prefix} is spoofable upstream, and
 * {@code no-cache} makes the browser revalidate each time, so a conditional GET can answer 304 and an edit is never
 * shown stale.
 * <p>
 * A request is treated as authenticated when it carries the session cookie or an
 * {@code Authorization: Basic} header. The filter yields to any {@code Cache-Control}
 * a prior filter or controller already set, so a deliberate {@code no-store} from the
 * Basic-Auth challenge entry point or an explicit cache directive from a controller
 * wins. A controller running after this filter can still override with
 * {@code setHeader}.
 * <p>
 * After the chain runs, the filter marks a response that sets the session cookie via {@code Set-Cookie}
 * {@link #CACHE_CONTROL_CREDENTIALS} when the response has not yet committed, since it carries a credential that
 * no cache may store.
 * <p>
 * Responses that never reach this filter or commit inside the chain call {@link #markSessionCookieResponse}
 * themselves: the form-login {@code /auth/cookie} response, whose Spring Security filter ends the chain, through
 * {@code FormLoginAuthenticationSuccessHandler}; the SAML, CAS and OIDC success redirects, which commit inside
 * {@code sendRedirect}, through {@code CacheAwareRedirectStrategy} (see {@code SecurityConfig}); and any response
 * that expires the session cookie, through {@code AuthTokenCookies}.
 */
@Component
public class CacheControlFilter extends OncePerRequestFilter {

	/**
	 * {@code Cache-Control} of responses to authenticated requests. Referenced by the unit and integration tests that
	 * assert on it, so filter and test expectations cannot drift.
	 */
	public static final String CACHE_CONTROL_AUTHENTICATED = "private, no-cache";

	/**
	 * {@code Cache-Control} of responses that carry a credential, so no cache may store them: those that set or expire
	 * the session cookie, the SSO redirects that may do so, and bodies with session ids or confirmation tokens.
	 */
	public static final String CACHE_CONTROL_CREDENTIALS = "private, no-store";

	/**
	 * {@code Cache-Control} the entry and resource controllers set on responses to anonymous requests, so guests
	 * revalidate as authenticated users do. This filter leaves other anonymous responses without one.
	 */
	public static final String CACHE_CONTROL_ANONYMOUS = "no-cache";

	private static final String BASIC_AUTH_SCHEME_PREFIX = "Basic ";

	private final String sessionCookieName;
	private final String sessionCookieAttributePrefix;

	public CacheControlFilter(@Value("${server.servlet.session.cookie.name:auth_token}") String sessionCookieName) {
		this.sessionCookieName = sessionCookieName;
		this.sessionCookieAttributePrefix = sessionCookieName + "=";
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
		if (responseEstablishesSession(response)) {
			markSessionCookieResponse(response);
		}
	}

	/**
	 * Sets {@link #CACHE_CONTROL_CREDENTIALS} on a response that has not committed. It replaces only
	 * {@link #CACHE_CONTROL_AUTHENTICATED}, which this filter stamps on a request carrying an old session cookie, so
	 * the result does not depend on whether this filter ran first; any other {@code Cache-Control} was set deliberately
	 * and is kept.
	 */
	public static void markSessionCookieResponse(HttpServletResponse response) {
		String cacheControl = response.getHeader(HttpHeaders.CACHE_CONTROL);
		if (!response.isCommitted() && (cacheControl == null || CACHE_CONTROL_AUTHENTICATED.equals(cacheControl))) {
			response.setHeader(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL_CREDENTIALS);
		}
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

	private boolean responseEstablishesSession(HttpServletResponse response) {
		// RFC 6265 cookie names are case-sensitive — compare verbatim against the configured name.
		for (String setCookie : response.getHeaders(HttpHeaders.SET_COOKIE)) {
			if (setCookie != null && setCookie.startsWith(sessionCookieAttributePrefix)) {
				return true;
			}
		}
		return false;
	}
}
