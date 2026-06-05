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
import org.entrystore.rest.springboot.configuration.SamlCustomConfiguration;
import org.entrystore.rest.springboot.model.auth.AuthState;
import org.entrystore.rest.springboot.service.SamlAuthService;
import org.entrystore.rest.springboot.service.auth.SamlAuthStateCache;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.saml2.provider.service.authentication.DefaultSaml2AuthenticatedPrincipal;
import org.springframework.security.saml2.provider.service.authentication.Saml2Authentication;
import org.springframework.security.web.RedirectStrategy;

import java.net.URI;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SamlLoginSuccessHandlerTest {

	private static final String FAILURE_URL = "http://localhost:8181/auth/login";
	private static final String SUCCESS_URL = "http://localhost:8181/welcome";
	private static final String CUSTOM_FAILURE_URL = "http://localhost:8181/custom-failure";
	private static final String CUSTOM_SUCCESS_URL = "http://localhost:8181/custom-success";
	private static final String EVIL_URL = "http://evil.com/phishing";
	private static final String RELAY_STATE = "relay";

	@Mock
	private ESUserDetailsService userService;

	@Mock
	private SamlAuthService samlAuthService;

	@Mock
	private SamlAuthStateCache samlAuthStateCache;

	@Mock
	private PrincipalManager principalManager;

	@Mock
	private HttpServletRequest request;

	@Mock
	private HttpServletResponse response;

	@Mock
	private RedirectStrategy redirectStrategy;

	@Mock
	private User esUser;

	@Mock
	private User adminUser;

	private SamlLoginSuccessHandler handler;

	@BeforeEach
	void setUp() {
		var samlConfiguration = new SamlCustomConfiguration(
				true, null, List.of(), Map.of(),
				new SamlCustomConfiguration.RedirectUrl(SUCCESS_URL),
				new SamlCustomConfiguration.RedirectUrl(FAILURE_URL));
		handler = new SamlLoginSuccessHandler(userService, samlAuthService, samlAuthStateCache,
				principalManager, samlConfiguration);
		// Custom-success redirects route through the RedirectStrategy; the failure path writes the
		// redirect directly to the response. Mocking the strategy keeps the success-path assertions
		// independent of the default strategy's encodeRedirectURL handling.
		handler.setRedirectStrategy(redirectStrategy);
		// Mirrors SecurityConfig: the fall-through (no valid cached success URL) goes to this default.
		handler.setDefaultTargetUrl(SUCCESS_URL);
	}

	@Test
	void nonSaml2AuthenticationIsRejectedWithoutLoadingUser() throws Exception {
		// Defense-in-depth guard: if the filter wiring ever delivers a non-SAML token to this
		// handler, we must not process it — the SecurityContext has already been persisted to
		// the session by the filter chain, so the handler's reject path is what unwinds it.
		var wrongToken = new UsernamePasswordAuthenticationToken("someone", "pw");

		handler.onAuthenticationSuccess(request, response, wrongToken);

		verify(response).sendRedirect(FAILURE_URL);
		verify(userService, never()).loadUser(any());
	}

	@Test
	void nonWhitelistedCachedFailureUrlFallsBackToDefault() throws Exception {
		// 'admin' short-circuits to the failure path, carrying the cached (attacker-supplied) failure URL.
		when(request.getParameter("RelayState")).thenReturn(RELAY_STATE);
		when(samlAuthStateCache.getAuthState(RELAY_STATE)).thenReturn(new AuthState(null, EVIL_URL));
		when(samlAuthService.isValidRedirectUrl(EVIL_URL)).thenReturn(false);

		handler.onAuthenticationSuccess(request, response, saml2Authentication("admin"));

		verify(response).sendRedirect(FAILURE_URL);
		verify(response, never()).sendRedirect(EVIL_URL);
	}

	@Test
	void whitelistedCachedFailureUrlIsHonored() throws Exception {
		when(request.getParameter("RelayState")).thenReturn(RELAY_STATE);
		when(samlAuthStateCache.getAuthState(RELAY_STATE)).thenReturn(new AuthState(null, CUSTOM_FAILURE_URL));
		when(samlAuthService.isValidRedirectUrl(CUSTOM_FAILURE_URL)).thenReturn(true);

		handler.onAuthenticationSuccess(request, response, saml2Authentication("admin"));

		verify(response).sendRedirect(CUSTOM_FAILURE_URL);
	}

	@Test
	void nonWhitelistedCachedSuccessUrlIsNotHonoredAndUserStillLogsInToDefault() throws Exception {
		when(request.getParameter("RelayState")).thenReturn(RELAY_STATE);
		when(samlAuthStateCache.getAuthState(RELAY_STATE)).thenReturn(new AuthState(EVIL_URL, null));
		when(samlAuthService.isValidRedirectUrl(EVIL_URL)).thenReturn(false);
		givenEnabledUserIsLoaded("jane");

		handler.onAuthenticationSuccess(request, response, saml2Authentication("jane"));

		// The invalid cached URL is dropped, but the login still completes — to the default target.
		verify(redirectStrategy).sendRedirect(request, response, SUCCESS_URL);
		verify(redirectStrategy, never()).sendRedirect(request, response, EVIL_URL);
	}

	@Test
	void liveSuccessUrlRequestParameterIsNeverHonored() throws Exception {
		// ENTRYSTORE-996 regression: even if a targetUrlParameter is (re)configured, a request-supplied
		// ?successurl= must never drive the redirect — only the validated cache or the default target.
		handler.setTargetUrlParameter("successurl");
		when(request.getParameter("RelayState")).thenReturn(null);
		lenient().when(request.getParameter("successurl")).thenReturn(EVIL_URL);
		givenEnabledUserIsLoaded("jane");

		handler.onAuthenticationSuccess(request, response, saml2Authentication("jane"));

		verify(redirectStrategy).sendRedirect(request, response, SUCCESS_URL);
		verify(redirectStrategy, never()).sendRedirect(request, response, EVIL_URL);
	}

	@Test
	void whitelistedCachedSuccessUrlIsHonored() throws Exception {
		when(request.getParameter("RelayState")).thenReturn(RELAY_STATE);
		when(samlAuthStateCache.getAuthState(RELAY_STATE)).thenReturn(new AuthState(CUSTOM_SUCCESS_URL, null));
		when(samlAuthService.isValidRedirectUrl(CUSTOM_SUCCESS_URL)).thenReturn(true);
		givenEnabledUserIsLoaded("jane");

		handler.onAuthenticationSuccess(request, response, saml2Authentication("jane"));

		verify(redirectStrategy).sendRedirect(request, response, CUSTOM_SUCCESS_URL);
	}

	// Mirrors CasLoginSuccessHandlerTest: stub the principalManager round-trip BasicVerifier.isUserDisabled
	// performs so the handler reaches the authenticated-success branch for a non-disabled user.
	private void givenEnabledUserIsLoaded(String username) {
		when(userService.loadUser(username)).thenReturn(esUser);
		when(esUser.isDisabled()).thenReturn(false);
		when(principalManager.getAuthenticatedUserURI()).thenReturn(URI.create("urn:test:current"));
		when(principalManager.getAdminUser()).thenReturn(adminUser);
		when(adminUser.getURI()).thenReturn(URI.create("urn:test:admin"));
	}

	private static Saml2Authentication saml2Authentication(String username) {
		var principal = new DefaultSaml2AuthenticatedPrincipal(username, Map.of());
		return new Saml2Authentication(principal, "saml-response", List.of());
	}
}
