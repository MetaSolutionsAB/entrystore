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
import org.springframework.boot.web.server.Cookie;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SecurityConfigTest {

	private static final String KEY = "server.servlet.session.cookie.same-site";

	@Test
	void resolveSessionCookieSameSite_returnsStrictForExplicitStrict() {
		var env = new MockEnvironment().withProperty(KEY, "strict");
		assertEquals(Cookie.SameSite.STRICT, SecurityConfig.resolveSessionCookieSameSite(env));
	}

	@Test
	void resolveSessionCookieSameSite_returnsLaxForLax() {
		var env = new MockEnvironment().withProperty(KEY, "lax");
		assertEquals(Cookie.SameSite.LAX, SecurityConfig.resolveSessionCookieSameSite(env));
	}

	@Test
	void resolveSessionCookieSameSite_returnsNoneForNone() {
		// SameSite=None must round-trip — csrfTokenRepository() relies on this to auto-enable Secure.
		var env = new MockEnvironment().withProperty(KEY, "none");
		assertEquals(Cookie.SameSite.NONE, SecurityConfig.resolveSessionCookieSameSite(env));
	}

	@Test
	void resolveSessionCookieSameSite_relaxedBindingHandlesCaseAndDashes() {
		// Spring Boot's relaxed binding accepts NONE / None / Strict / strict / lax-style variants.
		// If a future upgrade tightens this, the test fails loudly rather than silently flipping to STRICT.
		assertEquals(Cookie.SameSite.NONE,
				SecurityConfig.resolveSessionCookieSameSite(new MockEnvironment().withProperty(KEY, "NONE")));
		assertEquals(Cookie.SameSite.NONE,
				SecurityConfig.resolveSessionCookieSameSite(new MockEnvironment().withProperty(KEY, "None")));
		assertEquals(Cookie.SameSite.STRICT,
				SecurityConfig.resolveSessionCookieSameSite(new MockEnvironment().withProperty(KEY, "Strict")));
	}

	@Test
	void resolveSessionCookieSameSite_unsetDefaultsToStrict() {
		// Property absent — Binder.orElse(STRICT) kicks in. Strict is the safest default for a
		// cookie-authenticated app when the operator did not pick a value.
		assertEquals(Cookie.SameSite.STRICT, SecurityConfig.resolveSessionCookieSameSite(new MockEnvironment()));
	}

	@Test
	void resolveSessionCookieSameSite_invalidValueFallsBackToStrict() {
		// The whole point of the WARN-on-BindException branch: a typo like "Nonee" must NOT silently
		// bind to NONE (which would auto-enable Secure on cookies the operator did not actually mark
		// SameSite=None) and must NOT throw and break startup. It falls back to STRICT.
		var env = new MockEnvironment().withProperty(KEY, "Nonee");
		assertEquals(Cookie.SameSite.STRICT, SecurityConfig.resolveSessionCookieSameSite(env));
	}

	@Test
	void resolveSessionCookieSameSite_emptyValueFallsBackToStrict() {
		// Empty string isn't a valid SameSite enum constant — must fall back, not throw.
		var env = new MockEnvironment().withProperty(KEY, "");
		assertEquals(Cookie.SameSite.STRICT, SecurityConfig.resolveSessionCookieSameSite(env));
	}

	@Test
	void requiresSecureCookie_configuredSecureAlwaysSecure() {
		// Operator opted in to Secure — every SameSite value must produce Secure=true.
		assertTrue(SecurityConfig.requiresSecureCookie(true, Cookie.SameSite.NONE));
		assertTrue(SecurityConfig.requiresSecureCookie(true, Cookie.SameSite.LAX));
		assertTrue(SecurityConfig.requiresSecureCookie(true, Cookie.SameSite.STRICT));
	}

	@Test
	void requiresSecureCookie_sameSiteNoneAutoEnablesSecure() {
		// SameSite=None is illegal without Secure — browsers silently drop the cookie. The CSRF
		// repo must auto-enable Secure even when the operator did not configure it.
		assertTrue(SecurityConfig.requiresSecureCookie(false, Cookie.SameSite.NONE));
	}

	@Test
	void requiresSecureCookie_laxAndStrictDoNotAutoEnableSecure() {
		// Local/CI on plain HTTP runs with samesite=lax or strict and secure=off. A bug auto-setting
		// Secure for LAX/STRICT would cause browsers to silently reject the cookie on the test rig.
		assertFalse(SecurityConfig.requiresSecureCookie(false, Cookie.SameSite.LAX));
		assertFalse(SecurityConfig.requiresSecureCookie(false, Cookie.SameSite.STRICT));
	}
}
