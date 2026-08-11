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
 * Binds through a real context rather than constructing the record, so the {@code @ConfigurationProperties}
 * prefix and the component names are part of what is asserted. Constructing the record directly would keep
 * passing with a mistyped prefix, and the effect of that is an empty login whitelist or blacklist.
 */
class PasswordLoginListPropertiesTest {

	@Test
	void indexedEntries_bindToBothLists() {
		runner().withPropertyValues(
						"entrystore.auth.password=whitelist",
						"entrystore.auth.password.whitelist.1=admin",
						"entrystore.auth.password.whitelist.2=user@test.com",
						"entrystore.auth.password.blacklist.1=blocked@test.com")
				.run(context -> {
					PasswordLoginListProperties properties = context.getBean(PasswordLoginListProperties.class);

					// Compared as sets: Map.copyOf randomises iteration order per JVM and both consumers
					// match by membership, so asserting a sequence here would be flaky over-specification.
					assertEquals(Set.of("admin", "user@test.com"), Set.copyOf(properties.whitelist().values()));
					assertEquals(Set.of("blocked@test.com"), Set.copyOf(properties.blacklist().values()));
				});
	}

	@Test
	void scalarAtTheRecordsOwnPrefixWithNoLists_stillProducesTheBean() {
		// entrystore.auth.password=off with neither list configured is an ordinary production config, and
		// it is the one shape that reaches the binder differently from an absent prefix: a scalar sits at
		// the record's own prefix and cannot convert to the record. If this regresses, the context fails to
		// start rather than binding two empty maps.
		runner().withPropertyValues("entrystore.auth.password=off")
				.run(context -> {
					PasswordLoginListProperties properties = context.getBean(PasswordLoginListProperties.class);

					assertTrue(properties.whitelist().isEmpty());
					assertTrue(properties.blacklist().isEmpty());
				});
	}

	@Test
	void noPasswordKeysAtAll_bindsEmptyLists() {
		runner().run(context -> {
			PasswordLoginListProperties properties = context.getBean(PasswordLoginListProperties.class);

			assertTrue(properties.whitelist().isEmpty());
			assertTrue(properties.blacklist().isEmpty());
		});
	}

	@Test
	void siblingKeysUnderTheSamePrefix_areIgnoredRatherThanFailingTheBind() {
		// .require-current-password shares the prefix but is read elsewhere via @Value.
		runner().withPropertyValues(
						"entrystore.auth.password.require-current-password=false",
						"entrystore.auth.password.whitelist.1=admin")
				.run(context -> assertEquals(Set.of("admin"),
						Set.copyOf(context.getBean(PasswordLoginListProperties.class).whitelist().values())));
	}

	@Test
	void indexGap_keepsThePostGapEntry() {
		// Divergence from the legacy reader, which stopped counting at the first missing index.
		// IndexedListConfigValidator reports this shape at startup.
		runner().withPropertyValues(
						"entrystore.auth.password.whitelist.1=admin",
						"entrystore.auth.password.whitelist.3=contractor")
				.run(context -> assertEquals(2,
						context.getBean(PasswordLoginListProperties.class).whitelist().size()));
	}

	private static ApplicationContextRunner runner() {
		return new ApplicationContextRunner().withUserConfiguration(EnablePasswordLoginListProperties.class);
	}

	@EnableConfigurationProperties(PasswordLoginListProperties.class)
	static class EnablePasswordLoginListProperties {
	}
}
