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

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.entrystore.PrincipalManager;
import org.entrystore.User;
import org.entrystore.rest.springboot.configuration.CasCustomConfiguration;
import org.entrystore.rest.springboot.configuration.CasVersion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.cas.authentication.CasAuthenticationToken;
import org.springframework.security.web.RedirectStrategy;

import java.net.URI;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CAS-specific hook wiring: accepted token type and the config-driven auto-provisioning flag
 * and failure URL. The shared SSO invariants are covered by {@link AbstractSsoLoginSuccessHandlerTest}.
 */
@ExtendWith(MockitoExtension.class)
class CasLoginSuccessHandlerTest {

	private static final String FAILURE_URL = "http://localhost:8181/auth/login";
	private static final String SUCCESS_URL = "http://localhost:8181/welcome";

	@Mock
	private ESUserDetailsService userService;

	@Mock
	private PrincipalManager principalManager;

	@Mock
	private HttpServletRequest request;

	@Mock
	private HttpServletResponse response;

	@Mock
	private CasAuthenticationToken authentication;

	@Mock
	private RedirectStrategy redirectStrategy;

	@Mock
	private User esUser;

	@Mock
	private User adminUser;

	private CasLoginSuccessHandler handler;

	@BeforeEach
	void setUp() {
		handler = newHandler(true);
	}

	@Test
	void nonCasAuthenticationTokenIsRejectedWithoutLoadingUser() throws Exception {
		var wrongToken = new UsernamePasswordAuthenticationToken("someone", "pw");

		handler.onAuthenticationSuccess(request, response, wrongToken);

		verify(response).sendRedirect(FAILURE_URL);
		verify(userService, never()).loadUser(any());
	}

	@Test
	void userNotFoundAndAutoProvisioningDisabledRedirectsToFailure() throws Exception {
		handler = newHandler(false);
		when(authentication.getName()).thenReturn("newuser");
		when(userService.loadUser("newuser")).thenReturn(null);

		handler.onAuthenticationSuccess(request, response, authentication);

		verify(response).sendRedirect(FAILURE_URL);
		verify(userService, never()).createUser(any());
	}

	@Test
	void existingActiveUserProceedsToSuccess() throws Exception {
		when(authentication.getName()).thenReturn("jane");
		when(userService.loadUser("jane")).thenReturn(esUser);
		when(esUser.isDisabled()).thenReturn(false);
		when(principalManager.getAuthenticatedUserURI()).thenReturn(URI.create("urn:test:current"));
		when(principalManager.getAdminUser()).thenReturn(adminUser);
		when(adminUser.getURI()).thenReturn(URI.create("urn:test:admin"));

		handler.onAuthenticationSuccess(request, response, authentication);

		verify(redirectStrategy).sendRedirect(request, response, SUCCESS_URL);
		verify(response, never()).sendRedirect(FAILURE_URL);
	}

	@Test
	void userNotFoundAndAutoProvisioningEnabledCreatesUserAndProceedsToSuccess() throws Exception {
		when(authentication.getName()).thenReturn("newuser");
		when(userService.loadUser("newuser")).thenReturn(null);
		when(userService.createUser("newuser")).thenReturn(esUser);
		when(esUser.isDisabled()).thenReturn(false);
		when(principalManager.getAuthenticatedUserURI()).thenReturn(URI.create("urn:test:current"));
		when(principalManager.getAdminUser()).thenReturn(adminUser);
		when(adminUser.getURI()).thenReturn(URI.create("urn:test:admin"));

		handler.onAuthenticationSuccess(request, response, authentication);

		verify(userService).createUser("newuser");
		verify(redirectStrategy).sendRedirect(request, response, SUCCESS_URL);
		verify(response, never()).sendRedirect(FAILURE_URL);
	}

	private CasLoginSuccessHandler newHandler(boolean userAutoProvisioning) {
		var server = new CasCustomConfiguration.Server("https://cas.example.org/cas", null);
		var casConfiguration = new CasCustomConfiguration(true, CasVersion.CAS2, server, userAutoProvisioning,
				new CasCustomConfiguration.RedirectSuccess(SUCCESS_URL),
				new CasCustomConfiguration.RedirectFailure(FAILURE_URL));
		var newHandler = new CasLoginSuccessHandler(userService, principalManager, casConfiguration);
		// CAS has no custom-success hook, so success flows through super.onAuthenticationSuccess ->
		// determineTargetUrl -> RedirectStrategy. Mocking the strategy and default target makes the
		// success redirect observable; the failure path writes directly to the response.
		newHandler.setRedirectStrategy(redirectStrategy);
		newHandler.setDefaultTargetUrl(SUCCESS_URL);
		return newHandler;
	}
}
