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
import jakarta.servlet.http.HttpServletRequestWrapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.Collections;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import static org.entrystore.rest.springboot.security.AuthTokenCookiesTest.authTokenCookies;
import static org.entrystore.rest.springboot.security.AuthTokenCookiesTest.requestWithAuthToken;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

class InvalidSessionCookieFilterTest {

	private final InvalidSessionCookieFilter filter = new InvalidSessionCookieFilter(authTokenCookies("none", false, ""));

	@Test
	void invalidRequestedSession_expiresCookieAndContinuesChain() throws Exception {
		var request = requestWithAuthToken();
		request.setMethod("GET");
		request.setRequestedSessionId("unknown");
		request.setRequestedSessionIdValid(false);
		var response = new MockHttpServletResponse();
		var chain = new MockFilterChain();

		filter.doFilter(request, response, chain);

		assertEquals(2, response.getHeaders("Set-Cookie").size(), "cookie must be expired on /store and /store/");
		assertEquals(request, ((HttpServletRequestWrapper) chain.getRequest()).getRequest(),
				"request must continue as guest");
	}

	@Test
	void invalidRequestedSession_readContinuesWithoutRevalidationAndRangeHeaders() throws Exception {
		var request = requestWithAuthToken();
		request.setMethod("GET");
		request.setRequestedSessionId("unknown");
		request.setRequestedSessionIdValid(false);
		request.addHeader(HttpHeaders.IF_NONE_MATCH, "\"1700000000123\"");
		request.addHeader(HttpHeaders.IF_MODIFIED_SINCE, "Tue, 14 Nov 2023 22:13:20 GMT");
		request.addHeader(HttpHeaders.IF_RANGE, "\"1700000000123\"");
		request.addHeader(HttpHeaders.RANGE, "bytes=0-3");
		request.addHeader(HttpHeaders.IF_UNMODIFIED_SINCE, "Tue, 14 Nov 2023 22:13:20 GMT");
		request.addHeader(HttpHeaders.IF_MATCH, "\"1700000000123\"");
		var chain = new MockFilterChain();

		filter.doFilter(request, new MockHttpServletResponse(), chain);

		var continued = (HttpServletRequest) chain.getRequest();
		assertNull(continued.getHeader("if-none-match"));
		assertFalse(continued.getHeaders(HttpHeaders.IF_NONE_MATCH).hasMoreElements());
		assertEquals(-1, continued.getDateHeader(HttpHeaders.IF_MODIFIED_SINCE));
		assertNull(continued.getHeader(HttpHeaders.IF_RANGE));
		assertNull(continued.getHeader(HttpHeaders.RANGE));
		// Neither can answer a GET with a cached body: Spring ignores If-Match on GET, If-Unmodified-Since gives 412.
		assertEquals("\"1700000000123\"", continued.getHeader(HttpHeaders.IF_MATCH));
		assertEquals(1_700_000_000_000L, continued.getDateHeader(HttpHeaders.IF_UNMODIFIED_SINCE));
		assertEquals(Set.of("if-unmodified-since", "if-match"), Collections.list(continued.getHeaderNames()).stream()
				.map(name -> name.toLowerCase(Locale.ROOT))
				.filter(name -> name.startsWith("if-") || name.equals("range"))
				.collect(Collectors.toSet()));
	}

	@Test
	void invalidRequestedSession_writeKeepsItsPreconditions() throws Exception {
		var request = requestWithAuthToken();
		request.setMethod("PUT");
		request.setRequestedSessionId("unknown");
		request.setRequestedSessionIdValid(false);
		request.addHeader(HttpHeaders.IF_UNMODIFIED_SINCE, "Tue, 14 Nov 2023 22:13:20 GMT");
		var response = new MockHttpServletResponse();
		var chain = new MockFilterChain();

		filter.doFilter(request, response, chain);

		assertEquals(2, response.getHeaders("Set-Cookie").size(), "cookie must be expired on /store and /store/");
		assertEquals("Tue, 14 Nov 2023 22:13:20 GMT",
				((HttpServletRequest) chain.getRequest()).getHeader(HttpHeaders.IF_UNMODIFIED_SINCE));
	}

	@Test
	void validRequestedSession_keepsConditionalHeaders() throws Exception {
		var request = requestWithAuthToken();
		request.setRequestedSessionId("known");
		request.setRequestedSessionIdValid(true);
		request.addHeader(HttpHeaders.IF_NONE_MATCH, "\"1700000000123\"");
		var chain = new MockFilterChain();

		filter.doFilter(request, new MockHttpServletResponse(), chain);

		assertEquals("\"1700000000123\"", ((HttpServletRequest) chain.getRequest()).getHeader(HttpHeaders.IF_NONE_MATCH));
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
