/*
 * Copyright (c) 2007-2025 MetaSolutions AB
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

import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class PropertiesConfigurationTest {

	@Test
	public void constructor_ok() {
		PropertiesConfiguration configuration = new PropertiesConfiguration("test");
		assertTrue(configuration.isEmpty());
		assertFalse(configuration.isModified());
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
