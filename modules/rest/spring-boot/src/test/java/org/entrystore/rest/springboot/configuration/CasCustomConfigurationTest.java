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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CasCustomConfigurationTest {

	@Test
	void disabledConfigurationAllowsMissingServerUrl() {
		var config = new CasCustomConfiguration(false, "cas2", null, false, null, null);
		assertNotNull(config.server());
		assertNull(config.server().url());
	}

	@Test
	void enabledWithMissingServerUrlFailsFast() {
		var ex = assertThrows(IllegalArgumentException.class,
				() -> new CasCustomConfiguration(true, "cas2", null, false, null, null));
		assertTrue(ex.getMessage().contains("entrystore.auth.cas.server.url"));
	}

	@Test
	void enabledWithBlankServerUrlFailsFast() {
		var server = new CasCustomConfiguration.Server("  ", null);
		assertThrows(IllegalArgumentException.class,
				() -> new CasCustomConfiguration(true, "cas2", server, false, null, null));
	}

	@Test
	void enabledWithValidServerUrlSucceeds() {
		var server = new CasCustomConfiguration.Server("https://cas.example.org/cas", null);
		var config = new CasCustomConfiguration(true, "cas2", server, false, null, null);
		assertEquals("https://cas.example.org/cas", config.server().url());
	}

	@Test
	void redirectDefaultsAreApplied() {
		var server = new CasCustomConfiguration.Server("https://cas.example.org/cas", null);
		var config = new CasCustomConfiguration(true, "cas2", server, false, null, null);
		assertEquals("/auth/user", config.redirectSuccess().url());
		assertEquals("/auth/user", config.redirectFailure().url());
	}

	@Test
	void resolvedLoginUrlDerivesFromServerUrl() {
		var server = new CasCustomConfiguration.Server("https://cas.example.org/cas", null);
		assertEquals("https://cas.example.org/cas/login", server.resolvedLoginUrl());
	}

	@Test
	void resolvedLoginUrlHandlesTrailingSlash() {
		var server = new CasCustomConfiguration.Server("https://cas.example.org/cas/", null);
		assertEquals("https://cas.example.org/cas/login", server.resolvedLoginUrl());
	}

	@Test
	void resolvedLoginUrlPrefersExplicitUrlLogin() {
		var server = new CasCustomConfiguration.Server(
				"https://cas.example.org/cas",
				"https://sso.example.org/custom-login");
		assertEquals("https://sso.example.org/custom-login", server.resolvedLoginUrl());
	}

	@Test
	void resolvedLoginUrlReturnsNullWhenBothUnset() {
		var server = new CasCustomConfiguration.Server(null, null);
		assertNull(server.resolvedLoginUrl());
	}
}
