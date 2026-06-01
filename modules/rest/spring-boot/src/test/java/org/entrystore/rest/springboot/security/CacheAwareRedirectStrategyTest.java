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
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static jakarta.servlet.http.HttpServletResponse.SC_MOVED_TEMPORARILY;
import static org.junit.jupiter.api.Assertions.assertEquals;

class CacheAwareRedirectStrategyTest {

	private final CacheAwareRedirectStrategy strategy = new CacheAwareRedirectStrategy();

	@Test
	void sendRedirect_setsPrivateNoStore_andIssues302WithLocation() throws Exception {
		// Closes the SAML/CAS bypass identified in PR #283 review: the 302 response
		// carrying the session Set-Cookie must ship with Cache-Control: private, no-store
		// so a shared cache cannot replay it. The strategy stamps the header BEFORE
		// super.sendRedirect commits the response, which is the whole point.
		var request = new MockHttpServletRequest("POST", "/login/saml2/sso/example-idp");
		var response = new MockHttpServletResponse();

		strategy.sendRedirect(request, response, "/store/_principals/resource/_currentuser");

		// Literal anchor (not the CacheControlFilter constant) so that a regression to the constant's
		// value — e.g. changing it to "" or to a cacheable directive — fails this test instead of
		// silently shifting both production code and assertion together.
		assertEquals("private, no-store", response.getHeader(HttpHeaders.CACHE_CONTROL));
		assertEquals(SC_MOVED_TEMPORARILY, response.getStatus());
		assertEquals("/store/_principals/resource/_currentuser", response.getRedirectedUrl());
	}

	@Test
	void sendRedirect_preservesExistingCacheControlHeader() throws Exception {
		// Yield-to-prior-writer contract: an upstream component that has chosen its own
		// Cache-Control on the response keeps that value. Mirrors CacheControlFilter's guard.
		var request = new MockHttpServletRequest("GET", "/auth/cas");
		var response = new MockHttpServletResponse();
		response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store, max-age=0");

		strategy.sendRedirect(request, response, "/store/dashboard");

		assertEquals("no-store, max-age=0", response.getHeader(HttpHeaders.CACHE_CONTROL));
		assertEquals(SC_MOVED_TEMPORARILY, response.getStatus());
	}

	@Test
	void sendRedirect_withAbsoluteUrl_redirectsAndStampsHeader() throws Exception {
		// Absolute redirect target — common in SAML success-URL configuration. DefaultRedirectStrategy
		// passes absolute URLs through unchanged.
		var request = new MockHttpServletRequest("POST", "/login/saml2/sso/example-idp");
		var response = new MockHttpServletResponse();

		strategy.sendRedirect(request, response, "https://entrystore.example/store/welcome");

		assertEquals("private, no-store", response.getHeader(HttpHeaders.CACHE_CONTROL));
		assertEquals("https://entrystore.example/store/welcome", response.getRedirectedUrl());
	}
}
