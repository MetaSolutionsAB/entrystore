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

	private final CsrfCookieFilter filter = new CsrfCookieFilter();

	@Test
	void unsafeMethod_resolvesToken() throws Exception {
		var resolutions = new AtomicInteger();
		var request = new MockHttpServletRequest("POST", "/auth/cookie");
		request.setAttribute(CsrfToken.class.getName(), countingToken(resolutions));

		filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

		assertEquals(1, resolutions.get(), "POST must trigger CSRF token resolution");
	}

	@Test
	void safeMethodWithoutCsrfCookie_skipsResolution() throws Exception {
		// Server-to-server callers (harvesters, monitoring, Basic-auth API clients) take this path —
		// no SecureRandom UUID, no Set-Cookie: XSRF-TOKEN written.
		var resolutions = new AtomicInteger();
		var request = new MockHttpServletRequest("GET", "/_principals/groups");
		request.setAttribute(CsrfToken.class.getName(), countingToken(resolutions));

		filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

		assertEquals(0, resolutions.get(), "GET without XSRF-TOKEN cookie must skip token resolution");
	}

	@Test
	void safeMethodWithCsrfCookie_resolvesToken() throws Exception {
		// SPA already has a session — keep the XSRF-TOKEN cookie alive on the response.
		var resolutions = new AtomicInteger();
		var request = new MockHttpServletRequest("GET", "/auth/user");
		request.setCookies(new Cookie("XSRF-TOKEN", "existing-token"));
		request.setAttribute(CsrfToken.class.getName(), countingToken(resolutions));

		filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

		assertEquals(1, resolutions.get(), "GET with XSRF-TOKEN cookie must resolve token to refresh it");
	}

	@Test
	void unrelatedCookieDoesNotTriggerResolutionOnSafeMethod() throws Exception {
		// auth_token alone (Basic-auth migration scenario, or partial cookie state) does not pull
		// the SPA token resolution path on a GET.
		var resolutions = new AtomicInteger();
		var request = new MockHttpServletRequest("GET", "/auth/user");
		request.setCookies(new Cookie("auth_token", "session-id"));
		request.setAttribute(CsrfToken.class.getName(), countingToken(resolutions));

		filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

		assertEquals(0, resolutions.get(), "Unrelated cookies must not trigger token resolution on safe methods");
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
