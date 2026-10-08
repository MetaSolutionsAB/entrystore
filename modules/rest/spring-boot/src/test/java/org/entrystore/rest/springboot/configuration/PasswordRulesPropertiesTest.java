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

import org.entrystore.repository.security.Password;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.convert.ConversionFailedException;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Binds through a real context, so the prefix and the component names are part of what is asserted: a
 * mistyped prefix would bind nothing and silently leave the defaults in force.
 */
class PasswordRulesPropertiesTest {

	@AfterEach
	void restoreDefaultRules() {
		Password.setRules(Password.getDefaultRules());
	}

	@Test
	void nothingConfigured_yieldsThe5xDefaults() {
		runner().run(context -> {
			Password.Rules rules = context.getBean(PasswordRulesProperties.class).toRules(List.of());

			assertTrue(rules.isUppercase());
			assertTrue(rules.isLowercase());
			assertTrue(rules.isNumber());
			assertFalse(rules.isSymbol());
			assertEquals(10, rules.getMinLength());
			assertTrue(rules.getCustom().isEmpty());
		});
	}

	@Test
	void everyRuleKey_binds() {
		runner().withPropertyValues(
						"entrystore.auth.password.rule.uppercase=false",
						"entrystore.auth.password.rule.lowercase=false",
						"entrystore.auth.password.rule.number=false",
						"entrystore.auth.password.rule.symbol=true",
						"entrystore.auth.password.rule.min-length=14",
						"entrystore.auth.password.rule.custom.1=^\\S+$",
						"entrystore.auth.password.rule.custom.2=[xyz]")
				.run(context -> {
					Password.Rules rules = context.getBean(PasswordRulesProperties.class).toRules(
							IndexedListSettings.read(context.getEnvironment(), "entrystore.auth.password.rule.custom"));

					assertFalse(rules.isUppercase());
					assertFalse(rules.isLowercase());
					assertFalse(rules.isNumber());
					assertTrue(rules.isSymbol());
					assertEquals(14, rules.getMinLength());
					assertEquals(Set.of("^\\S+$", "[xyz]"), rules.getCustom());
				});
	}

	@ParameterizedTest(name = "symbol={0} -> {1}")
	@CsvSource({"yes, true", "on, true", "1, true", "TRUE, true", "no, false", "off, false", "0, false"})
	void booleanRules_acceptTheRelaxedSpellings(String value, boolean expected) {
		runner().withPropertyValues("entrystore.auth.password.rule.symbol=" + value)
				.run(context -> assertEquals(expected,
						context.getBean(PasswordRulesProperties.class).toRules(List.of()).isSymbol()));
	}

	@Test
	void unrecognisedBooleanValue_failsStartupNamingTheKey() {
		runner().withPropertyValues("entrystore.auth.password.rule.number=maybe")
				.run(context -> {
					BindException bindFailure = causeOfType(context.getStartupFailure(), BindException.class);
					assertEquals("entrystore.auth.password.rule.number", bindFailure.getName().toString());
					assertNotNull(causeOfType(bindFailure, ConversionFailedException.class));
				});
	}

	@Test
	void invalidCustomRegex_failsStartupNamingTheKeyAndIndex() {
		runner().withUserConfiguration(PasswordRulesInitializer.class)
				.withPropertyValues(
						"entrystore.auth.password.rule.custom.1=[0-9]",
						"entrystore.auth.password.rule.custom.2=[unclosed")
				.run(context -> {
					IllegalArgumentException invalid =
							causeOfType(context.getStartupFailure(), IllegalArgumentException.class);
					assertTrue(invalid.getMessage().contains("entrystore.auth.password.rule.custom.2"),
							"got: " + invalid.getMessage());
					assertNull(invalid.getCause(), "the key must be in the root cause, which the startup report prints");
				});
	}

	@Test
	void initializer_appliesTheConfiguredRulesToPassword() {
		runner().withUserConfiguration(PasswordRulesInitializer.class)
				.withPropertyValues(
						"entrystore.auth.password.rule.uppercase=off",
						"entrystore.auth.password.rule.custom.1=^\\S+$")
				.run(context -> {
					assertTrue(Password.conformsToRules("lowercase123"), "uppercase is no longer required");
					assertFalse(Password.conformsToRules("Lower case123"), "the custom rule rejects whitespace");
					assertFalse(Password.conformsToRules("Lowercase"), "unconfigured rules keep their defaults");
				});
	}

	@Test
	void bareCustomRule_isAppliedAsOneRule() {
		// 5.x read a bare value as a one-element list.
		runner().withUserConfiguration(PasswordRulesInitializer.class)
				.withPropertyValues("entrystore.auth.password.rule.custom=^\\S+$")
				.run(context -> {
					assertTrue(Password.conformsToRules("Lowercase123"));
					assertFalse(Password.conformsToRules("Lower case123"), "the bare custom rule rejects whitespace");
				});
	}

	private static <T extends Throwable> T causeOfType(Throwable failure, Class<T> type) {
		for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
			if (type.isInstance(cause)) {
				return type.cast(cause);
			}
		}
		throw new AssertionError("no " + type.getSimpleName() + " in the cause chain of " + failure);
	}

	private static ApplicationContextRunner runner() {
		return new ApplicationContextRunner().withUserConfiguration(EnablePasswordRulesProperties.class);
	}

	@EnableConfigurationProperties(PasswordRulesProperties.class)
	static class EnablePasswordRulesProperties {
	}
}
