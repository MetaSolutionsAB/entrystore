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
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.logging.DeferredLogFactory;
import org.springframework.core.Ordered;
import org.springframework.mock.env.MockEnvironment;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LegacyPropertyKeyDetectorTest {

	@Test
	void noRemovedKeysPresent_doesNothing() {
		var warnings = new ArrayList<String>();

		newDetector(warnings).postProcessEnvironment(new MockEnvironment(), null);

		assertTrue(warnings.isEmpty());
	}

	@ParameterizedTest
	@ValueSource(strings = {"true", "false"})
	void removedTrustForwardedForKey_warnsWhateverTheValue(String value) {
		var warnings = new ArrayList<String>();
		var env = new MockEnvironment().withProperty("entrystore.trust.x-forwarded-for", value);

		newDetector(warnings).postProcessEnvironment(env, null);

		assertEquals(1, warnings.size());
		assertTrue(warnings.getFirst().contains("'entrystore.trust.x-forwarded-for'"));
		assertTrue(warnings.getFirst().contains("server.forward-headers-strategy=none"));
	}

	@Test
	void runsAfterConfigData() {
		assertEquals(Ordered.LOWEST_PRECEDENCE, newDetector(new ArrayList<>()).getOrder(),
				"Must run after ConfigDataEnvironmentPostProcessor so it sees entrystore.properties imported "
						+ "via spring.config.import");
	}

	@Test
	void springFactoriesWiresThisDetector() {
		assertTrue(RegisteredEnvironmentPostProcessors.load().stream().anyMatch(LegacyPropertyKeyDetector.class::isInstance),
				"META-INF/spring.factories must register LegacyPropertyKeyDetector and Boot must be able to instantiate it");
	}

	private LegacyPropertyKeyDetector newDetector(List<String> warningSink) {
		DeferredLogFactory factory = _ -> new RecordingLog(warningSink);
		return new LegacyPropertyKeyDetector(factory);
	}
}
