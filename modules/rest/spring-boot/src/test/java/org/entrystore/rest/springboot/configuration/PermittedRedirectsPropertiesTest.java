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
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the pairing of {@code prefix = "entrystore.auth.permitted"} with the {@code redirects} component.
 * {@code RedirectUrlValidatorTest} builds the record directly and would keep passing with a mistyped
 * prefix; the effect of that is an empty allowlist, so every operator-configured post-login redirect
 * target stops working while the repository base URL — which {@code RedirectUrlValidator} derives from
 * {@code RepositoryManager}, not from this record — keeps working and masks it.
 */
class PermittedRedirectsPropertiesTest {

	@Test
	void everyIndexedEntry_binds() {
		// Compared as a set: Map.copyOf randomises iteration order per JVM, and RedirectUrlValidator
		// returns on the first matching base URL regardless of position, so order is not a contract here.
		runner().withPropertyValues(
						"entrystore.auth.permitted.redirects.1=https://app.example.com/",
						"entrystore.auth.permitted.redirects.2=https://admin.example.com/")
				.run(context -> assertEquals(
						Set.of("https://app.example.com/", "https://admin.example.com/"),
						Set.copyOf(context.getBean(PermittedRedirectsProperties.class).redirects().values())));
	}

	@Test
	void noRedirectKeys_bindAnEmptyAllowlist() {
		runner().run(context -> assertTrue(
				context.getBean(PermittedRedirectsProperties.class).redirects().isEmpty()));
	}

	private static ApplicationContextRunner runner() {
		return new ApplicationContextRunner().withUserConfiguration(EnablePermittedRedirectsProperties.class);
	}

	@EnableConfigurationProperties(PermittedRedirectsProperties.class)
	static class EnablePermittedRedirectsProperties {
	}
}
