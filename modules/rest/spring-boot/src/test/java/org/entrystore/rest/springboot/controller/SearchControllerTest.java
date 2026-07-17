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

package org.entrystore.rest.springboot.controller;

import org.entrystore.config.Config;
import org.entrystore.repository.config.Settings;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SearchControllerTest {

	@BeforeEach
	void bindMaxLimitFromConfig() {
		Config config = mock(Config.class);
		when(config.getInt(Settings.SOLR_MAX_LIMIT, 100)).thenReturn(100);
		when(config.getInt(Settings.SOLR_FACET_MAX_LIMIT, 1000)).thenReturn(1000);
		new SearchController(null, null, null, config).init();
	}

	@ParameterizedTest(name = "limit {0} clamps to {1}")
	@CsvSource({
			"150, 100", // above the configured maximum — capped at MAX_LIMIT
			"100, 100", // exactly the maximum — unchanged
			"42, 42",   // in range — unchanged
			"0, 0",     // 0 is allowed on purpose: enables count-only requests
			"-1, 50",   // negative — falls back to the default page size
	})
	void clampLimit_clampsToConfiguredBounds(int requested, int expected) {
		assertEquals(expected, SearchController.clampLimit(requested));
	}
}
