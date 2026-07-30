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
import jakarta.servlet.http.HttpSession;
import org.entrystore.PrincipalManager;
import org.entrystore.User;
import org.entrystore.rest.springboot.util.ErrorResponseWriter;
import tools.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.RedirectStrategy;
import org.springframework.security.web.savedrequest.HttpSessionRequestCache;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Shared SSO invariants, exercised through a minimal test-only subclass. Subclass-specific
 * hook wiring is covered by {@link CasLoginSuccessHandlerTest} and {@link SamlLoginSuccessHandlerTest}.
 */
@ExtendWith(MockitoExtension.class)
class AbstractSsoLoginSuccessHandlerTest {

	private static final String FAILURE_URL = "http://localhost:8181/auth/login";
	private static final String SUCCESS_URL = "http://localhost:8181/welcome";
	private static final String EVIL_URL = "http://evil.com/phishing";

	@Mock
	private ESUserDetailsService userService;

	@Mock
	private PrincipalManager principalManager;

	@Mock
	private HttpServletRequest request;

	@Mock
	private HttpServletResponse response;

	@Mock
	private HttpSession session;

	@Mock
	private RedirectStrategy redirectStrategy;

	@Mock
	private User esUser;

	@Mock
	private User adminUser;

	private TestSsoLoginSuccessHandler handler;

	@BeforeEach
	void setUp() {
		handler = newHandler(FAILURE_URL, true);
	}

	@AfterEach
	void clearSecurityContext() {
		SecurityContextHolder.clearContext();
	}

	@Test
	void wrongTokenTypeIsRejectedWithoutLoadingUser() throws Exception {
		// Defense-in-depth guard: if the filter wiring ever delivers a token of a different type to
		// the handler, we must not process it — the SecurityContext has already been persisted to
		// the session by the filter chain, so the handler's reject path is what unwinds it.
		var wrongToken = new TestingAuthenticationToken("someone", "pw");

		handler.onAuthenticationSuccess(request, response, wrongToken);

		verify(response).sendRedirect(FAILURE_URL);
		verify(userService, never()).loadUser(any());
	}

	// "ADMİN" (dotted İ, U+0130) and "admın" (dotless ı, U+0131) pin the equalsIgnoreCase
	// semantics of the reserved-name check: a toLowerCase()-based lookup would let both through, and
	// the downstream default-locale principal lookup can resolve "ADMİN" to the real admin.
	@ParameterizedTest(name = "reserved username ''{0}'' is blocked")
	@ValueSource(strings = {"admin", "ADMIN", "guest", "GUEST", "ADMİN", "admın"})
	void reservedUsernameIsBlocked(String username) throws Exception {
		handler.onAuthenticationSuccess(request, response, token(username));

		verify(response).sendRedirect(FAILURE_URL);
		verify(userService, never()).loadUser(any());
	}

	@Test
	void rejectedLoginInvalidatesSessionAndClearsSecurityContext() throws Exception {
		// The SSO filter has already persisted the token to the session-backed SecurityContext;
		// the reject path must unwind both, or the rejected principal stays authenticated.
		when(request.getSession(false)).thenReturn(session);
		SecurityContextHolder.getContext().setAuthentication(token("admin"));

		handler.onAuthenticationSuccess(request, response, token("admin"));

		verify(session).invalidate();
		assertNull(SecurityContextHolder.getContext().getAuthentication());
		verify(response).sendRedirect(FAILURE_URL);
	}

	@Test
	void userNotFoundAndAutoProvisioningDisabledRedirectsToFailure() throws Exception {
		handler = newHandler(FAILURE_URL, false);
		when(userService.loadUser("newuser")).thenReturn(null);

		handler.onAuthenticationSuccess(request, response, token("newuser"));

		verify(response).sendRedirect(FAILURE_URL);
		verify(userService, never()).createUser(any());
	}

	@Test
	void userNotFoundAndAutoProvisioningEnabledCreatesUserAndProceedsToSuccess() throws Exception {
		when(userService.loadUser("newuser")).thenReturn(null);
		when(userService.createUser("newuser")).thenReturn(esUser);
		givenUserIsEnabled();

		handler.onAuthenticationSuccess(request, response, token("newuser"));

		verify(userService).createUser("newuser");
		verify(redirectStrategy).sendRedirect(request, response, SUCCESS_URL);
		verify(response, never()).sendRedirect(FAILURE_URL);
	}

	@Test
	void autoProvisioningFailureRedirectsToFailure() throws Exception {
		when(userService.loadUser("collidinguser")).thenReturn(null);
		when(userService.createUser("collidinguser"))
				.thenThrow(new IllegalStateException("Principal name 'collidinguser' already in use"));

		handler.onAuthenticationSuccess(request, response, token("collidinguser"));

		verify(response).sendRedirect(FAILURE_URL);
	}

	@Test
	void autoProvisioningRuntimeExceptionRedirectsToFailure() throws Exception {
		when(userService.loadUser("newuser")).thenReturn(null);
		when(userService.createUser("newuser"))
				.thenThrow(new RuntimeException("RDF store unavailable"));

		handler.onAuthenticationSuccess(request, response, token("newuser"));

		verify(response).sendRedirect(FAILURE_URL);
	}

	@Test
	void disabledExistingUserRedirectsToFailure() throws Exception {
		when(userService.loadUser("jane")).thenReturn(esUser);
		when(esUser.isDisabled()).thenReturn(true);
		when(principalManager.getAuthenticatedUserURI()).thenReturn(URI.create("urn:test:current"));
		when(principalManager.getAdminUser()).thenReturn(adminUser);
		when(adminUser.getURI()).thenReturn(URI.create("urn:test:admin"));

		handler.onAuthenticationSuccess(request, response, token("jane"));

		verify(response).sendRedirect(FAILURE_URL);
	}

