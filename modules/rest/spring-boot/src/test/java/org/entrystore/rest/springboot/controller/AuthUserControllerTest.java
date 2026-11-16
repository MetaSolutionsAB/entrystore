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

package org.entrystore.rest.springboot.controller;

import org.entrystore.rest.springboot.security.AuthTokenCookies;
import org.entrystore.rest.springboot.service.TokenService;
import org.entrystore.rest.springboot.service.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;

import java.time.Instant;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuthUserControllerTest {

	private final UserService userService = mock(UserService.class);
	private final AuthTokenCookies authTokenCookies = mock(AuthTokenCookies.class);
	private final AuthUserController controller =
			new AuthUserController(userService, mock(TokenService.class), authTokenCookies);

	@Test
	void sessionOfTheRequest_givesTheExpiryOfItsLogin() {
		var request = new MockHttpServletRequest("GET", "/auth/user");
		var expiry = Instant.parse("2026-11-16T12:00:00Z");
		when(authTokenCookies.expiry(any())).thenReturn(expiry);
		request.setSession(new MockHttpSession());

		controller.userInfo("en", request);

		verify(userService).getUserInfo("en", expiry);
	}

	@Test
	void sessionThatAConcurrentRequestHasEnded_isAnsweredWithoutAnExpiry() {
		var request = new MockHttpServletRequest("GET", "/auth/user");
		when(authTokenCookies.expiry(any())).thenThrow(new IllegalStateException("ended"));
		request.setSession(new MockHttpSession());

		controller.userInfo("en", request);

		verify(userService).getUserInfo("en", null);
	}
}
