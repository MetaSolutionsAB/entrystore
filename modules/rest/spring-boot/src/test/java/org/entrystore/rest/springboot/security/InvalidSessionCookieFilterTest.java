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
import org.springframework.mock.web.MockHttpServletResponse;

import static org.entrystore.rest.springboot.security.AuthTokenCookiesTest.authTokenCookies;
import static org.entrystore.rest.springboot.security.AuthTokenCookiesTest.requestWithAuthToken;
import static org.junit.jupiter.api.Assertions.assertEquals;

class InvalidSessionCookieFilterTest {

	private final InvalidSessionCookieFilter filter = new InvalidSessionCookieFilter(authTokenCookies("none", false, ""));

	@Test
	void invalidRequestedSession_expiresCookieAndContinuesChain() throws Exception {
		var request = requestWithAuthToken();
		request.setRequestedSessionId("unknown");
		request.setRequestedSessionIdValid(false);
		var response = new MockHttpServletResponse();
		var chain = new MockFilterChain();

		filter.doFilter(request, response, chain);

		assertEquals(2, response.getHeaders("Set-Cookie").size(), "cookie must be expired on /store and /store/");
		assertEquals(request, chain.getRequest(), "request must continue as guest");
	}

	@Test
	void validRequestedSession_keepsCookie() throws Exception {
		var request = requestWithAuthToken();
		request.setRequestedSessionId("known");
		request.setRequestedSessionIdValid(true);
		var response = new MockHttpServletResponse();
		var chain = new MockFilterChain();

		filter.doFilter(request, response, chain);

		assertEquals(0, response.getHeaders("Set-Cookie").size());
		assertEquals(request, chain.getRequest());
	}
}
