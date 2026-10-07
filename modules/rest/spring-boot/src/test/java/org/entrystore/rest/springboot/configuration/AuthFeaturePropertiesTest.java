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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Binds through a real context, so the prefix and component names are part of what is asserted: a mistyped
 * name would silently bind the default and leave the feature off.
 */
class AuthFeaturePropertiesTest {

	@Test
	void noKeys_bothFeaturesOff() {
		runner().run(context -> {
			var properties = context.getBean(AuthFeatureProperties.class);
			assertFalse(properties.signup());
			assertFalse(properties.passwordReset());
		});
	}

	@ParameterizedTest(name = "{0} enables: {1}")
	@CsvSource({
			"on, true", "true, true", "yes, true", "1, true", "ON, true",
			"off, false", "false, false", "no, false", "0, false", "'', false"
	})
	void relaxedBooleans_bindForBothKeys(String value, boolean expected) {
		runner().withPropertyValues("entrystore.auth.signup=" + value, "entrystore.auth.password-reset=" + value)
				.run(context -> {
					var properties = context.getBean(AuthFeatureProperties.class);
					assertEquals(expected, properties.signup());
					assertEquals(expected, properties.passwordReset());
				});
	}

	@Test
	void keysAreIndependent() {
		runner().withPropertyValues("entrystore.auth.signup=on", "entrystore.auth.password-reset=off")
				.run(context -> {
					var properties = context.getBean(AuthFeatureProperties.class);
					assertTrue(properties.signup());
					assertFalse(properties.passwordReset());
				});
	}

	@Test
	void subKeysWithoutTheSwitch_leaveTheFeatureOff() {
		runner().withPropertyValues(
						"entrystore.auth.signup.whitelist.1=example.com",
						"entrystore.auth.password-reset.email.subject=Reset")
				.run(context -> {
					var properties = context.getBean(AuthFeatureProperties.class);
					assertFalse(properties.signup());
					assertFalse(properties.passwordReset());
				});
	}

	@Test
	void switchWithSubKeys_bindsTheSwitch() {
		runner().withPropertyValues("entrystore.auth.signup=on", "entrystore.auth.signup.whitelist.1=example.com")
				.run(context -> assertTrue(context.getBean(AuthFeatureProperties.class).signup()));
	}

	@Test
	void unrecognisedValue_failsStartup() {
		runner().withPropertyValues("entrystore.auth.password-reset=enabled")
				.run(context -> assertThat(context).hasFailed());
	}

	private static ApplicationContextRunner runner() {
		return new ApplicationContextRunner().withUserConfiguration(EnableAuthFeatureProperties.class);
	}

	@EnableConfigurationProperties(AuthFeatureProperties.class)
	static class EnableAuthFeatureProperties {
	}
}
