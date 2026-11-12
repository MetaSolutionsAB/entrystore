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
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SessionTrackingModesTest {

	// The integration tests load their own application.yaml, so only this test covers the shipped one.
	@Test
	void shippedApplicationYaml_takesTheSessionFromItsCookieOnly() throws IOException {
		var sources = new YamlPropertySourceLoader()
				.load("application.yaml", new ClassPathResource("application.yaml"));

		assertEquals("cookie", sources.getFirst().getProperty("server.servlet.session.tracking-modes"),
				"the shipped application.yaml must take a session from its cookie only");
	}
}
