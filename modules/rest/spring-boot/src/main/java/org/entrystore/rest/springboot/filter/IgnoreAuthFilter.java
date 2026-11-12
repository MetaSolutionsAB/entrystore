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
import lombok.extern.slf4j.Slf4j;
import org.entrystore.rest.springboot.util.HttpUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Forces anonymous access for a request that names the {@code ignoreAuth} query parameter or, while
 * {@code entrystore.jsonp} is on, is a GET that names {@code callback}, on any path and whether or not
 * {@link JsonpCallbackFilter} wraps the response, by replacing the SecurityContext with an anonymous authentication
 * token. The session is not invalidated, so subsequent requests without these parameters remain authenticated.
 * Only the query string is read, so a multipart body is not parsed before the access checks.
 * <p>
 * {@code GuestRequestCredentialsHandler} decides by the same {@link #isRequested(String, String)} before the
 * session is resolved and hides the request's session cookie and credentials, so such a request does not touch
 * the session; this filter then sees a request without them and is the second line.
 */
@Slf4j
@Component
public class IgnoreAuthFilter extends OncePerRequestFilter {

	private final boolean jsonpEnabled;

	public IgnoreAuthFilter(@Value("${entrystore.jsonp:true}") boolean jsonpEnabled) {
		this.jsonpEnabled = jsonpEnabled;
	}

	/** @return whether the request is served as guest; see {@link #isRequested(String, String)} */
	public boolean isRequested(HttpServletRequest request) {
		return isRequested(request.getMethod(), request.getQueryString());
	}

	/**
	 * @return whether a request with this method and raw query string is served as guest, whatever session cookie
	 * or credentials it carries. The parameter names decide, so a value that cannot be decoded still counts.
	 */
	public boolean isRequested(String method, @Nullable String queryString) {
		return HttpUtil.hasQueryParameter(queryString, "ignoreAuth")
				|| (jsonpEnabled && JsonpCallbackFilter.isCallbackRequested(method, queryString));
	}

	@Override
	protected void doFilterInternal(@NotNull HttpServletRequest request, @NotNull HttpServletResponse response, @NotNull FilterChain filterChain)
			throws ServletException, IOException {

		if (isRequested(request)) {
			log.debug("Forcing anonymous access due to an ignoreAuth or JSONP callback request parameter");
			SecurityContext originalContext = SecurityContextHolder.getContext();
			SecurityContext anonymousContext = SecurityContextHolder.createEmptyContext();
			anonymousContext.setAuthentication(
					new AnonymousAuthenticationToken("ignoreAuth", "anonymousUser",
							AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS"))
			);
			SecurityContextHolder.setContext(anonymousContext);
			try {
				filterChain.doFilter(request, response);
			} finally {
				SecurityContextHolder.setContext(originalContext);
			}
			return;
		}

		filterChain.doFilter(request, response);
	}
}
