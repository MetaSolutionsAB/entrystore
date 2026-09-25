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

class ForwardHeadersStrategyTest {

	// The integration tests load their own application.yaml, so only this test covers the shipped one.
	@Test
	void shippedApplicationYaml_resolvesClientIpFromForwardedHeaders() throws IOException {
		var sources = new YamlPropertySourceLoader()
				.load("application.yaml", new ClassPathResource("application.yaml"));

		assertEquals("framework", sources.getFirst().getProperty("server.forward-headers-strategy"),
				"The per-IP rate limiters key on request.getRemoteAddr(); without the framework strategy "
						+ "every request behind the reverse proxy shares the proxy's address and one budget");
	}
}
