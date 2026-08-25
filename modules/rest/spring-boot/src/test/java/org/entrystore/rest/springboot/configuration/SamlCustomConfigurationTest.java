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

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SamlCustomConfigurationTest {

	@Test
	void metadataMaxAge_defaultsToSevenDays_whenNotConfigured() {
		var config = bind(Map.of(
				"entrystore.auth.saml.enabled", "true",
				"entrystore.auth.saml.idp.acme.user-auto-provisioning", "true"));

		assertEquals(604800L, config.idp().get("acme").metadata().maxAge());
	}

	@Test
	void metadataMaxAge_bindsConfiguredValue() {
		var config = bind(Map.of(
				"entrystore.auth.saml.enabled", "true",
				"entrystore.auth.saml.idp.acme.metadata.max-age", "120"));

		assertEquals(120L, config.idp().get("acme").metadata().maxAge());
	}

	@Test
	void metadataMaxAge_belowMinimum_isRejected() {
		// zero, negative, and a positive-but-too-small value all violate the 60s floor.
		assertRejected("0");
		assertRejected("-1");
		assertRejected("30");
	}

	// Config-side case variance: domains must be lowercased at binding, because the routing lookup
	// (SamlAuthService.findIdpIdForDomain) compares against a case-folded request domain — an
	// uppercase entry would otherwise silently misroute to the wildcard IdP (mirrors
	// OidcCustomConfiguration.Provider).
	@Test
	void idpDomains_areLowercasedAtBinding() {
		var config = bind(Map.of(
				"entrystore.auth.saml.enabled", "true",
				"entrystore.auth.saml.idp.acme.domains", "EXAMPLE.com,*"));

		assertEquals(List.of("example.com", "*"), config.idp().get("acme").domains());
	}

	private static void assertRejected(String maxAge) {
		var ex = assertThrows(BindException.class, () -> bind(Map.of(
				"entrystore.auth.saml.enabled", "true",
				"entrystore.auth.saml.idp.acme.metadata.max-age", maxAge)));

		assertTrue(rootCauseMessage(ex).contains("at least"),
				"max-age=" + maxAge + " should be rejected with the floor message, got: " + rootCauseMessage(ex));
	}

	private static SamlCustomConfiguration bind(Map<String, String> properties) {
		var source = new MapConfigurationPropertySource(properties);
		return new Binder(source).bind("entrystore.auth.saml", SamlCustomConfiguration.class).get();
	}

	private static String rootCauseMessage(Throwable throwable) {
		var cause = throwable;
		while (cause.getCause() != null) {
			cause = cause.getCause();
		}
		return String.valueOf(cause.getMessage());
	}
}
