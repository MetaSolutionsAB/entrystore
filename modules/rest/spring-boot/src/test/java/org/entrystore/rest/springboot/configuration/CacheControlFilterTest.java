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

package org.entrystore.rest.springboot.configuration;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class CacheControlFilterTest {

	private static final String DEFAULT_SESSION_COOKIE = "auth_token";

	private final CacheControlFilter filter = new CacheControlFilter(DEFAULT_SESSION_COOKIE);

	@Test
	void sessionCookie_setsPrivateNoStore() throws Exception {
		var request = new MockHttpServletRequest("GET", "/auth/user");
		request.setCookies(new Cookie(DEFAULT_SESSION_COOKIE, "session-id-xyz"));
		var response = new MockHttpServletResponse();
		var chain = new MockFilterChain();

		filter.doFilter(request, response, chain);

		assertEquals(CacheControlFilter.CACHE_CONTROL_AUTHENTICATED, response.getHeader(HttpHeaders.CACHE_CONTROL));
		assertNotNull(chain.getRequest(), "filter must always invoke the chain");
	}

	@Test
	void basicAuthorizationHeader_setsPrivateNoStore() throws Exception {
		var request = new MockHttpServletRequest("GET", "/auth/user");
		request.addHeader(HttpHeaders.AUTHORIZATION, "Basic dXNlcjpwYXNz");
		var response = new MockHttpServletResponse();
		var chain = new MockFilterChain();

		filter.doFilter(request, response, chain);

		assertEquals(CacheControlFilter.CACHE_CONTROL_AUTHENTICATED, response.getHeader(HttpHeaders.CACHE_CONTROL));
		assertNotNull(chain.getRequest(), "filter must always invoke the chain");
	}

	@Test
	void basicAuthorizationHeader_mixedCaseScheme_setsPrivateNoStore() throws Exception {
		// RFC 7235: auth-scheme is case-insensitive. CacheControlFilter#isAuthenticatedRequest
		// uses String#regionMatches(true, ...) to honour that — protect the behaviour.
		var request = new MockHttpServletRequest("GET", "/auth/user");
		request.addHeader(HttpHeaders.AUTHORIZATION, "baSIC dXNlcjpwYXNz");
		var response = new MockHttpServletResponse();
		var chain = new MockFilterChain();

		filter.doFilter(request, response, chain);

		assertEquals(CacheControlFilter.CACHE_CONTROL_AUTHENTICATED, response.getHeader(HttpHeaders.CACHE_CONTROL));
		assertNotNull(chain.getRequest(), "filter must always invoke the chain");
	}

	@Test
	void bearerAuthorizationHeader_doesNotSetCacheControl() throws Exception {
		// Only the Basic scheme is recognised — Bearer / Digest / API-key / custom schemes do
		// not flag the request as authenticated, so the filter must leave Cache-Control unset
		// and let downstream policy decide. Guards against a future refactor that broadens
		// the prefix check to "any non-null Authorization header".
		var request = new MockHttpServletRequest("GET", "/auth/user");
		request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer eyJhbGciOiJIUzI1NiJ9.payload.signature");
		var response = new MockHttpServletResponse();
		var chain = new MockFilterChain();

		filter.doFilter(request, response, chain);

		assertNull(response.getHeader(HttpHeaders.CACHE_CONTROL));
		assertNotNull(chain.getRequest(), "filter must always invoke the chain");
	}

	@Test
	void anonymousRequest_doesNotSetCacheControl() throws Exception {
		var request = new MockHttpServletRequest("GET", "/auth/user");
		var response = new MockHttpServletResponse();
		var chain = new MockFilterChain();

		filter.doFilter(request, response, chain);

		assertNull(response.getHeader(HttpHeaders.CACHE_CONTROL));
		assertNotNull(chain.getRequest(), "filter must always invoke the chain");
	}

	@Test
	void unrelatedCookie_doesNotSetCacheControl() throws Exception {
		var request = new MockHttpServletRequest("GET", "/auth/user");
		request.setCookies(new Cookie("tracking_id", "abc123"));
		var response = new MockHttpServletResponse();
		var chain = new MockFilterChain();

		filter.doFilter(request, response, chain);

		assertNull(response.getHeader(HttpHeaders.CACHE_CONTROL));
		assertNotNull(chain.getRequest(), "filter must always invoke the chain");
	}

	@Test
	void priorCacheControlHeader_isPreservedForAuthenticatedRequest() throws Exception {
		// Filter-level proof of the documented yield-to-prior-writer contract: any earlier
		// component (ResourceHttpRequestHandler for static assets, authChallengeAwareEntryPoint
		// for the Basic-Auth 401 challenge, a controller that explicitly sets the header) wins.
		var request = new MockHttpServletRequest("GET", "/favicon.ico");
		request.setCookies(new Cookie(DEFAULT_SESSION_COOKIE, "session-id-xyz"));
		var response = new MockHttpServletResponse();
		response.setHeader(HttpHeaders.CACHE_CONTROL, "public, max-age=60");
		var chain = new MockFilterChain();

		filter.doFilter(request, response, chain);

		assertEquals("public, max-age=60", response.getHeader(HttpHeaders.CACHE_CONTROL));
		assertNotNull(chain.getRequest(), "filter must always invoke the chain");
	}

	@Test
	void priorCacheControlHeader_isPreservedForAnonymousRequest() throws Exception {
		// The yield-to-prior-writer guard must also hold for anonymous traffic. A handler that
		// has explicitly opted into "public, max-age" caching for a permit-all endpoint must
		// keep that value even if a future refactor accidentally widens the filter's setHeader
		// branch beyond authenticated requests.
		var request = new MockHttpServletRequest("GET", "/management/status");
		var response = new MockHttpServletResponse();
		response.setHeader(HttpHeaders.CACHE_CONTROL, "public, max-age=300");
		var chain = new MockFilterChain();

		filter.doFilter(request, response, chain);

		assertEquals("public, max-age=300", response.getHeader(HttpHeaders.CACHE_CONTROL));
		assertNotNull(chain.getRequest(), "filter must always invoke the chain");
	}

	@Test
	void responseEstablishesSessionCookie_stampsPrivateNoStorePostChain() throws Exception {
		// Login / SAML ACS / CAS callback flows: the inbound request is anonymous, so the
		// pre-chain branch is skipped, but the outbound response writes Set-Cookie for the
		// configured session cookie name. The post-chain branch must catch this and stamp
		// Cache-Control so an intermediary cannot cache the response and replay the session
		// cookie to a different client.
		var request = new MockHttpServletRequest("POST", "/auth/cookie");
		var response = new MockHttpServletResponse();
		var chain = new FilterChainAddingSessionCookie(DEFAULT_SESSION_COOKIE, "freshly-minted");

		filter.doFilter(request, response, chain);

		assertEquals(CacheControlFilter.CACHE_CONTROL_AUTHENTICATED, response.getHeader(HttpHeaders.CACHE_CONTROL));
	}

	@Test
	void responseSettingUnrelatedCookie_doesNotStampCacheControl() throws Exception {
		// Only the configured session cookie name triggers the post-chain stamp. Setting an
		// XSRF cookie, an analytics cookie, or any other Set-Cookie value must leave
		// Cache-Control unset so non-session responses remain freely cacheable.
		var request = new MockHttpServletRequest("GET", "/management/status");
		var response = new MockHttpServletResponse();
		var chain = new FilterChainAddingSessionCookie("XSRF-TOKEN", "csrf-token-value");

		filter.doFilter(request, response, chain);

		assertNull(response.getHeader(HttpHeaders.CACHE_CONTROL));
	}

	@Test
	void priorCacheControlOnResponseEstablishingSession_isPreserved() throws Exception {
		// A handler that has already chosen its own Cache-Control on a session-establishing
		// response must keep that value — the post-chain branch is a defensive default, not
		// an override. Mirrors the pre-chain yield-to-prior-writer contract.
		var request = new MockHttpServletRequest("POST", "/auth/cookie");
		var response = new MockHttpServletResponse();
		var chain = new FilterChain() {
			@Override
			public void doFilter(jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res) {
				var httpResponse = (HttpServletResponse) res;
				httpResponse.setHeader(HttpHeaders.CACHE_CONTROL, "no-store, max-age=0");
				httpResponse.addCookie(new Cookie(DEFAULT_SESSION_COOKIE, "freshly-minted"));
			}
		};

		filter.doFilter(request, response, chain);

		assertEquals("no-store, max-age=0", response.getHeader(HttpHeaders.CACHE_CONTROL));
	}

	@Test
	void configuredCookieName_defaultNamedCookieIsIgnored() throws Exception {
		var customNameFilter = new CacheControlFilter("custom_session");

		var request = new MockHttpServletRequest("GET", "/auth/user");
		request.setCookies(new Cookie(DEFAULT_SESSION_COOKIE, "session-id-xyz"));
		var response = new MockHttpServletResponse();
		var chain = new MockFilterChain();

		customNameFilter.doFilter(request, response, chain);

		assertNull(response.getHeader(HttpHeaders.CACHE_CONTROL),
				"default-named cookie must be ignored when a custom session cookie name is configured");
		assertNotNull(chain.getRequest(), "filter must always invoke the chain");
	}

	@Test
	void configuredCookieName_customNamedCookieTriggersHeader() throws Exception {
		var customNameFilter = new CacheControlFilter("custom_session");

		var request = new MockHttpServletRequest("GET", "/auth/user");
		request.setCookies(new Cookie("custom_session", "session-id-xyz"));
		var response = new MockHttpServletResponse();
		var chain = new MockFilterChain();

		customNameFilter.doFilter(request, response, chain);

		assertEquals(CacheControlFilter.CACHE_CONTROL_AUTHENTICATED, response.getHeader(HttpHeaders.CACHE_CONTROL));
		assertNotNull(chain.getRequest(), "filter must always invoke the chain");
	}

	/**
	 * Minimal FilterChain that mutates the response with a Set-Cookie before returning,
	 * simulating an authentication handler (form-login, SAML, CAS) that establishes a session.
	 */
	private static final class FilterChainAddingSessionCookie implements FilterChain {
		private final String cookieName;
		private final String cookieValue;

		FilterChainAddingSessionCookie(String cookieName, String cookieValue) {
			this.cookieName = cookieName;
			this.cookieValue = cookieValue;
		}

		@Override
		public void doFilter(jakarta.servlet.ServletRequest request, jakarta.servlet.ServletResponse response) {
			((HttpServletResponse) response).addCookie(new Cookie(cookieName, cookieValue));
		}
	}
}
