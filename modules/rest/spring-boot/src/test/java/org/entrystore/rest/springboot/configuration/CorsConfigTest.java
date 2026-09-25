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

import org.entrystore.repository.config.PropertiesConfiguration;
import org.entrystore.repository.config.Settings;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CorsConfigTest {

	@ParameterizedTest(name = "entrystore.cors={0} permits an allowed origin: {1}")
	@CsvSource({
			"true, true", "on, true", "yes, true", "1, true",
			"false, false", "off, false", "no, false", "0, false",
			// Unrecognised values fall back to the disabled default.
			"enabled, false"
	})
	void relaxedBooleanSettingControlsCors(String value, boolean expected) {
		var config = new PropertiesConfiguration("test");
		config.setProperty(Settings.CORS, value);
		config.setProperty(Settings.CORS_ORIGINS, "http://example.com");
		var corsConfig = new CorsConfig(config);
		corsConfig.init();
		var request = new MockHttpServletRequest();
		request.addHeader("Origin", "http://example.com");

		assertEquals(expected, corsConfig.isCorsEnabled());
		assertEquals(expected, corsConfig.corsConfigurationSource().getCorsConfiguration(request) != null);
	}
}
