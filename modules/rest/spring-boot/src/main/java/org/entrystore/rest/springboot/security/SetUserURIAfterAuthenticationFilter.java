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

		Authentication auth = SecurityContextHolder.getContext().getAuthentication();

		if (auth != null && auth.isAuthenticated()) {
			if (auth instanceof AnonymousAuthenticationToken) {
				pm.setAuthenticatedUserURI(pm.getGuestUser().getURI());
			} else if (auth instanceof Saml2Authentication || auth instanceof CasAuthenticationToken) {
				String authType = auth instanceof Saml2Authentication ? "SAML" : "CAS";
				String username = auth.getName();
				User user = userDetailsService.loadUser(username);
				if (user != null) {
					pm.setAuthenticatedUserURI(user.getURI());
				} else {
					log.warn("Authenticated {} user '{}' not found in EntryStore, denying access", authType, username);
					// Invalidate the session so the client re-authenticates instead of
					// hitting this branch on every subsequent request.
					SecurityContextHolder.clearContext();
					HttpSession session = request.getSession(false);
					if (session != null) {
						session.invalidate();
					}
					setForbiddenResponse(request, response, "Authenticated " + authType + " user not found in EntryStore");
					return;
				}
			} else if (auth.getPrincipal() instanceof ESUserSessionDetails esUser && esUser.getEsUser() != null) {
				// Cookie has been verified and user is authenticated
				pm.setAuthenticatedUserURI(esUser.getEsUser().getURI());
			} else {
				log.warn("Authenticated user has unrecognized principal type: '{}'. Denying access.", auth.getPrincipal().getClass().getName());
				setForbiddenResponse(request, response, "Unrecognized principal type");
				return;
			}
		}

		filterChain.doFilter(request, response);
	}

	private void setForbiddenResponse(HttpServletRequest request, HttpServletResponse response, String message) throws IOException {
		HttpUtil.writeErrorResponseAsJson(response, ErrorResponse.builder()
				.status(HttpStatus.FORBIDDEN.value())
				.path(request.getRequestURI())
				.error(message)
				.build());
	}
}
