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
import org.entrystore.rest.springboot.configuration.OidcCustomConfiguration;
import org.entrystore.rest.springboot.configuration.OidcCustomConfiguration.Provider;
import org.entrystore.rest.springboot.model.auth.AuthState;
import org.entrystore.rest.springboot.service.OidcAuthService;
import org.entrystore.rest.springboot.service.auth.OidcAuthStateCache;
import org.entrystore.rest.springboot.util.ErrorResponseWriter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.web.RedirectStrategy;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * OIDC-specific hook wiring: accepted token type, state-keyed custom URL handling, per-provider
 * auto-provisioning, and the missing-openid-scope guard. The shared SSO invariants are covered by
 * {@link AbstractSsoLoginSuccessHandlerTest}.
 */
@ExtendWith(MockitoExtension.class)
class OidcLoginSuccessHandlerTest {

	private static final String FAILURE_URL = "http://localhost:8181/auth/login";
	private static final String SUCCESS_URL = "http://localhost:8181/welcome";
	private static final String CUSTOM_FAILURE_URL = "http://localhost:8181/custom-failure";
	private static final String CUSTOM_SUCCESS_URL = "http://localhost:8181/custom-success";
	private static final String EVIL_URL = "http://evil.com/phishing";
	private static final String STATE = "state-token";
	private static final String PROVIDER_ID = "keycloak";

	@Mock
	private ESUserDetailsService userService;

	@Mock
	private OidcAuthService oidcAuthService;

	@Mock
	private OidcAuthStateCache oidcAuthStateCache;

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

	@Mock
	private ConditionContext conditionContext;

	private OidcLoginSuccessHandler handler;

	@BeforeEach
	void setUp() {
		var oidcConfiguration = new OidcCustomConfiguration(
				true, null, List.of(), Map.of(),
				new OidcCustomConfiguration.RedirectUrl(SUCCESS_URL),
				new OidcCustomConfiguration.RedirectUrl(FAILURE_URL));
		handler = new OidcLoginSuccessHandler(userService, oidcAuthService, oidcAuthStateCache,
				principalManager, new ErrorResponseWriter(JsonMapper.builder().build()), oidcConfiguration);
		// Custom-success redirects route through the RedirectStrategy; the failure path writes the
		// redirect directly to the response. Mocking the strategy keeps the success-path assertions
		// independent of the default strategy's encodeRedirectURL handling.
		handler.setRedirectStrategy(redirectStrategy);
		// Mirrors SecurityConfig: the fall-through (no valid cached success URL) goes to this default.
		handler.setDefaultTargetUrl(SUCCESS_URL);
	}

	@Test
	void nonOAuth2AuthenticationIsRejectedWithoutLoadingUser() throws Exception {
		var wrongToken = new UsernamePasswordAuthenticationToken("someone", "pw");

		handler.onAuthenticationSuccess(request, response, wrongToken);

		verify(response).sendRedirect(FAILURE_URL);
		verify(userService, never()).loadUser(any());
	}

	@Test
	void nonOidcPrincipalIsRejectedWithoutLoadingUser() throws Exception {
		// A registration without the 'openid' scope yields a plain OAuth2User; the username-claim
		// mapping never ran for it, so the handler must fail closed instead of matching whatever
		// name Spring defaulted to.
		var plainOAuth2User = new DefaultOAuth2User(List.of(new SimpleGrantedAuthority("ROLE_USER")),
				Map.of("sub", "someone"), "sub");
		var plainToken = new OAuth2AuthenticationToken(plainOAuth2User,
				List.of(new SimpleGrantedAuthority("ROLE_USER")), PROVIDER_ID);

		handler.onAuthenticationSuccess(request, response, plainToken);

		verify(response).sendRedirect(FAILURE_URL);
		verify(userService, never()).loadUser(any());
	}

	@Test
	void nonWhitelistedCachedFailureUrlFallsBackToDefault() throws Exception {
		// 'admin' short-circuits to the failure path, carrying the cached (attacker-supplied) failure URL.
		when(request.getParameter("state")).thenReturn(STATE);
		when(oidcAuthStateCache.getAuthState(STATE)).thenReturn(new AuthState(null, EVIL_URL));
		when(oidcAuthService.isValidRedirectUrl(EVIL_URL)).thenReturn(false);

		handler.onAuthenticationSuccess(request, response, oidcAuthentication("admin"));

		verify(response).sendRedirect(FAILURE_URL);
		verify(response, never()).sendRedirect(EVIL_URL);
	}

	@Test
	void whitelistedCachedFailureUrlIsHonored() throws Exception {
		when(request.getParameter("state")).thenReturn(STATE);
		when(oidcAuthStateCache.getAuthState(STATE)).thenReturn(new AuthState(null, CUSTOM_FAILURE_URL));
		when(oidcAuthService.isValidRedirectUrl(CUSTOM_FAILURE_URL)).thenReturn(true);

		handler.onAuthenticationSuccess(request, response, oidcAuthentication("admin"));

		verify(response).sendRedirect(CUSTOM_FAILURE_URL);
	}

