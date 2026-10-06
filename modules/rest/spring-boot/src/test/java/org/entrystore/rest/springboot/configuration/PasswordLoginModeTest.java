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
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.support.PropertySourcesPlaceholderConfigurer;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;

class PasswordLoginModeTest {

	@ParameterizedTest(name = "''{0}'' is {1}")
	@CsvSource({
			"on, ON", "true, ON", "yes, ON", "1, ON", "On, ON", "'', ON",
			"off, OFF", "false, OFF", "no, OFF", "0, OFF", "OFF, OFF", "' off ', OFF",
			"whitelist, WHITELIST", "Whitelist, WHITELIST", "' whitelist ', WHITELIST"
	})
	void parse_acceptsWhitelistAndRelaxedBooleans(String value, PasswordLoginMode expected) {
		assertEquals(Optional.of(expected), PasswordLoginMode.parse(value));
	}

	@Test
	void parse_null_isOn() {
		assertEquals(Optional.of(PasswordLoginMode.ON), PasswordLoginMode.parse(null));
	}

	@Test
	void parse_unrecognisedValue_isEmpty() {
		assertEquals(Optional.empty(), PasswordLoginMode.parse("disabled"));
	}

	@Test
	void bean_unsetKey_isOn() {
		runner().run(context -> assertEquals(PasswordLoginMode.ON, context.getBean(PasswordLoginMode.class)));
	}

	@Test
	void bean_bindsTheConfiguredKey() {
		runner().withPropertyValues("entrystore.auth.password=off", "entrystore.auth.password.whitelist.1=admin")
				.run(context -> assertEquals(PasswordLoginMode.OFF, context.getBean(PasswordLoginMode.class)));
	}

	@Test
	void bean_unrecognisedValue_failsStartupNamingTheKey() {
		runner().withPropertyValues("entrystore.auth.password=disabled")
				.run(context -> assertThat(context).getFailure()
						.rootCause()
						.hasMessageContaining("entrystore.auth.password")
						.hasMessageContaining("whitelist"));
	}

	@Test
	void bean_blankValue_isOn() {
		runner().withPropertyValues("entrystore.auth.password=")
				.run(context -> assertEquals(PasswordLoginMode.ON, context.getBean(PasswordLoginMode.class)));
	}

	private static ApplicationContextRunner runner() {
		return new ApplicationContextRunner()
				.withBean(PropertySourcesPlaceholderConfigurer.class)
				.withUserConfiguration(PasswordLoginConfiguration.class);
	}
}
