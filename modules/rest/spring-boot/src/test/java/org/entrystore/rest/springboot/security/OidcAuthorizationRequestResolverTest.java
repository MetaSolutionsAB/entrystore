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
import org.entrystore.rest.springboot.model.auth.AuthState;
import org.entrystore.rest.springboot.service.OidcAuthService;
import org.entrystore.rest.springboot.service.auth.OidcAuthStateCache;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OidcAuthorizationRequestResolverTest {

	private static final String VALID_SUCCESS = "http://localhost:8181/welcome";
	private static final String VALID_FAILURE = "http://localhost:8181/auth/login";
	private static final String EVIL = "http://evil.com/phishing";
	private static final String STATE = "state-token";

	@Mock
	private OAuth2AuthorizationRequestResolver delegate;

	@Mock
	private OidcAuthService oidcAuthService;

	@Mock
	private OidcAuthStateCache oidcAuthStateCache;

	@Mock
	private HttpServletRequest request;

	private OidcAuthorizationRequestResolver resolver;

	@BeforeEach
	void setUp() {
		resolver = new OidcAuthorizationRequestResolver(delegate, oidcAuthService, oidcAuthStateCache);
	}

	private OAuth2AuthorizationRequest authorizationRequest() {
		return OAuth2AuthorizationRequest.authorizationCode()
				.authorizationUri("https://idp.example.com/authorize")
				.clientId("entrystore")
				.state(STATE)
				.build();
	}

	@Test
	void validSuccessUrlIsCachedKeyedByState() {
		var authorizationRequest = authorizationRequest();
		when(delegate.resolve(request)).thenReturn(authorizationRequest);
		when(request.getParameter("successurl")).thenReturn(VALID_SUCCESS);
		when(request.getParameter("failureurl")).thenReturn(null);
		when(oidcAuthService.isValidRedirectUrl(VALID_SUCCESS)).thenReturn(true);

		var resolved = resolver.resolve(request);

		assertSame(authorizationRequest, resolved);
		AuthState stored = captureStoredAuthState();
		assertEquals(VALID_SUCCESS, stored.successUrl());
		assertNull(stored.failureUrl());
	}

	@Test
	void invalidSuccessUrlIsDroppedAndNothingCached() {
		when(delegate.resolve(request)).thenReturn(authorizationRequest());
		when(request.getParameter("successurl")).thenReturn(EVIL);
		when(request.getParameter("failureurl")).thenReturn(null);
		when(oidcAuthService.isValidRedirectUrl(EVIL)).thenReturn(false);

		resolver.resolve(request);

		verify(oidcAuthStateCache, never()).storeAuthState(any(), any());
	}

	@Test
	void validFailureUrlIsCached() {
		when(delegate.resolve(request)).thenReturn(authorizationRequest());
		when(request.getParameter("successurl")).thenReturn(null);
		when(request.getParameter("failureurl")).thenReturn(VALID_FAILURE);
		when(oidcAuthService.isValidRedirectUrl(VALID_FAILURE)).thenReturn(true);

		resolver.resolve(request);

		AuthState stored = captureStoredAuthState();
		assertNull(stored.successUrl());
		assertEquals(VALID_FAILURE, stored.failureUrl());
	}

	@Test
	void onlyTheValidUrlIsKeptWhenOneIsInvalid() {
		when(delegate.resolve(request)).thenReturn(authorizationRequest());
		when(request.getParameter("successurl")).thenReturn(VALID_SUCCESS);
		when(request.getParameter("failureurl")).thenReturn(EVIL);
		when(oidcAuthService.isValidRedirectUrl(VALID_SUCCESS)).thenReturn(true);
		when(oidcAuthService.isValidRedirectUrl(EVIL)).thenReturn(false);

		resolver.resolve(request);

		AuthState stored = captureStoredAuthState();
		assertEquals(VALID_SUCCESS, stored.successUrl());
		assertNull(stored.failureUrl());
	}

	@Test
	void noRedirectParametersMeansNothingCached() {
		when(delegate.resolve(request)).thenReturn(authorizationRequest());

		resolver.resolve(request);

		verify(oidcAuthStateCache, never()).storeAuthState(any(), any());
	}

	// The filter probes resolve() for every request below /oauth2/authorization; a null delegate
	// result (no registration id in the path) must pass through without touching the cache.
	@Test
	void nullDelegateResultPassesThroughWithoutCaching() {
		when(delegate.resolve(request)).thenReturn(null);

		assertNull(resolver.resolve(request));

		verify(oidcAuthStateCache, never()).storeAuthState(any(), any());
	}

	@Test
	void twoArgResolveAppliesTheSameValidationAndCaching() {
		var authorizationRequest = authorizationRequest();
		when(delegate.resolve(request, "keycloak")).thenReturn(authorizationRequest);
		when(request.getParameter("successurl")).thenReturn(VALID_SUCCESS);
		when(request.getParameter("failureurl")).thenReturn(null);
		when(oidcAuthService.isValidRedirectUrl(VALID_SUCCESS)).thenReturn(true);

		var resolved = resolver.resolve(request, "keycloak");

		assertSame(authorizationRequest, resolved);
		AuthState stored = captureStoredAuthState();
		assertEquals(VALID_SUCCESS, stored.successUrl());
	}

	private AuthState captureStoredAuthState() {
		ArgumentCaptor<AuthState> captor = ArgumentCaptor.forClass(AuthState.class);
		verify(oidcAuthStateCache).storeAuthState(eq(STATE), captor.capture());
		return captor.getValue();
	}
}
