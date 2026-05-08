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
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CsrfRequestMatcherTest {

	private final CsrfRequestMatcher matcher = new CsrfRequestMatcher("auth_token");

	@Test
	void safeMethods_neverRequireCsrf() {
		for (String method : new String[]{"GET", "HEAD", "TRACE", "OPTIONS"}) {
			var request = new MockHttpServletRequest(method, "/_principals/groups");
			request.setCookies(new Cookie("auth_token", "session-id"));
			assertFalse(matcher.matches(request), method + " should never require CSRF");
		}
	}

	@Test
	void unsafeMethodWithoutSessionCookie_skipsCsrf() {
		for (String method : new String[]{"POST", "PUT", "PATCH", "DELETE"}) {
			var request = new MockHttpServletRequest(method, "/_principals/groups");
			assertFalse(matcher.matches(request), method + " without session cookie should skip CSRF");
		}
	}

	@Test
	void unsafeMethodWithSessionCookieOnUnprotectedPath_requiresCsrf() {
		// Cover every method outside SAFE_METHODS so a regression that drops one (e.g. dropping PATCH
		// from the unsafe set) trips this test, not the integration suite.
		for (String method : new String[]{"POST", "PUT", "PATCH", "DELETE"}) {
			var request = new MockHttpServletRequest(method, "/_principals/groups");
			request.setCookies(new Cookie("auth_token", "session-id"));
			assertTrue(matcher.matches(request), method + " with session cookie on protected path should require CSRF");
		}
	}

	@Test
	void unsafeMethodWithSessionCookieOnLogin_skipsCsrf() {
		var request = new MockHttpServletRequest("POST", "/auth/cookie");
		request.setCookies(new Cookie("auth_token", "session-id"));
		assertFalse(matcher.matches(request));
	}

	@Test
	void unsafeMethodWithSessionCookieOnSignup_skipsCsrf() {
		var request = new MockHttpServletRequest("POST", "/auth/signup");
		request.setCookies(new Cookie("auth_token", "session-id"));
		assertFalse(matcher.matches(request));
	}

	@Test
	void unsafeMethodWithSessionCookieOnPasswordReset_skipsCsrf() {
		var request = new MockHttpServletRequest("POST", "/auth/pwreset");
		request.setCookies(new Cookie("auth_token", "session-id"));
		assertFalse(matcher.matches(request));
	}

	@Test
	void unsafeMethodWithSessionCookieOnSamlAcs_skipsCsrf() {
		var request = new MockHttpServletRequest("POST", "/login/saml2/sso/keycloak");
		request.setCookies(new Cookie("auth_token", "session-id"));
		assertFalse(matcher.matches(request));
	}

	@Test
	void unsafeMethodWithSessionCookieOnManagement_requiresCsrf() {
		// Mutating /management/** endpoints (logging, solr reindex, actuator shutdown) are NOT
		// exempt — a cookie-authenticated user submitting these is in CSRF scope.
		var request = new MockHttpServletRequest("PUT", "/management/logging");
		request.setCookies(new Cookie("auth_token", "session-id"));
		assertTrue(matcher.matches(request));

		var request2 = new MockHttpServletRequest("POST", "/management/solr");
		request2.setCookies(new Cookie("auth_token", "session-id"));
		assertTrue(matcher.matches(request2));

		var request3 = new MockHttpServletRequest("POST", "/management/shutdown");
		request3.setCookies(new Cookie("auth_token", "session-id"));
		assertTrue(matcher.matches(request3));
	}

	@Test
	void safeMethodOnManagement_skipsCsrf() {
		// Read-only actuator and status calls are GETs and therefore exempt via SAFE_METHODS,
		// not via path matching.
		var request = new MockHttpServletRequest("GET", "/management/status");
		request.setCookies(new Cookie("auth_token", "session-id"));
		assertFalse(matcher.matches(request));

		var request2 = new MockHttpServletRequest("GET", "/management/metrics");
		request2.setCookies(new Cookie("auth_token", "session-id"));
		assertFalse(matcher.matches(request2));
	}

	@Test
	void unsafeMethodWithSessionCookieOnLogout_requiresCsrf() {
		var request = new MockHttpServletRequest("POST", "/auth/logout");
		request.setCookies(new Cookie("auth_token", "session-id"));
		assertTrue(matcher.matches(request));
	}

	@Test
	void unrelatedCookieDoesNotTriggerCsrf() {
		var request = new MockHttpServletRequest("POST", "/_principals/groups");
		request.setCookies(new Cookie("XSRF-TOKEN", "csrf-value"));
		assertFalse(matcher.matches(request));
	}

	@Test
	void customSessionCookieName_usedForDetection() {
		var customMatcher = new CsrfRequestMatcher("custom_session");
		var request = new MockHttpServletRequest("POST", "/_principals/groups");
		request.setCookies(new Cookie("custom_session", "session-id"));
		assertTrue(customMatcher.matches(request));

		var requestWithDefault = new MockHttpServletRequest("POST", "/_principals/groups");
		requestWithDefault.setCookies(new Cookie("auth_token", "session-id"));
		assertFalse(customMatcher.matches(requestWithDefault));
	}
}
