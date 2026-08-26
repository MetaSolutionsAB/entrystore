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

import org.entrystore.rest.springboot.configuration.OidcCustomConfiguration.Provider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OidcCustomConfigurationTest {

	@Test
	void redirectDefaultsAreApplied() {
		var config = new OidcCustomConfiguration(false, null, null, null, null, null);
		assertEquals("/auth/user", config.redirectSuccess().url());
		assertEquals("/auth/user", config.redirectFailure().url());
	}

	@Test
	void nullCollectionsBecomeEmptyImmutableOnes() {
		var config = new OidcCustomConfiguration(false, null, null, null, null, null);
		assertTrue(config.redirectDomainWhitelist().isEmpty());
		assertTrue(config.provider().isEmpty());
	}

	@Test
	void boundCollectionsAreDefensivelyCopied() {
		var whitelist = new ArrayList<>(List.of("app.example.com"));
		var providers = new HashMap<String, Provider>();
		providers.put("keycloak", new Provider(null, true, null));

		var config = new OidcCustomConfiguration(true, "keycloak", whitelist, providers, null, null);
		whitelist.add("evil.com");
		providers.clear();

		assertEquals(List.of("app.example.com"), config.redirectDomainWhitelist());
		assertEquals(1, config.provider().size());
		assertThrows(UnsupportedOperationException.class, () -> config.redirectDomainWhitelist().add("evil.com"));
	}

	@Test
	void providerDomainsDefaultToWildcard() {
		var provider = new Provider(null, false, null);
		assertEquals(List.of("*"), provider.domains());
	}

	@Test
	void providerUsernameClaimDefaultsToEmail() {
		var provider = new Provider(null, false, null);
		assertEquals("email", provider.usernameClaim());
		assertEquals(Provider.DEFAULT_USERNAME_CLAIM, provider.usernameClaim());
	}

	@Test
	void blankUsernameClaimFallsBackToDefault() {
		var provider = new Provider(null, false, "  ");
		assertEquals(Provider.DEFAULT_USERNAME_CLAIM, provider.usernameClaim());
	}

	@Test
	void explicitUsernameClaimIsKept() {
		var provider = new Provider(null, false, "preferred_username");
		assertEquals("preferred_username", provider.usernameClaim());
	}

	@Test
	void providerDomainsAreDefensivelyCopied() {
		var domains = new ArrayList<>(List.of("example.com"));
		var provider = new Provider(domains, false, null);
		domains.add("evil.com");
		assertEquals(List.of("example.com"), provider.domains());
	}

	// Hostnames and email domains are case-insensitive; the routing/whitelist lookups compare
	// against lowercased request-side values, so uppercase config entries must be normalized at
	// binding time or they would silently never match.
	@Test
	void providerDomainsAreLowercasedAtBinding() {
		var provider = new Provider(List.of("EXAMPLE.com", "*"), false, null);
		assertEquals(List.of("example.com", "*"), provider.domains());
	}

	@Test
	void redirectDomainWhitelistIsLowercasedAtBinding() {
		var config = new OidcCustomConfiguration(true, null, List.of("APP.Example.com"), null, null, null);
		assertEquals(List.of("app.example.com"), config.redirectDomainWhitelist());
	}

	// Entries must round-trip through URI.getHost() unchanged — anything else can never match the
	// lookup and would silently drop every custom redirect URL.
	@ParameterizedTest(name = "\"{0}\"")
	@ValueSource(strings = {"https://app.example.com", "app.example.com:8443", "app.example.com/",
			"user@app.example.com", "*.app.example.com", "*", " app.example.com",
			"app.example.com portal.example.com", "internal_app.example.com", "[foo]"})
	void nonBareHostnameWhitelistEntryFailsFast(String entry) {
		assertThrows(IllegalArgumentException.class,
				() -> new OidcCustomConfiguration(true, null, List.of(entry), null, null, null));
	}

	// URI.getHost() returns the bracketed form for IPv6 literals, so that exact form must be
	// whitelistable — its colons are not a port separator.
	@Test
	void bracketedIpv6WhitelistEntryIsAccepted() {
		var config = new OidcCustomConfiguration(true, null, List.of("[::1]"), null, null, null);
		assertEquals(List.of("[::1]"), config.redirectDomainWhitelist());
	}

	// A trailing or doubled comma binds as a blank list element; that formatting artifact must not
	// abort startup (this record binds even on deployments with OIDC disabled) — filter, not reject.
	@Test
	void blankWhitelistEntriesAreFilteredOut() {
		var config = new OidcCustomConfiguration(true, null, List.of("app.example.com", "", "  "), null, null, null);
		assertEquals(List.of("app.example.com"), config.redirectDomainWhitelist());
	}

	// A whitespace-only default-provider (YAML `default-provider: " "`, an escaped trailing space)
	// must normalize to "not configured": the startup validator's isNotBlank would skip it while
	// OidcAuthService's isEmpty would treat it as a configured id and fail per-request — trimming
	// here makes both see the same "not configured" state.
	@Test
	void blankDefaultProviderNormalizesToNull() {
		var config = new OidcCustomConfiguration(true, "  ", null, null, null, null);
		assertNull(config.defaultProvider());
	}

	// A blank binding (`entrystore.auth.oidc.redirect-success.url=`) bypasses the null-only
	// defaulting; downstream the redirect handlers reject it at bean creation, but only when OIDC
	// is enabled and without naming the key — so it must fail at binding.
	@Test
	void blankRedirectUrlFailsFast() {
		assertThrows(IllegalArgumentException.class, () -> new OidcCustomConfiguration.RedirectUrl("  "));
		assertThrows(IllegalArgumentException.class, () -> new OidcCustomConfiguration.RedirectUrl(null));
	}

	// A syntactically invalid configured URL must fail at startup, not at the first post-login
	// redirect.
	@Test
	void malformedRedirectUrlFailsFast() {
		assertThrows(IllegalArgumentException.class,
				() -> new OidcCustomConfiguration.RedirectUrl("http://example.com/a b"));
	}

	// The default Map.of()/Map.copyOf() results must be usable with the routing lookups in
	// OidcAuthService, which call get() with arbitrary provider ids.
	@Test
	void unknownProviderLookupReturnsNullInsteadOfThrowing() {
		var config = new OidcCustomConfiguration(true, null, null, Map.of(), null, null);
		assertEquals(null, config.provider().get("unknown"));
	}
}
