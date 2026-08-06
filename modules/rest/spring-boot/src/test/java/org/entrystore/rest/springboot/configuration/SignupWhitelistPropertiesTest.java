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
 * Binds through a real context rather than constructing the record, so the prefix and the component name
 * are part of what is asserted. {@code AuthServiceTest} builds the record directly and cannot catch a
 * mistyped prefix, whose effect is a whitelist that binds empty — which for this key means every domain
 * is allowed to sign up.
 */
class SignupWhitelistPropertiesTest {

	@Test
	void indexedEntries_bind() {
		runner().withPropertyValues(
						"entrystore.auth.signup=on",
						"entrystore.auth.signup.whitelist.1=example.com",
						"entrystore.auth.signup.whitelist.2=ext.example.com")
				.run(context -> assertEquals(Set.of("example.com", "ext.example.com"),
						Set.copyOf(context.getBean(SignupWhitelistProperties.class).whitelist().values())));
	}

	@Test
	void scalarAtTheRecordsOwnPrefixWithNoWhitelist_stillProducesTheBean() {
		// entrystore.auth.signup=on with no whitelist is open signup — the ordinary production config, and
		// the one shape entrystore-it.properties cannot exercise because it always sets whitelist.1. The
		// scalar sits at the record's own prefix, which reaches the binder differently from an absent
		// prefix; if this regresses the context fails to start rather than binding an empty whitelist.
		runner().withPropertyValues("entrystore.auth.signup=on")
				.run(context -> assertTrue(
						context.getBean(SignupWhitelistProperties.class).whitelist().isEmpty()));
	}

	@Test
	void noSignupKeysAtAll_bindsAnEmptyWhitelist() {
		runner().run(context -> assertTrue(
				context.getBean(SignupWhitelistProperties.class).whitelist().isEmpty()));
	}

	private static ApplicationContextRunner runner() {
		return new ApplicationContextRunner().withUserConfiguration(EnableSignupWhitelistProperties.class);
	}

	@EnableConfigurationProperties(SignupWhitelistProperties.class)
	static class EnableSignupWhitelistProperties {
	}
}
