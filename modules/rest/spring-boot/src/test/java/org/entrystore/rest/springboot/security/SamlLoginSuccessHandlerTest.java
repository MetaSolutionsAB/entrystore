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
import org.entrystore.rest.springboot.configuration.SamlCustomConfiguration;
import org.entrystore.rest.springboot.service.SamlAuthService;
import org.entrystore.rest.springboot.service.auth.SamlAuthStateCache;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class SamlLoginSuccessHandlerTest {

	private static final String FAILURE_URL = "http://localhost:8181/auth/login";
	private static final String SUCCESS_URL = "http://localhost:8181/welcome";

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

	private SamlLoginSuccessHandler handler;

	@BeforeEach
	void setUp() {
		var samlConfiguration = new SamlCustomConfiguration(
				true, null,
				new SamlCustomConfiguration.RedirectSuccess(SUCCESS_URL),
				new SamlCustomConfiguration.RedirectFailure(FAILURE_URL));
		handler = new SamlLoginSuccessHandler(userService, samlAuthService, samlAuthStateCache,
				principalManager, samlConfiguration);
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
}
