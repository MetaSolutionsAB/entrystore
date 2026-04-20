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
import org.springframework.security.core.Authentication;

import java.net.URI;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
	private Authentication authentication;

	@Mock
	private User esUser;

	@Mock
	private User adminUser;

	private CasLoginSuccessHandler handler;

	@BeforeEach
	void setUp() {
		var server = new CasCustomConfiguration.Server("https://cas.example.org/cas", null);
		var casConfiguration = new CasCustomConfiguration(true, CasVersion.CAS2, server, true,
				new CasCustomConfiguration.RedirectSuccess(SUCCESS_URL),
				new CasCustomConfiguration.RedirectFailure(FAILURE_URL));
		handler = new CasLoginSuccessHandler(userService, principalManager, casConfiguration);
	}

	@Test
	void adminUsernameIsBlocked() throws Exception {
		when(authentication.getName()).thenReturn("admin");

		handler.onAuthenticationSuccess(request, response, authentication);

		verify(response).sendRedirect(FAILURE_URL);
		verify(userService, never()).loadUser(any());
	}

	@Test
	void adminUsernameBlockIsCaseInsensitive() throws Exception {
		when(authentication.getName()).thenReturn("ADMIN");

		handler.onAuthenticationSuccess(request, response, authentication);

		verify(response).sendRedirect(FAILURE_URL);
		verify(userService, never()).loadUser(any());
	}

	@Test
	void guestUsernameIsBlocked() throws Exception {
		when(authentication.getName()).thenReturn("guest");

		handler.onAuthenticationSuccess(request, response, authentication);

		verify(response).sendRedirect(FAILURE_URL);
		verify(userService, never()).loadUser(any());
	}

	@Test
	void userNotFoundAndAutoProvisioningDisabledRedirectsToFailure() throws Exception {
		var configNoProvisioning = new CasCustomConfiguration(
				true, CasVersion.CAS2,
				new CasCustomConfiguration.Server("https://cas.example.org/cas", null),
				false, // auto-provisioning disabled
				new CasCustomConfiguration.RedirectSuccess(SUCCESS_URL),
				new CasCustomConfiguration.RedirectFailure(FAILURE_URL));
		handler = new CasLoginSuccessHandler(userService, principalManager, configNoProvisioning);

		when(authentication.getName()).thenReturn("newuser");
		when(userService.loadUser("newuser")).thenReturn(null);

		handler.onAuthenticationSuccess(request, response, authentication);

		verify(response).sendRedirect(FAILURE_URL);
	}

	@Test
	void disabledExistingUserRedirectsToFailure() throws Exception {
		when(authentication.getName()).thenReturn("jane");
		when(userService.loadUser("jane")).thenReturn(esUser);
		when(esUser.isDisabled()).thenReturn(true);
		when(principalManager.getAuthenticatedUserURI()).thenReturn(URI.create("urn:test:current"));
		when(principalManager.getAdminUser()).thenReturn(adminUser);
		when(adminUser.getURI()).thenReturn(URI.create("urn:test:admin"));

		handler.onAuthenticationSuccess(request, response, authentication);

		verify(response).sendRedirect(FAILURE_URL);
	}

	@Test
	void autoProvisioningFailureRedirectsToFailure() throws Exception {
		when(authentication.getName()).thenReturn("collidinguser");
		when(userService.loadUser("collidinguser")).thenReturn(null);
		when(userService.createUser("collidinguser"))
				.thenThrow(new IllegalStateException("Principal name 'collidinguser' already in use"));

		handler.onAuthenticationSuccess(request, response, authentication);

		verify(response).sendRedirect(FAILURE_URL);
	}

	@Test
	void autoProvisioningRuntimeExceptionRedirectsToFailure() throws Exception {
		when(authentication.getName()).thenReturn("newuser");
		when(userService.loadUser("newuser")).thenReturn(null);
		when(userService.createUser("newuser"))
				.thenThrow(new RuntimeException("RDF store unavailable"));

		handler.onAuthenticationSuccess(request, response, authentication);

		verify(response).sendRedirect(FAILURE_URL);
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

		verify(response, never()).sendRedirect(FAILURE_URL);
	}

	@Test
	void failureWithNullRedirectUrlWritesJsonError() throws Exception {
		var configNoRedirect = new CasCustomConfiguration(
				true, CasVersion.CAS2,
				new CasCustomConfiguration.Server("https://cas.example.org/cas", null),
				true,
				new CasCustomConfiguration.RedirectSuccess(SUCCESS_URL),
				new CasCustomConfiguration.RedirectFailure(null));
		handler = new CasLoginSuccessHandler(userService, principalManager, configNoRedirect);

		when(authentication.getName()).thenReturn("admin");
		when(response.getWriter()).thenReturn(new java.io.PrintWriter(java.io.Writer.nullWriter()));

		handler.onAuthenticationSuccess(request, response, authentication);

		verify(response, never()).sendRedirect(any());
		verify(response).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
	}
}
