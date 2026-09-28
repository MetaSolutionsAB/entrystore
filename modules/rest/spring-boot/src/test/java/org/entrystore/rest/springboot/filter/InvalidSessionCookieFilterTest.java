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

package org.entrystore.rest.springboot.filter;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class InvalidSessionCookieFilterTest {

	@AfterEach
	void clearAuthentication() {
		SecurityContextHolder.clearContext();
	}

	@Test
	void staleCookieIsClearedAndRequestContinuesAsGuest() throws Exception {
		var request = staleRequest();
		var response = new MockHttpServletResponse();
		var chain = new MockFilterChain();

		new InvalidSessionCookieFilter().doFilter(request, response, chain);

		assertNotNull(chain.getRequest());
		assertNull(SecurityContextHolder.getContext().getAuthentication());
		assertEquals(200, response.getStatus());
		assertEquals(0, response.getCookie("auth_token").getMaxAge());
		assertEquals("/store", response.getCookie("auth_token").getPath());
	}

	@Test
	void validAuthenticationTakesPrecedenceOverStaleCookie() throws Exception {
		var authentication = UsernamePasswordAuthenticationToken.authenticated("user", null, List.of());
		SecurityContextHolder.getContext().setAuthentication(authentication);
		var response = new MockHttpServletResponse();
		var chain = new MockFilterChain();

		new InvalidSessionCookieFilter().doFilter(staleRequest(), response, chain);

		assertNotNull(chain.getRequest());
		assertEquals(authentication, SecurityContextHolder.getContext().getAuthentication());
		assertNull(response.getCookie("auth_token"));
	}

	@Test
	void validSessionCookieIsRetained() throws Exception {
		var request = staleRequest();
		request.setRequestedSessionIdValid(true);
		var response = new MockHttpServletResponse();
		new InvalidSessionCookieFilter().doFilter(request, response, new MockFilterChain());
		assertNull(response.getCookie("auth_token"));
	}

	@Test
	void customCookieNamePathAndDomainAreUsedForRemoval() throws Exception {
		var request = staleRequest();
		var settings = request.getServletContext().getSessionCookieConfig();
		settings.setName("custom_session");
		settings.setPath("/");
		settings.setDomain("example.com");
		var response = new MockHttpServletResponse();

		new InvalidSessionCookieFilter().doFilter(request, response, new MockFilterChain());

		assertEquals("/", response.getCookie("custom_session").getPath());
		assertEquals("example.com", response.getCookie("custom_session").getDomain());
		assertEquals(0, response.getCookie("custom_session").getMaxAge());
	}

	private MockHttpServletRequest staleRequest() {
		var request = new MockHttpServletRequest("GET", "/store/auth/user");
		request.setContextPath("/store");
		request.setRequestedSessionId("expired");
		request.setRequestedSessionIdFromCookie(true);
		request.setRequestedSessionIdValid(false);
		return request;
	}
}
