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

import org.apache.commons.lang3.exception.ExceptionUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BooleanConfigConditionTest {

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
			.withUserConfiguration(ClassSetting.class, MethodSetting.class, RequestResponseLoggingFilter.class);

	@ParameterizedTest(name = "{0} activates beans: {1}")
	@CsvSource({
			"true, true", "on, true", "yes, true", "1, true",
			"false, false", "off, false", "no, false", "0, false",
			"TrUe, true", "ON, true", "YeS, true",
			"FaLsE, false", "OFF, false", "No, false",
			"'  yes  ', true", "'  no  ', false"
	})
	void relaxedSpellingsGateBothClassesAndMethods(String value, boolean expected) {
		// Raw property source: withPropertyValues trims, which would hide the whitespace rows.
		runner.withInitializer(rawProperties(Map.of("entrystore.feature", value, "logging.http.enabled", value)))
				.run(context -> {
					assertNull(context.getStartupFailure());
					assertEquals(expected, context.containsBean("classEnabled"));
					assertEquals(expected, context.containsBean("methodEnabled"));
					assertEquals(expected, context.containsBean("requestResponseLoggingFilter"));
				});
	}

	@Test
	void absentSettingsPreserveEachDefault() {
		runner.run(context -> {
			assertNull(context.getStartupFailure());
			assertFalse(context.containsBean("classEnabled"));
			assertFalse(context.containsBean("methodEnabled"));
			assertTrue(context.containsBean("requestResponseLoggingFilter"));
		});
	}

	@Test
	void blankSettingsPreserveEachDefault() {
		runner.withPropertyValues("entrystore.feature=", "logging.http.enabled=")
				.run(context -> {
					assertNull(context.getStartupFailure());
					assertFalse(context.containsBean("classEnabled"));
					assertFalse(context.containsBean("methodEnabled"));
					assertTrue(context.containsBean("requestResponseLoggingFilter"));
				});
	}

	@Test
	void propertyPlaceholdersResolveBeforeBooleanConversion() {
		runner.withPropertyValues("feature-value=on", "entrystore.feature=${feature-value}")
				.run(context -> {
					assertNull(context.getStartupFailure());
					assertTrue(context.containsBean("classEnabled"));
					assertTrue(context.containsBean("methodEnabled"));
				});
	}

	@ParameterizedTest(name = "invalid value {0} fails startup")
	@ValueSource(strings = {"enabled", "disabled", "typo", "2"})
	void invalidValuesFailStartup(String value) {
		runner.withPropertyValues("entrystore.feature=" + value)
				.run(context -> {
					assertNotNull(context.getStartupFailure());
					assertTrue(ExceptionUtils.getThrowableList(context.getStartupFailure()).stream()
							.map(Throwable::getMessage)
							.anyMatch(message -> message != null && message.contains("entrystore.feature")));
				});
	}

	private static ApplicationContextInitializer<ConfigurableApplicationContext> rawProperties(
			Map<String, Object> properties) {
		return context -> context.getEnvironment().getPropertySources()
				.addFirst(new MapPropertySource("raw", properties));
	}

	@Configuration(proxyBeanMethods = false)
	@ConditionalOnBooleanConfig("entrystore.feature")
	static class ClassSetting {

		@Bean
		String classEnabled() {
			return "active";
		}
	}

	@Configuration(proxyBeanMethods = false)
	static class MethodSetting {

		@Bean
		@ConditionalOnBooleanConfig("entrystore.feature")
		String methodEnabled() {
			return "active";
		}
	}
}
