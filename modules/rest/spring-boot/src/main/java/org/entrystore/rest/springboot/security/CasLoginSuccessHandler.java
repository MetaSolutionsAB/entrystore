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

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.entrystore.PrincipalManager;
import org.entrystore.User;
import org.entrystore.rest.springboot.configuration.CasCustomConfiguration;
import org.entrystore.rest.springboot.service.auth.BasicVerifier;
import org.entrystore.rest.springboot.util.HttpUtil;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.SavedRequestAwareAuthenticationSuccessHandler;
import org.springframework.security.web.savedrequest.HttpSessionRequestCache;

import java.io.IOException;

@Slf4j
@RequiredArgsConstructor
public class CasLoginSuccessHandler extends SavedRequestAwareAuthenticationSuccessHandler {

	private final ESUserDetailsService userService;
	private final PrincipalManager principalManager;
	private final CasCustomConfiguration casConfiguration;

	@Override
	public void onAuthenticationSuccess(HttpServletRequest request,
										HttpServletResponse response,
										Authentication authentication) throws IOException, ServletException {
		try {
			handleCasAuthentication(request, response, authentication);
		} catch (IOException | ServletException e) {
			throw e;
		} catch (Exception e) {
			log.error("Unexpected error during CAS login for user '{}'", authentication.getName(), e);
			redirectToLoginFailureUrl(request, response);
		}
	}

	private void handleCasAuthentication(HttpServletRequest request,
										 HttpServletResponse response,
										 Authentication authentication) throws IOException, ServletException {

		String username = authentication.getName();
		log.info("Successfully authenticated via CAS, username: '{}'", username);

		if ("admin".equalsIgnoreCase(username) || "guest".equalsIgnoreCase(username)) {
			log.warn("Ignoring reserved username '{}' from CAS server", username);
			redirectToLoginFailureUrl(request, response);
			return;
		}

		User esUser = userService.loadUser(username);
		if (esUser == null) {
			if (!casConfiguration.userAutoProvisioning()) {
				log.warn("Login denied for CAS user '{}': user not found in EntryStore and auto-provisioning is disabled", username);
				redirectToLoginFailureUrl(request, response);
				return;
			}
			log.info("User '{}' not found in EntryStore. Creating new user (auto-provisioning enabled)", username);
			esUser = userService.createUser(username);
		} else {
			log.info("Existing EntryStore user '{}' logged in via CAS", username);
		}

		if (BasicVerifier.isUserDisabled(principalManager, esUser)) {
			log.warn("Login denied for CAS user '{}': account is disabled", username);
			redirectToLoginFailureUrl(request, response);
			return;
		}

		new HttpSessionRequestCache().removeRequest(request, response);
		super.onAuthenticationSuccess(request, response, authentication);
	}

	private void redirectToLoginFailureUrl(HttpServletRequest request, HttpServletResponse response) throws IOException {
		HttpUtil.redirectOrWriteUnauthorized(response, request.getRequestURI(),
				casConfiguration.redirectFailure().url());
	}
}
