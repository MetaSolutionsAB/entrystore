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
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.entrystore.rest.springboot.model.api.ErrorResponse;
import org.entrystore.rest.springboot.model.auth.SessionInfo;
import org.entrystore.rest.springboot.security.AuthTokenCookies;
import org.entrystore.rest.springboot.security.ESUserDetailsService;
import org.entrystore.rest.springboot.security.ESUserSessionDetails;
import org.entrystore.rest.springboot.util.ErrorResponseWriter;
import org.entrystore.rest.springboot.util.HttpUtil;
import org.jspecify.annotations.NonNull;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.session.SessionInformation;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * Reloads the user's properties on each request. A disabled or deleted user's session ends and its cookie is expired,
 * as 5.x removed their tokens, so the long-lived cookie does not keep failing.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReloadUserPropertiesFilter extends OncePerRequestFilter {

	private final ESUserDetailsService userDetailsService;
	private final SessionRegistry sessionRegistry;
	private final ErrorResponseWriter errorResponseWriter;
	private final AuthTokenCookies authTokenCookies;

	@Override
	protected void doFilterInternal(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response, @NonNull FilterChain filterChain)
		throws ServletException, IOException {

		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

		if (authentication != null && authentication.getPrincipal() instanceof ESUserSessionDetails esUserDetails) {
			try {
				// Get fresh User details
				ESUserSessionDetails updatedUser = (ESUserSessionDetails) userDetailsService.loadUserByUsername(esUserDetails.getUsername());
				Instant now = Instant.now();
				// HTTP Basic requests have no session and must not get one
				HttpSession session = request.getSession(false);
				SessionInfo.SessionInfoBuilder sessionInfo = SessionInfo.builder()
						.userName(esUserDetails.getSessionInfo().userName())
						.loginTime(esUserDetails.getSessionInfo().loginTime())
						.loginExpiration(session != null
								? LocalDateTime.ofInstant(authTokenCookies.expiry(session), ZoneId.systemDefault())
								: null)
						.lastAccessTime(LocalDateTime.ofInstant(now, ZoneId.systemDefault()))
						.lastUsedIpAddress(request.getRemoteAddr())
						.lastUsedUserAgent(request.getHeader("User-Agent"))
						.loginTokenMaxAge(session != null ? session.getMaxInactiveInterval() : 0);

				if (!updatedUser.isEnabled()) {
					HttpUtil.clearAuthenticatedSession(request);
					authTokenCookies.expireAll(request, response);
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
				if (session != null) {
					updateRegisteredSession(session.getId(), updatedUser);
				}
			} catch (UsernameNotFoundException e) {
				log.warn("User no longer found during session reload: {}", e.getMessage());
				HttpUtil.clearAuthenticatedSession(request);
				authTokenCookies.expireAll(request, response);
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

	/**
	 * Updates the session info that /auth/tokens reports on the registered session. The registered entry is kept, not
	 * registered anew, because registering replaces it with an unexpired one and would undo a revocation (user
	 * disabled, password changed, token deleted) made by a concurrent request.
	 */
	private void updateRegisteredSession(String sessionId, ESUserSessionDetails updatedUser) {
		SessionInformation registered = sessionRegistry.getSessionInformation(sessionId);
		if (registered == null) {
			sessionRegistry.registerNewSession(sessionId, updatedUser);
			return;
		}
		if (registered.getPrincipal() instanceof ESUserSessionDetails registeredUser) {
			registeredUser.setSessionInfo(updatedUser.getSessionInfo());
		}
		registered.refreshLastRequest();
	}
}
