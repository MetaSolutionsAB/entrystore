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
import lombok.extern.slf4j.Slf4j;
import org.entrystore.rest.springboot.model.api.ErrorResponse;
import org.entrystore.rest.springboot.model.auth.SessionInfo;
import org.entrystore.rest.springboot.util.ErrorResponseWriter;
import org.jspecify.annotations.NonNull;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * Class reloads User properties on each HTTP request
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReloadUserPropertiesFilter extends OncePerRequestFilter {

	private final ESUserDetailsService userDetailsService;
	private final SessionRegistry sessionRegistry;
	private final ErrorResponseWriter errorResponseWriter;

	@Override
	protected void doFilterInternal(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response, @NonNull FilterChain filterChain)
		throws ServletException, IOException {

		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

		if (authentication != null && authentication.getPrincipal() instanceof ESUserSessionDetails esUserDetails) {
			try {
				// Get fresh User details
				ESUserSessionDetails updatedUser = (ESUserSessionDetails) userDetailsService.loadUserByUsername(esUserDetails.getUsername());
				Instant now = Instant.now();
				SessionInfo.SessionInfoBuilder sessionInfo = SessionInfo.builder()
						.userName(esUserDetails.getSessionInfo().userName())
						.loginTime(esUserDetails.getSessionInfo().loginTime())
						.loginExpiration(LocalDateTime.ofInstant(now.plusSeconds(request.getSession().getMaxInactiveInterval()), ZoneId.systemDefault()))
						.lastAccessTime(LocalDateTime.ofInstant(now, ZoneId.systemDefault()))
						.lastUsedIpAddress(request.getRemoteAddr())
						.lastUsedUserAgent(request.getHeader("User-Agent"))
						.loginTokenMaxAge(request.getSession().getMaxInactiveInterval());

				if (!updatedUser.isEnabled()) {
					SecurityContextHolder.clearContext();
					errorResponseWriter.writeErrorResponseAsJson(response, ErrorResponse.builder()
							.status(HttpStatus.FORBIDDEN.value())
							.path(request.getRequestURI())
							.error("User account is disabled.")
							.build());
					return;
				}

				updatedUser.setSessionInfo(sessionInfo.build());

				UsernamePasswordAuthenticationToken newAuth = new UsernamePasswordAuthenticationToken(updatedUser, updatedUser.getPassword(), updatedUser.getAuthorities());
				SecurityContextHolder.getContext().setAuthentication(newAuth);
				sessionRegistry.registerNewSession(request.getSession().getId(), updatedUser);
			} catch (UsernameNotFoundException e) {
				log.warn("User no longer found during session reload: {}", e.getMessage());
				SecurityContextHolder.clearContext();
				errorResponseWriter.writeErrorResponseAsJson(response, ErrorResponse.builder()
						.status(HttpStatus.UNAUTHORIZED.value())
						.path(request.getRequestURI())
						.error("User account is not found.")
						.build());
				return;
			} catch (ClassCastException e) {
				log.error("Unexpected principal type during user details reload", e);
				SecurityContextHolder.clearContext();
				errorResponseWriter.writeErrorResponseAsJson(response, ErrorResponse.builder()
						.status(HttpStatus.INTERNAL_SERVER_ERROR.value())
						.path(request.getRequestURI())
						.error("Authentication error.")
						.build());
				return;
			} catch (Exception e) {
				log.error("Failed to reload user details", e);
				SecurityContextHolder.clearContext();
				errorResponseWriter.writeErrorResponseAsJson(response, ErrorResponse.builder()
						.status(HttpStatus.INTERNAL_SERVER_ERROR.value())
						.path(request.getRequestURI())
						.error("Authentication error.")
						.build());
				return;
			}
		}

		filterChain.doFilter(request, response);
	}
}
