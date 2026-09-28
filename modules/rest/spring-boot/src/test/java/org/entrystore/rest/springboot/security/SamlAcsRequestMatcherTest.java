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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SamlAcsRequestMatcherTest {

	@ParameterizedTest(name = "{0}")
	@CsvSource({
			"modern POST, POST, /store/login/saml2/sso/acme, true",
			"modern GET, GET, /store/login/saml2/sso/acme, true",
			"modern issuer route, POST, /store/login/saml2/sso, true",
			"legacy callback, POST, /store/auth/saml, true",
			"login initiation, GET, /store/auth/saml, false",
			"unrelated mutation, POST, /store/auth/user, false",
			"legacy wrong method, PUT, /store/auth/saml, false"
	})
	void matchesOnlySupportedRoutes(String name, String method, String uri, boolean expected) {
		var request = new MockHttpServletRequest(method, uri);
		request.setContextPath("/store");
		request.setParameter("idp", "acme");
		assertEquals(expected, new SamlAcsRequestMatcher("").matches(request));
	}

	@Test
	void legacyIdpIsExposedAsRegistrationId() {
		var request = new MockHttpServletRequest("POST", "/auth/saml");
		request.setParameter("idp", "acme");
		var result = new SamlAcsRequestMatcher("").matcher(request);
		assertTrue(result.isMatch());
		assertEquals("acme", result.getVariables().get("registrationId"));
	}

	@Test
	void configuredLegacyPathMatchesWithContextPrefix() {
		var request = new MockHttpServletRequest("POST", "/store/custom/acs");
		request.setContextPath("/store");
		var matcher = new SamlAcsRequestMatcher("https://external.example/store/custom/acs?tenant=acme");
		assertTrue(matcher.matches(request));
		assertFalse(matcher.matches(new MockHttpServletRequest("GET", "/store/custom/acs")));
	}
}