	@Test
	void existingActiveUserProceedsToSuccess() throws Exception {
		when(userService.loadUser("jane")).thenReturn(esUser);
		givenUserIsEnabled();

		handler.onAuthenticationSuccess(request, response, token("jane"));

		verify(redirectStrategy).sendRedirect(request, response, SUCCESS_URL);
		verify(response, never()).sendRedirect(FAILURE_URL);
	}

	@Test
	void savedRequestPointingAtSsoEndpointIsDiscardedOnSuccess() throws Exception {
		// Real servlet mocks here: the saved-request cache lives in the HTTP session (Mockito request
		// mocks carry none), and the assertion is on the actual redirect target the default strategy
		// writes. Without removeRequest in the success flow, SavedRequestAwareAuthenticationSuccessHandler
		// would redirect to the saved SSO callback URL instead of the trusted default target.
		var httpSession = new MockHttpSession();
		var callbackRequest = new MockHttpServletRequest("GET", "/auth/cas");
		callbackRequest.setSession(httpSession);
		new HttpSessionRequestCache().saveRequest(callbackRequest, new MockHttpServletResponse());

		var realRedirectHandler = new TestSsoLoginSuccessHandler(userService, principalManager, FAILURE_URL, true);
		realRedirectHandler.setDefaultTargetUrl(SUCCESS_URL);
		when(userService.loadUser("jane")).thenReturn(esUser);
		givenUserIsEnabled();

		var loginRequest = new MockHttpServletRequest("GET", "/auth/cas");
		loginRequest.setSession(httpSession);
		var loginResponse = new MockHttpServletResponse();

		realRedirectHandler.onAuthenticationSuccess(loginRequest, loginResponse, token("jane"));

		assertEquals(SUCCESS_URL, loginResponse.getRedirectedUrl());
	}

	@Test
	void failureWithNullRedirectUrlWritesJsonError() throws Exception {
		handler = newHandler(null, true);
		when(response.getWriter()).thenReturn(new java.io.PrintWriter(java.io.Writer.nullWriter()));

		handler.onAuthenticationSuccess(request, response, token("admin"));

		verify(response, never()).sendRedirect(any());
		verify(response).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
	}

	@Test
	void liveSuccessUrlRequestParameterIsNeverHonored() throws Exception {
		// ENTRYSTORE-996 regression: even if a targetUrlParameter is (re)configured, a request-supplied
		// ?successurl= must never drive the redirect — determineTargetUrl always returns the trusted
		// default target; custom success URLs may only come from a subclass's validated hook.
		handler.setTargetUrlParameter("successurl");
		lenient().when(request.getParameter("successurl")).thenReturn(EVIL_URL);
		when(userService.loadUser("jane")).thenReturn(esUser);
		givenUserIsEnabled();

		handler.onAuthenticationSuccess(request, response, token("jane"));

		verify(redirectStrategy).sendRedirect(request, response, SUCCESS_URL);
		verify(redirectStrategy, never()).sendRedirect(request, response, EVIL_URL);
	}

	private TestSsoLoginSuccessHandler newHandler(String failureUrl, boolean autoProvisioning) {
		var newHandler = new TestSsoLoginSuccessHandler(userService, principalManager, failureUrl, autoProvisioning);
		// The success path routes through the RedirectStrategy; the failure path writes the redirect
		// directly to the response. Mocking the strategy keeps the success-path assertions independent
		// of the default strategy's encodeRedirectURL handling.
		newHandler.setRedirectStrategy(redirectStrategy);
		newHandler.setDefaultTargetUrl(SUCCESS_URL);
		return newHandler;
	}

	// Stubs the principalManager round-trip BasicVerifier.isUserDisabled performs so the handler
	// reaches the authenticated-success branch for a non-disabled user.
	private void givenUserIsEnabled() {
		when(esUser.isDisabled()).thenReturn(false);
		when(principalManager.getAuthenticatedUserURI()).thenReturn(URI.create("urn:test:current"));
		when(principalManager.getAdminUser()).thenReturn(adminUser);
		when(adminUser.getURI()).thenReturn(URI.create("urn:test:admin"));
	}

	private static UsernamePasswordAuthenticationToken token(String username) {
		return new UsernamePasswordAuthenticationToken(username, "pw");
	}

	/** Minimal concrete subclass: fixed label and failure URL, no per-request context. */
	private static final class TestSsoLoginSuccessHandler
			extends AbstractSsoLoginSuccessHandler<UsernamePasswordAuthenticationToken, Void> {

		private final String failureUrl;
		private final boolean autoProvisioning;

		private TestSsoLoginSuccessHandler(ESUserDetailsService userService, PrincipalManager principalManager,
										   String failureUrl, boolean autoProvisioning) {
			super(userService, principalManager, new ErrorResponseWriter(JsonMapper.builder().build()));
			this.failureUrl = failureUrl;
			this.autoProvisioning = autoProvisioning;
		}

		@Override
		protected String authTypeLabel() {
			return "TEST";
		}

		@Override
		protected Class<UsernamePasswordAuthenticationToken> tokenType() {
			return UsernamePasswordAuthenticationToken.class;
		}

		@Override
		protected Void resolveContext(HttpServletRequest request, UsernamePasswordAuthenticationToken token) {
			return null;
		}

		@Override
		protected boolean isAutoProvisioningEnabled(Void context) {
			return autoProvisioning;
		}

		@Override
		protected String defaultFailureUrl() {
			return failureUrl;
		}
	}
}
