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

package org.entrystore.repository.config;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.config.Property;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class PropertiesConfigurationTest {

	@ParameterizedTest(name = "{0} resolves to {1}")
	@CsvSource({
			"true, true", "on, true", "yes, true", "1, true",
			"false, false", "off, false", "no, false", "0, false",
			"TrUe, true", "ON, true", "YeS, true",
			"FaLsE, false", "OFF, false", "No, false",
			"'  yes  ', true", "'  no  ', false"
	})
	void getBoolean_acceptsRelaxedSpellings(String value, boolean expected) {
		var configuration = new PropertiesConfiguration("test");
		configuration.setProperty("feature", value);

		assertEquals(expected, configuration.getBoolean("feature"));
		assertEquals(expected, configuration.getBoolean("feature", !expected));
	}

	@Test
	void getBoolean_missingValueUsesTheCallersDefault() {
		var configuration = new PropertiesConfiguration("test");

		assertFalse(configuration.getBoolean("feature"));
		assertFalse(configuration.getBoolean("feature", false));
		assertTrue(configuration.getBoolean("feature", true));
	}

	@ParameterizedTest(name = "unrecognised value ''{0}'' uses the caller's default")
	@ValueSource(strings = {"enabled", "disabled", "typo"})
	void getBoolean_unrecognisedValueUsesTheCallersDefault(String value) {
		var configuration = new PropertiesConfiguration("test");
		configuration.setProperty("feature", value);

		assertFalse(configuration.getBoolean("feature"));
		assertTrue(configuration.getBoolean("feature", true));
	}

	@ParameterizedTest(name = "blank value ''{0}'' uses the caller's default")
	@ValueSource(strings = {"", "  "})
	void getBoolean_blankValueUsesTheCallersDefault(String value) {
		var configuration = new PropertiesConfiguration("test");
		configuration.setProperty("feature", value);

		assertFalse(configuration.getBoolean("feature", false));
		assertTrue(configuration.getBoolean("feature", true));
	}

	@Test
	public void constructor_ok() {
		PropertiesConfiguration configuration = new PropertiesConfiguration("test");
		assertTrue(configuration.isEmpty());
		assertFalse(configuration.isModified());
	}

	@Test
	void getBoolean_warnsOnlyForUnrecognisedValues() {
		var events = new ArrayList<LogEvent>();
		var appender = new AbstractAppender("booleanConfigTest", null, null, true, Property.EMPTY_ARRAY) {
			@Override
			public void append(LogEvent event) {
				events.add(event.toImmutable());
			}
		};
		var context = (LoggerContext) LogManager.getContext(false);
		var logging = context.getConfiguration();
		String loggerName = PropertiesConfiguration.class.getName();
		var previous = logging.getLoggers().get(loggerName);
		var logger = new LoggerConfig(loggerName, Level.WARN, false);
		appender.start();
		logger.addAppender(appender, Level.WARN, null);
		logging.removeLogger(loggerName);
		logging.addLogger(loggerName, logger);
		context.updateLoggers();
		try {
			var configuration = new PropertiesConfiguration("test");
			configuration.setProperty("entrystore.feature", "enabled");
			configuration.setProperty("entrystore.blank", "");
			configuration.setProperty("entrystore.on", "on");

			assertTrue(configuration.getBoolean("entrystore.absent", true));
			assertTrue(configuration.getBoolean("entrystore.blank", true));
			assertTrue(configuration.getBoolean("entrystore.on", false));
			assertTrue(configuration.getBoolean("entrystore.feature", true));
			assertEquals(1, events.size());
			assertEquals(Level.WARN, events.getFirst().getLevel());
			String message = events.getFirst().getMessage().getFormattedMessage();
			assertTrue(message.contains("entrystore.feature"));
			assertTrue(message.contains("enabled"));
		} finally {
			logging.removeLogger(loggerName);
			if (previous != null) {
				logging.addLogger(loggerName, previous);
			}
			context.updateLoggers();
			appender.stop();
		}
	}

	@Test
	public void clear_ok() {
		PropertiesConfiguration configuration = new PropertiesConfiguration("test");
		configuration.addProperty(Settings.STORE_PATH, "file:///dummy.dat");
		assertFalse(configuration.isEmpty());
		configuration.clear();
		assertTrue(configuration.isModified());
	}

	@Test
	public void load_ok() {
		PropertiesConfiguration configuration = new PropertiesConfiguration("test");
		try {
			File properties = new File("src/test/resources/entrystore.properties-test");
			configuration.load(URI.create("file:///" + properties.getAbsolutePath().replace('\\', '/')).toURL());
		} catch (IOException e) {
			e.printStackTrace();
		}
		assertFalse(configuration.isEmpty());
	}
}
