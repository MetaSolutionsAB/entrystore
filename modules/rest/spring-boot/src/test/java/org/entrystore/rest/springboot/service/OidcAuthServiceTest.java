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

import org.entrystore.rest.springboot.configuration.OidcCustomConfiguration;
import org.entrystore.rest.springboot.configuration.OidcCustomConfiguration.Provider;
import org.entrystore.rest.springboot.model.exception.BadRequestException;
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
import static org.junit.jupiter.api.Assertions.assertThrows;

class OidcAuthServiceTest {

	private OidcAuthService service;

	@BeforeEach
	void setUp() {
		var config = new OidcCustomConfiguration(true, "keycloak", List.of("localhost"), Map.of(), null, null);
		service = new OidcAuthService(config);
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

	private static OidcAuthService serviceWithProviders(String defaultProvider, Map<String, Provider> providers) {
		return new OidcAuthService(new OidcCustomConfiguration(true, defaultProvider, List.of(), providers, null, null));
	}

	@Test
	void findProviderIdForRequest_exactDomainMatchWinsOverWildcard() {
		var svc = serviceWithProviders("keycloak", Map.of(
				"keycloak", new Provider(List.of("*"), true, null),
				"corp", new Provider(List.of("example.com"), false, null)));

		assertEquals("corp", svc.findProviderIdForRequest("user@example.com", null));
	}

	@Test
	void findProviderIdForRequest_unmatchedDomainFallsBackToWildcardProvider() {
		var svc = serviceWithProviders("keycloak", Map.of(
				"keycloak", new Provider(List.of("*"), true, null),
				"corp", new Provider(List.of("example.com"), false, null)));

		assertEquals("keycloak", svc.findProviderIdForRequest("user@other.com", null));
	}

	// Deliberate deviation from the SAML equivalent (which surfaces a null IdP id here): with no
	// wildcard provider, an unmatched email domain falls through to the default provider instead
	// of producing a redirect to /oauth2/authorization/null.
	@Test
	void findProviderIdForRequest_unmatchedDomainWithoutWildcardFallsThroughToDefault() {
		var svc = serviceWithProviders("keycloak", Map.of(
				"corp", new Provider(List.of("example.com"), false, null),
				"keycloak", new Provider(List.of("keycloak.org"), true, null)));

		assertEquals("keycloak", svc.findProviderIdForRequest("user@other.com", null));
	}

	@Test
	void findProviderIdForRequest_unmatchedDomainWithoutWildcardUsesExplicitProviderParameter() {
		var svc = serviceWithProviders("keycloak", Map.of(
				"corp", new Provider(List.of("example.com"), false, null)));

		assertEquals("explicit", svc.findProviderIdForRequest("user@other.com", "explicit"));
	}

	@Test
	void findProviderIdForRequest_explicitProviderParameterIsUsedWhenNoUsername() {
		var svc = serviceWithProviders("keycloak", Map.of("keycloak", new Provider(List.of("*"), true, null)));

		assertEquals("explicit", svc.findProviderIdForRequest(null, "explicit"));
	}

	@Test
	void findProviderIdForRequest_fallsBackToDefaultProvider() {
		var svc = serviceWithProviders("keycloak", Map.of("keycloak", new Provider(List.of("*"), true, null)));

		assertEquals("keycloak", svc.findProviderIdForRequest(null, null));
	}

	@Test
	void findProviderIdForRequest_noHintAndNoDefaultThrowsBadRequest() {
		var svc = serviceWithProviders(null, Map.of("corp", new Provider(List.of("example.com"), false, null)));

		assertThrows(BadRequestException.class, () -> svc.findProviderIdForRequest(null, null));
	}

	@Test
	void findProviderForCallback_returnsKeyedProvider() {
		var corp = new Provider(List.of("example.com"), false, null);
		var svc = serviceWithProviders("keycloak",
				Map.of("keycloak", new Provider(List.of("*"), true, null), "corp", corp));

		assertEquals(corp, svc.findProviderForCallback("corp"));
	}

	@Test
	void findProviderForCallback_blankNameResolvesToDefaultProvider() {
		var keycloak = new Provider(List.of("*"), true, null);
		var svc = serviceWithProviders("keycloak", Map.of("keycloak", keycloak));

		assertEquals(keycloak, svc.findProviderForCallback(null));
	}

	@Test
	void findProviderForCallback_unknownProviderReturnsNull() {
		var svc = serviceWithProviders("keycloak", Map.of("keycloak", new Provider(List.of("*"), true, null)));

		assertNull(svc.findProviderForCallback("nope"));
	}

	@Test
	void usernameClaimFor_returnsConfiguredClaim() {
		var svc = serviceWithProviders("keycloak",
				Map.of("keycloak", new Provider(List.of("*"), true, "preferred_username")));

		assertEquals("preferred_username", svc.usernameClaimFor("keycloak"));
	}

	@Test
	void usernameClaimFor_unknownProviderFallsBackToDefaultClaim() {
		var svc = serviceWithProviders("keycloak", Map.of("keycloak", new Provider(List.of("*"), true, null)));

		assertEquals(Provider.DEFAULT_USERNAME_CLAIM, svc.usernameClaimFor("unconfigured"));
	}

	@Test
	void usernameClaimFor_blankProviderIdFallsBackToDefaultClaim() {
		var svc = serviceWithProviders("keycloak", Map.of("keycloak", new Provider(List.of("*"), true, "upn")));

		assertEquals(Provider.DEFAULT_USERNAME_CLAIM, svc.usernameClaimFor(null));
	}
}