	@Test
	void nonWhitelistedCachedSuccessUrlIsNotHonoredAndUserStillLogsInToDefault() throws Exception {
		when(request.getParameter("state")).thenReturn(STATE);
		when(oidcAuthStateCache.getAuthState(STATE)).thenReturn(new AuthState(EVIL_URL, null));
		when(oidcAuthService.isValidRedirectUrl(EVIL_URL)).thenReturn(false);
		givenEnabledUserIsLoaded("jane");

		handler.onAuthenticationSuccess(request, response, oidcAuthentication("jane"));

		// The invalid cached URL is dropped, but the login still completes — to the default target.
		verify(redirectStrategy).sendRedirect(request, response, SUCCESS_URL);
		verify(redirectStrategy, never()).sendRedirect(request, response, EVIL_URL);
	}

	@Test
	void whitelistedCachedSuccessUrlIsHonored() throws Exception {
		when(request.getParameter("state")).thenReturn(STATE);
		when(oidcAuthStateCache.getAuthState(STATE)).thenReturn(new AuthState(CUSTOM_SUCCESS_URL, null));
		when(oidcAuthService.isValidRedirectUrl(CUSTOM_SUCCESS_URL)).thenReturn(true);
		givenEnabledUserIsLoaded("jane");

		handler.onAuthenticationSuccess(request, response, oidcAuthentication("jane"));

		verify(redirectStrategy).sendRedirect(request, response, CUSTOM_SUCCESS_URL);
	}

	@Test
	void userNotFoundAndUnknownProviderRedirectsToFailure() throws Exception {
		when(userService.loadUser("newuser")).thenReturn(null);
		when(oidcAuthService.findProviderForCallback(PROVIDER_ID)).thenReturn(null);

		handler.onAuthenticationSuccess(request, response, oidcAuthentication("newuser"));

		verify(response).sendRedirect(FAILURE_URL);
		verify(userService, never()).createUser(any());
	}

	@Test
	void userNotFoundAndProviderAutoProvisioningDisabledRedirectsToFailure() throws Exception {
		when(userService.loadUser("newuser")).thenReturn(null);
		when(oidcAuthService.findProviderForCallback(PROVIDER_ID)).thenReturn(new Provider(List.of("*"), false, null));

		handler.onAuthenticationSuccess(request, response, oidcAuthentication("newuser"));

		verify(response).sendRedirect(FAILURE_URL);
		verify(userService, never()).createUser(any());
	}

	@Test
	void userNotFoundAndProviderAutoProvisioningEnabledCreatesUserAndProceedsToSuccess() throws Exception {
		when(userService.loadUser("newuser")).thenReturn(null);
		when(oidcAuthService.findProviderForCallback(PROVIDER_ID)).thenReturn(new Provider(List.of("*"), true, null));
		when(userService.createUser("newuser")).thenReturn(esUser);
		givenUserIsEnabled();

		handler.onAuthenticationSuccess(request, response, oidcAuthentication("newuser"));

		verify(userService).createUser("newuser");
		verify(redirectStrategy).sendRedirect(request, response, SUCCESS_URL);
		verify(response, never()).sendRedirect(FAILURE_URL);
	}

	private void givenEnabledUserIsLoaded(String username) {
		when(userService.loadUser(username)).thenReturn(esUser);
		givenUserIsEnabled();
	}

	// OidcEnabledCondition must mirror the relaxed Boolean binding OidcCustomConfiguration.enabled()
	// uses — a literal-string match would make values like 'on' boot the SecurityConfig OIDC branch
	// without the handler bean and fail startup.
	@ParameterizedTest(name = "entrystore.auth.oidc.enabled={0} -> bean active: {1}")
	@CsvSource({
			"true, true",
			"on, true",
			"yes, true",
			"1, true",
			"false, false",
			"off, false",
			"no, false",
			"0, false"
	})
	void oidcEnabledConditionUsesRelaxedBooleanBinding(String value, boolean expected) {
		var environment = new MockEnvironment().withProperty("entrystore.auth.oidc.enabled", value);
		when(conditionContext.getEnvironment()).thenReturn(environment);

		assertEquals(expected,
				new OidcLoginSuccessHandler.OidcEnabledCondition().matches(conditionContext, null));
	}

	@Test
	void oidcEnabledConditionDefaultsToDisabledWhenPropertyAbsent() {
		when(conditionContext.getEnvironment()).thenReturn(new MockEnvironment());

		assertFalse(new OidcLoginSuccessHandler.OidcEnabledCondition().matches(conditionContext, null));
	}

	// Stubs the principalManager round-trip BasicVerifier.isUserDisabled performs so the handler
	// reaches the authenticated-success branch for a non-disabled user.
	private void givenUserIsEnabled() {
		when(esUser.isDisabled()).thenReturn(false);
		when(principalManager.getAuthenticatedUserURI()).thenReturn(URI.create("urn:test:current"));
		when(principalManager.getAdminUser()).thenReturn(adminUser);
		when(adminUser.getURI()).thenReturn(URI.create("urn:test:admin"));
	}

	private static OAuth2AuthenticationToken oidcAuthentication(String username) {
		// Mirrors what UsernameClaimOidcUserService produces at login: the principal is named
		// after the username claim (email by default).
		var idToken = new OidcIdToken("id-token", Instant.now(), Instant.now().plusSeconds(60),
				Map.of("sub", "subject-" + username, "email", username));
		var principal = new DefaultOidcUser(List.of(new SimpleGrantedAuthority("ROLE_USER")), idToken, "email");
		return new OAuth2AuthenticationToken(principal, List.of(new SimpleGrantedAuthority("ROLE_USER")), PROVIDER_ID);
	}
}
