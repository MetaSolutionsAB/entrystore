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
import org.entrystore.rest.springboot.service.SamlAuthService;
import org.entrystore.rest.springboot.service.auth.SamlAuthStateCache;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SamlRelayStateResolverTest {

	private static final String VALID_SUCCESS = "http://localhost:8181/welcome";
	private static final String VALID_FAILURE = "http://localhost:8181/auth/login";
	private static final String EVIL = "http://evil.com/phishing";

	@Mock
	private SamlAuthService samlAuthService;

	@Mock
	private SamlAuthStateCache samlAuthStateCache;

	@Mock
	private HttpServletRequest request;

	private SamlRelayStateResolver resolver;

	@BeforeEach
	void setUp() {
		resolver = new SamlRelayStateResolver(samlAuthService, samlAuthStateCache);
	}

	@Test
	void validSuccessUrlIsCached() {
		when(request.getParameter("successurl")).thenReturn(VALID_SUCCESS);
		when(request.getParameter("failureurl")).thenReturn(null);
		when(samlAuthService.isValidRedirectUrl(VALID_SUCCESS)).thenReturn(true);

		resolver.convert(request);

		AuthState stored = captureStoredAuthState();
		assertEquals(VALID_SUCCESS, stored.successUrl());
		assertNull(stored.failureUrl());
	}

	@Test
	void invalidSuccessUrlIsDroppedAndNothingCached() {
		when(request.getParameter("successurl")).thenReturn(EVIL);
		when(request.getParameter("failureurl")).thenReturn(null);
		when(samlAuthService.isValidRedirectUrl(EVIL)).thenReturn(false);

		resolver.convert(request);

		verify(samlAuthStateCache, never()).storeAuthState(any(), any());
	}

	@Test
	void validFailureUrlIsCached() {
		when(request.getParameter("successurl")).thenReturn(null);
		when(request.getParameter("failureurl")).thenReturn(VALID_FAILURE);
		when(samlAuthService.isValidRedirectUrl(VALID_FAILURE)).thenReturn(true);

		resolver.convert(request);

		AuthState stored = captureStoredAuthState();
		assertNull(stored.successUrl());
		assertEquals(VALID_FAILURE, stored.failureUrl());
	}

	@Test
	void invalidFailureUrlIsDroppedAndNothingCached() {
		when(request.getParameter("successurl")).thenReturn(null);
		when(request.getParameter("failureurl")).thenReturn(EVIL);
		when(samlAuthService.isValidRedirectUrl(EVIL)).thenReturn(false);

		resolver.convert(request);

		verify(samlAuthStateCache, never()).storeAuthState(any(), any());
	}

	@Test
	void onlyTheValidUrlIsKeptWhenOneIsInvalid() {
		when(request.getParameter("successurl")).thenReturn(VALID_SUCCESS);
		when(request.getParameter("failureurl")).thenReturn(EVIL);
		when(samlAuthService.isValidRedirectUrl(VALID_SUCCESS)).thenReturn(true);
		when(samlAuthService.isValidRedirectUrl(EVIL)).thenReturn(false);

		resolver.convert(request);

		AuthState stored = captureStoredAuthState();
		assertEquals(VALID_SUCCESS, stored.successUrl());
		assertNull(stored.failureUrl());
	}

	@Test
	void bothValidUrlsAreCached() {
		when(request.getParameter("successurl")).thenReturn(VALID_SUCCESS);
		when(request.getParameter("failureurl")).thenReturn(VALID_FAILURE);
		when(samlAuthService.isValidRedirectUrl(VALID_SUCCESS)).thenReturn(true);
		when(samlAuthService.isValidRedirectUrl(VALID_FAILURE)).thenReturn(true);

		resolver.convert(request);

		AuthState stored = captureStoredAuthState();
		assertEquals(VALID_SUCCESS, stored.successUrl());
		assertEquals(VALID_FAILURE, stored.failureUrl());
	}

	@Test
	void noRedirectParametersMeansNothingCachedAndTokenIsReturned() {
		String token = resolver.convert(request);

		verify(samlAuthStateCache, never()).storeAuthState(any(), any());
		assertTrue(token.matches("[A-Za-z0-9]{16}"), "relay state token must be 16 alphanumerics, was: " + token);
	}

	private AuthState captureStoredAuthState() {
		ArgumentCaptor<AuthState> captor = ArgumentCaptor.forClass(AuthState.class);
		verify(samlAuthStateCache).storeAuthState(anyString(), captor.capture());
		return captor.getValue();
	}
}
