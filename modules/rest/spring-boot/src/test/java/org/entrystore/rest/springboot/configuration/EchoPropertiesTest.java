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
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.util.unit.DataSize;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Binder tests for {@link EchoProperties}. Constructing the record directly — which every
 * {@code EchoServiceTest} case does — cannot catch a key-spelling change, because the component name
 * is what derives the key. These bind through a real {@code Environment} so the spelling is pinned.
 */
class EchoPropertiesTest {

	@Test
	void unsetKey_fallsBackToTheConstantThisKeyReplaced() {
		// The number the constant held before it became configurable: an existing deployment that sets
		// nothing must see no behaviour change. Asserted through the binder rather than by constructing
		// the record, so this actually exercises @DefaultValue.
		runner().run(context ->
				assertEquals(10 * 1024 * 1024, context.getBean(EchoProperties.class).maxFileSize().toBytes()));
	}

	@Test
	void bindsFromTheConfiguredKey() {
		runner().withPropertyValues("entrystore.echo.max-file-size=2MB").run(context ->
				assertEquals(DataSize.ofMegabytes(2), context.getBean(EchoProperties.class).maxFileSize()));
	}

	@Test
	void sizeBeyondTheCeiling_failsFastNamingTheKey() {
		// EchoService reads the payload back as a string and the response HTML-escapes it, so peak heap
		// is a multiple of this cap — on an endpoint that needs no authentication.
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
				() -> new EchoProperties(DataSize.ofMegabytes(128)));

		assertEquals("entrystore.echo.max-file-size must not exceed 64MB — the payload is held in "
				+ "memory and echoed back, got 134217728B", e.getMessage());
		// The ceiling itself is legal.
		assertEquals(DataSize.ofMegabytes(64), new EchoProperties(DataSize.ofMegabytes(64)).maxFileSize());
	}

	private static ApplicationContextRunner runner() {
		return new ApplicationContextRunner().withUserConfiguration(EnableEchoProperties.class);
	}

	@EnableConfigurationProperties(EchoProperties.class)
	static class EnableEchoProperties {
	}
}
