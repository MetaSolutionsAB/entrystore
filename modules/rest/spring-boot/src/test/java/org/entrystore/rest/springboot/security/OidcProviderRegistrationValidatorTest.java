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

import org.entrystore.rest.springboot.configuration.OidcCustomConfiguration;
import org.entrystore.rest.springboot.configuration.OidcCustomConfiguration.Provider;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OidcProviderRegistrationValidatorTest {

	private static ClientRegistration registration(String id) {
		return ClientRegistration.withRegistrationId(id)
				.clientId("entrystore")
				.clientSecret("secret")
				.authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
				.redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
				.authorizationUri("https://idp.example.com/authorize")
				.tokenUri("https://idp.example.com/token")
				.scope("openid", "email")
				.build();
	}

	private static OidcCustomConfiguration config(String defaultProvider, Map<String, Provider> providers) {
		return new OidcCustomConfiguration(true, defaultProvider, List.of(), providers, null, null);
	}

	private static OidcProviderRegistrationValidator validator(OidcCustomConfiguration config,
															   String... registrationIds) {
		Optional<ClientRegistrationRepository> repository = registrationIds.length == 0
				? Optional.empty()
				: Optional.of(new InMemoryClientRegistrationRepository(
						Arrays.stream(registrationIds)
								.map(OidcProviderRegistrationValidatorTest::registration)
								.toList()));
		return new OidcProviderRegistrationValidator(config, repository);
	}

	@Test
	void matchingDefaultProviderAndProviderKeysPass() {
		var validator = validator(config("keycloak", Map.of(
				"keycloak", new Provider(List.of("*"), true, null),
				"corp", new Provider(List.of("example.com"), false, null))), "keycloak", "corp");

		assertDoesNotThrow(validator::afterPropertiesSet);
	}

	@Test
	void defaultProviderTypoFailsStartupNamingTheKey() {
		var validator = validator(config("keycloack", Map.of()), "keycloak");

		var ex = assertThrows(IllegalStateException.class, validator::afterPropertiesSet);
		assertTrue(ex.getMessage().contains("entrystore.auth.oidc.default-provider=keycloack"),
				() -> "message should name the offending key, was: " + ex.getMessage());
	}

	@Test
	void providerEntryWithoutRegistrationFailsStartupNamingTheKey() {
		var validator = validator(config("keycloak", Map.of(
				"keycloak", new Provider(List.of("*"), true, null),
				"corq", new Provider(List.of("example.com"), false, null))), "keycloak", "corp");

		var ex = assertThrows(IllegalStateException.class, validator::afterPropertiesSet);
		assertTrue(ex.getMessage().contains("entrystore.auth.oidc.provider.corq.*"),
				() -> "message should name the offending key, was: " + ex.getMessage());
	}

	// OIDC enabled with no client registrations at all: any configured provider reference is broken.
	@Test
	void missingRegistrationRepositoryFailsWhenDefaultProviderIsConfigured() {
		var validator = validator(config("keycloak", Map.of()));

		assertThrows(IllegalStateException.class, validator::afterPropertiesSet);
	}

	// No default-provider and no provider.{id} entries: nothing to validate, startup proceeds (the
	// controller then answers hint-less logins with a 400, which is request-shaped, not config-shaped).
	@Test
	void absentConfigReferencesPassWithoutRegistrations() {
		var validator = validator(config(null, Map.of()));

		assertDoesNotThrow(validator::afterPropertiesSet);
	}
}
