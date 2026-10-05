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

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionLifetimeFilterTest {

	@Test
	void fixedExpiration_loginPastItsLifetime_endsTheSessionAndContinues() throws Exception {
		var cookies = AuthTokenCookiesTest.authTokenCookies("3600", false);
		var request = new MockHttpServletRequest();
		cookies.applySessionLifetime(request, null);
		var session = (MockHttpSession) request.getSession();
		session.setAttribute(AuthTokenCookies.LOGIN_EXPIRY_ATTRIBUTE, Instant.now().minusSeconds(1));
		var chain = new MockFilterChain();

		new SessionLifetimeFilter(cookies).doFilter(request, new MockHttpServletResponse(), chain);

		assertTrue(session.isInvalid());
		assertNotNull(chain.getRequest());
	}

	@Test
	void fixedExpiration_loginWithinItsLifetime_keepsTheSession() throws Exception {
		var cookies = AuthTokenCookiesTest.authTokenCookies("3600", false);
		var request = new MockHttpServletRequest();
		cookies.applySessionLifetime(request, null);
		var session = (MockHttpSession) request.getSession();
		var chain = new MockFilterChain();

		new SessionLifetimeFilter(cookies).doFilter(request, new MockHttpServletResponse(), chain);

		assertFalse(session.isInvalid());
		assertNotNull(chain.getRequest());
	}

	@Test
	void refreshExpirationOnAccess_neverEndsTheSession() throws Exception {
		var cookies = AuthTokenCookiesTest.authTokenCookies("3600", true);
		var request = new MockHttpServletRequest();
		cookies.applySessionLifetime(request, null);
		var session = (MockHttpSession) request.getSession();
		session.setAttribute(AuthTokenCookies.LOGIN_EXPIRY_ATTRIBUTE, Instant.now().minusSeconds(1));

		new SessionLifetimeFilter(cookies).doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

		assertFalse(session.isInvalid());
	}

	@Test
	void requestWithoutSession_isLeftAlone() throws Exception {
		var request = new MockHttpServletRequest();
		var chain = new MockFilterChain();

		new SessionLifetimeFilter(AuthTokenCookiesTest.authTokenCookies("1", false))
				.doFilter(request, new MockHttpServletResponse(), chain);

		assertNull(request.getSession(false));
		assertNotNull(chain.getRequest());
	}
}
