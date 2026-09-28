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

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class StaleSessionCookieFilterTest {

	private final StaleSessionCookieFilter filter = new StaleSessionCookieFilter("auth_token");

	@Test
	void staleSessionCookie_isExpiredAndHiddenFromTheChain() throws Exception {
		var request = requestWithSessionCookie(false);
		var response = new MockHttpServletResponse();
		var chain = new MockFilterChain();

		filter.doFilter(request, response, chain);

		Cookie cleared = response.getCookie("auth_token");
		assertNotNull(cleared, "the stale cookie must be expired on the response");
		assertEquals(0, cleared.getMaxAge());
		var downstream = (HttpServletRequest) chain.getRequest();
		assertNull(downstream.getRequestedSessionId());
		assertFalse(downstream.isRequestedSessionIdValid());
		assertEquals(1, downstream.getCookies().length);
		assertEquals("XSRF-TOKEN", downstream.getCookies()[0].getName());
	}

	@Test
	void validSessionCookie_passesThroughUntouched() throws Exception {
		var request = requestWithSessionCookie(true);
		var response = new MockHttpServletResponse();
		var chain = new MockFilterChain();

		filter.doFilter(request, response, chain);

		assertSame(request, chain.getRequest());
		assertNull(response.getCookie("auth_token"));
	}

	private static MockHttpServletRequest requestWithSessionCookie(boolean sessionValid) {
		var request = new MockHttpServletRequest("POST", "/_principals/groups");
		request.setCookies(new Cookie("auth_token", "session-id"), new Cookie("XSRF-TOKEN", "csrf"));
		request.setRequestedSessionId("session-id");
		request.setRequestedSessionIdValid(sessionValid);
		return request;
	}
}
