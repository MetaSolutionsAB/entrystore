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
import lombok.extern.slf4j.Slf4j;
import org.entrystore.PrincipalManager;
import org.entrystore.User;
import org.entrystore.rest.springboot.model.api.ErrorResponse;
import org.entrystore.rest.springboot.util.HttpUtil;
import org.jetbrains.annotations.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.cas.authentication.CasAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.saml2.provider.service.authentication.Saml2Authentication;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.URI;

/**
 * Class sets the user URI in PrincipalManager after successful authentication
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SetUserURIAfterAuthenticationFilter extends OncePerRequestFilter {

	private final PrincipalManager pm;
	private final ESUserDetailsService userDetailsService;

	@Override
	protected void doFilterInternal(@NotNull HttpServletRequest request, @NotNull HttpServletResponse response, @NotNull FilterChain filterChain)
			throws ServletException, IOException {

		// Reset to guest before consulting Spring Security so the PrincipalManager
		// ThreadLocal cannot leak an authenticated URI from a previous request that
		// ran on this Jetty worker thread.
		URI guestUri = guestUserUri();
		if (guestUri == null) {
			// Servlet filters run before the DispatcherServlet, so AppExceptionHandler
			// cannot see anything thrown from here — write the 500 directly.
			log.error("PrincipalManager has no guest user URI; cannot reset request principal");
			HttpUtil.writeErrorResponseAsJson(response, ErrorResponse.builder()
					.status(HttpStatus.INTERNAL_SERVER_ERROR.value())
					.path(request.getRequestURI())
					.error("PrincipalManager is not initialized")
					.build());
			return;
		}
		pm.setAuthenticatedUserURI(guestUri);

		Authentication auth = SecurityContextHolder.getContext().getAuthentication();

		if (auth != null && auth.isAuthenticated() && !(auth instanceof AnonymousAuthenticationToken)) {
			String externalAuthType = switch (auth) {
				case Saml2Authentication ignored -> "SAML";
				case CasAuthenticationToken ignored -> "CAS";
				default -> null;
			};
			if (externalAuthType != null) {
				String username = auth.getName();
				User user = userDetailsService.loadUser(username);
				if (user == null) {
					log.warn("Authenticated {} user '{}' not found in EntryStore, denying access",
							externalAuthType, HttpUtil.sanitizeForLog(username));
					clearSessionAndContext(request);
					setForbiddenResponse(request, response,
							"Authenticated " + externalAuthType + " user not found in EntryStore");
					return;
				}
				URI userUri = user.getURI();
				if (userUri == null) {
					log.warn("Authenticated {} user '{}' has no URI in EntryStore, denying access",
							externalAuthType, HttpUtil.sanitizeForLog(username));
					clearSessionAndContext(request);
					setForbiddenResponse(request, response,
							"Authenticated " + externalAuthType + " user has no URI in EntryStore");
					return;
				}
				pm.setAuthenticatedUserURI(userUri);
			} else if (auth.getPrincipal() instanceof ESUserSessionDetails esUser) {
				User esUserEntity = esUser.getEsUser();
				URI userUri = esUserEntity == null ? null : esUserEntity.getURI();
				if (userUri == null) {
					log.warn("Cookie-authenticated session for '{}' has no usable EntryStore user, denying access",
							HttpUtil.sanitizeForLog(esUser.getUsername()));
					clearSessionAndContext(request);
					setForbiddenResponse(request, response,
							"Cookie-authenticated user no longer exists in EntryStore");
					return;
				}
				pm.setAuthenticatedUserURI(userUri);
			} else {
				Object principal = auth.getPrincipal();
				String principalType = principal == null ? "<null>" : principal.getClass().getName();
				log.warn("Authenticated user has unrecognized principal type: '{}'. Denying access.", principalType);
				clearSessionAndContext(request);
				setForbiddenResponse(request, response, "Unrecognized principal type");
				return;
			}
		}

		filterChain.doFilter(request, response);
	}

	private URI guestUserUri() {
		User guest = pm.getGuestUser();
		return guest == null ? null : guest.getURI();
	}

	private static void clearSessionAndContext(HttpServletRequest request) {
		SecurityContextHolder.clearContext();
		HttpSession session = request.getSession(false);
		if (session != null) {
			try {
				session.invalidate();
			} catch (IllegalStateException e) {
				// Concurrent request or async dispatch already invalidated this session.
				log.debug("Session was already invalidated when filter tried to invalidate it", e);
			}
		}
	}

	private void setForbiddenResponse(HttpServletRequest request, HttpServletResponse response, String message) throws IOException {
		HttpUtil.writeErrorResponseAsJson(response, ErrorResponse.builder()
				.status(HttpStatus.FORBIDDEN.value())
				.path(request.getRequestURI())
				.error(message)
				.build());
	}
}
