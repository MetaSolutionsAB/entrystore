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
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.DefaultCsrfToken;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CsrfCookieFilterTest {

	private final CsrfCookieFilter filter = new CsrfCookieFilter("auth_token", "XSRF-TOKEN");

	@Test
	void unsafeMethod_resolvesToken() throws Exception {
		var resolutions = new AtomicInteger();
		var request = new MockHttpServletRequest("POST", "/auth/cookie");
		request.setAttribute(CsrfToken.class.getName(), countingToken(resolutions));
		var chain = new MockFilterChain();

		filter.doFilter(request, new MockHttpServletResponse(), chain);

		assertEquals(1, resolutions.get(), "POST must trigger CSRF token resolution");
		assertEquals(request, chain.getRequest(), "Filter chain must still be invoked");
	}

	@Test
	void safeMethodWithoutCsrfCookie_skipsResolution() throws Exception {
		// Server-to-server callers (harvesters, monitoring, Basic-auth API clients) take this path —
		// no SecureRandom UUID, no Set-Cookie: XSRF-TOKEN written.
		var resolutions = new AtomicInteger();
		var request = new MockHttpServletRequest("GET", "/_principals/groups");
		request.setAttribute(CsrfToken.class.getName(), countingToken(resolutions));
		var chain = new MockFilterChain();

		filter.doFilter(request, new MockHttpServletResponse(), chain);

		assertEquals(0, resolutions.get(), "GET without session cookie or XSRF-TOKEN cookie must skip token resolution");
		assertEquals(request, chain.getRequest(), "Filter chain must still be invoked");
	}

	@Test
	void safeMethodWithCsrfCookie_resolvesToken() throws Exception {
		// SPA already has a session — keep the XSRF-TOKEN cookie alive on the response.
		var resolutions = new AtomicInteger();
		var request = new MockHttpServletRequest("GET", "/auth/user");
		request.setCookies(new Cookie("XSRF-TOKEN", "existing-token"));
		request.setAttribute(CsrfToken.class.getName(), countingToken(resolutions));
		var chain = new MockFilterChain();

		filter.doFilter(request, new MockHttpServletResponse(), chain);

		assertEquals(1, resolutions.get(), "GET with XSRF-TOKEN cookie must resolve token to refresh it");
		assertEquals(request, chain.getRequest(), "Filter chain must still be invoked");
	}

	@Test
	void safeMethodWithSessionCookieOnly_resolvesToken() throws Exception {
		// Bootstrap path: an authenticated session that pre-dates this rollout (or whose XSRF-TOKEN
		// cookie was cleared while the session cookie survived) must be able to mint a new token on
		// its next safe GET. Without this, the next mutation would be rejected before CsrfCookieFilter
		// could write a replacement cookie.
		var resolutions = new AtomicInteger();
		var request = new MockHttpServletRequest("GET", "/auth/user");
		request.setCookies(new Cookie("auth_token", "session-id"));
		request.setAttribute(CsrfToken.class.getName(), countingToken(resolutions));
		var chain = new MockFilterChain();

		filter.doFilter(request, new MockHttpServletResponse(), chain);

		assertEquals(1, resolutions.get(), "GET with session cookie but no XSRF-TOKEN cookie must mint a new token");
		assertEquals(request, chain.getRequest(), "Filter chain must still be invoked");
	}

	@Test
	void unrelatedCookieDoesNotTriggerResolutionOnSafeMethod() throws Exception {
		// An XSRF-TOKEN cookie alone does not trigger SPA token resolution unless either the method is
		// unsafe or the session cookie is present — a bare unrelated cookie on a GET stays the
		// server-to-server fast path.
		var resolutions = new AtomicInteger();
		var request = new MockHttpServletRequest("GET", "/_principals/groups");
		request.setCookies(new Cookie("unrelated", "value"));
		request.setAttribute(CsrfToken.class.getName(), countingToken(resolutions));
		var chain = new MockFilterChain();

		filter.doFilter(request, new MockHttpServletResponse(), chain);

		assertEquals(0, resolutions.get(), "Unrelated cookies must not trigger token resolution on safe methods");
		assertEquals(request, chain.getRequest(), "Filter chain must still be invoked");
	}

	@Test
	void noCsrfTokenAttribute_doesNotCrash() throws Exception {
		// Defensive: if the CsrfFilter never set the attribute (e.g. CSRF disabled in some test slice),
		// the filter must still pass the request through.
		var request = new MockHttpServletRequest("POST", "/auth/cookie");
		var response = new MockHttpServletResponse();
		var chain = new MockFilterChain();

		filter.doFilter(request, response, chain);

		// MockFilterChain advances when doFilter is called on it.
		assertEquals(request, chain.getRequest(), "Filter chain must still be invoked");
	}

	private static CsrfToken countingToken(AtomicInteger counter) {
		var delegate = new DefaultCsrfToken("X-XSRF-TOKEN", "_csrf", "the-token");
		return new CsrfToken() {
			@Override public String getHeaderName() { return delegate.getHeaderName(); }
			@Override public String getParameterName() { return delegate.getParameterName(); }
			@Override public String getToken() {
				counter.incrementAndGet();
				return delegate.getToken();
			}
		};
	}
}
