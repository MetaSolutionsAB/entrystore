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

package org.entrystore.rest.springboot.service;

import org.entrystore.rest.springboot.configuration.SamlCustomConfiguration;
import org.entrystore.rest.springboot.configuration.SamlCustomConfiguration.Idp;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class SamlAuthServiceTest {

	private SamlAuthService service;

	@BeforeEach
	void setUp() {
		var config = new SamlCustomConfiguration(true, "keycloak", List.of("localhost"), Map.of(), null, null);
		service = new SamlAuthService(config);
	}

	static Stream<Arguments> redirectUrls() {
		return Stream.of(
				Arguments.of("http://localhost:8181/welcome", true),
				Arguments.of("http://localhost/", true),
				Arguments.of("http://evil.com/phishing", false),
				Arguments.of("http://localhost@evil.com/x", false), // userinfo trick: real host is evil.com
				Arguments.of("//evil.com/x", false),                // scheme-relative: host is evil.com
				Arguments.of("https://localhost.evil.com/x", false), // look-alike subdomain
				Arguments.of("HTTP://LOCALHOST/x", false),           // whitelist match is case-sensitive (fail-closed)
				Arguments.of("ftp://localhost/x", false),            // disallowed scheme even on a whitelisted host
				Arguments.of("/relative/path", false),               // no host
				Arguments.of("http://exa mple.com/x", false),        // malformed: URI.create throws -> rejected, not propagated
				Arguments.of("", false),
				Arguments.of(null, false)
		);
	}

	@ParameterizedTest(name = "isValidRedirectUrl(\"{0}\") == {1}")
	@MethodSource("redirectUrls")
	void isValidRedirectUrlEnforcesWhitelistAndRejectsMalformedUrls(String url, boolean expected) {
		assertEquals(expected, service.isValidRedirectUrl(url));
	}

	private static SamlAuthService serviceWithIdps(String defaultIdp, Map<String, Idp> idps) {
		return new SamlAuthService(new SamlCustomConfiguration(true, defaultIdp, List.of(), idps, null, null));
	}

	@Test
	void findIdpIdForRequest_exactDomainMatchWinsOverWildcard() {
		var svc = serviceWithIdps("keycloak", Map.of(
				"keycloak", new Idp(List.of("*"), true, null),
				"corp", new Idp(List.of("example.com"), false, null)));

		assertEquals("corp", svc.findIdpIdForRequest("user@example.com", null));
	}

	@Test
	void findIdpIdForRequest_unmatchedDomainFallsBackToWildcardIdp() {
		var svc = serviceWithIdps("keycloak", Map.of(
				"keycloak", new Idp(List.of("*"), true, null),
				"corp", new Idp(List.of("example.com"), false, null)));

		assertEquals("keycloak", svc.findIdpIdForRequest("user@other.com", null));
	}

	@Test
	void findIdpIdForRequest_explicitIdpParameterIsUsedWhenNoUsername() {
		var svc = serviceWithIdps("keycloak", Map.of("keycloak", new Idp(List.of("*"), true, null)));

		assertEquals("explicit", svc.findIdpIdForRequest(null, "explicit"));
	}

	@Test
	void findIdpIdForRequest_fallsBackToDefaultIdp() {
		var svc = serviceWithIdps("keycloak", Map.of("keycloak", new Idp(List.of("*"), true, null)));

		assertEquals("keycloak", svc.findIdpIdForRequest(null, null));
	}

	@Test
	void findIdpForSamlResponse_returnsKeyedIdp() {
		var corp = new Idp(List.of("example.com"), false, null);
		var svc = serviceWithIdps("keycloak", Map.of("keycloak", new Idp(List.of("*"), true, null), "corp", corp));

		assertEquals(corp, svc.findIdpForSamlResponse("corp"));
	}

	@Test
	void findIdpForSamlResponse_blankNameResolvesToDefaultIdp() {
		var keycloak = new Idp(List.of("*"), true, null);
		var svc = serviceWithIdps("keycloak", Map.of("keycloak", keycloak));

		assertEquals(keycloak, svc.findIdpForSamlResponse(null));
	}

	@Test
	void findIdpForSamlResponse_unknownIdpReturnsNull() {
		var svc = serviceWithIdps("keycloak", Map.of("keycloak", new Idp(List.of("*"), true, null)));

		assertNull(svc.findIdpForSamlResponse("nope"));
	}
}
