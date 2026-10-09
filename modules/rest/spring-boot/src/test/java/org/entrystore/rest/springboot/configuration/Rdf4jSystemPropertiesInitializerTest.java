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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.assertEquals;

class Rdf4jSystemPropertiesInitializerTest {

	private static final String KEY = "org.eclipse.rdf4j.sail.nativerdf.softFailOnCorruptDataAndRepairIndexes";

	private String previous;

	@BeforeEach
	void rememberSystemProperty() {
		previous = System.getProperty(KEY);
	}

	@AfterEach
	void restoreSystemProperty() {
		if (previous == null) {
			System.clearProperty(KEY);
		} else {
			System.setProperty(KEY, previous);
		}
	}

	@Test
	void apply_configuredTrue_setsSystemPropertyToTrue() {
		System.clearProperty(KEY);

		new Rdf4jSystemPropertiesInitializer(new MockEnvironment().withProperty(KEY, "true")).apply();

		assertEquals("true", System.getProperty(KEY));
	}

	@Test
	void apply_notConfigured_setsSystemPropertyToFalse() {
		System.clearProperty(KEY);

		new Rdf4jSystemPropertiesInitializer(new MockEnvironment()).apply();

		assertEquals("false", System.getProperty(KEY));
	}

	@Test
	void apply_systemPropertyAlreadySet_keepsItOverConfiguredValue() {
		System.setProperty(KEY, "false");

		new Rdf4jSystemPropertiesInitializer(new MockEnvironment().withProperty(KEY, "true")).apply();

		assertEquals("false", System.getProperty(KEY));
	}
}
