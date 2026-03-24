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

package org.entrystore.rest.standalone.springboot.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * When the {@code ignoreAuth} query parameter is present, this filter forces anonymous access
 * for the current request by replacing the SecurityContext with an anonymous authentication token.
 * The session is not invalidated, so subsequent requests without {@code ?ignoreAuth} remain authenticated.
 */
@Slf4j
@Component
public class IgnoreAuthFilter extends OncePerRequestFilter {

	@Override
	protected void doFilterInternal(@NotNull HttpServletRequest request, @NotNull HttpServletResponse response, @NotNull FilterChain filterChain)
			throws ServletException, IOException {

		if (request.getParameter("ignoreAuth") != null) {
			log.debug("Forcing anonymous access due to ignoreAuth request parameter");
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
