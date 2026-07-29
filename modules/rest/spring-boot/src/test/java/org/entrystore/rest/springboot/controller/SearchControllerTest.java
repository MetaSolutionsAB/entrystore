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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.support.PropertySourcesPlaceholderConfigurer;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SearchControllerTest {

	private SearchController searchController;

	@BeforeEach
	void bindLimits() {
		searchController = new SearchController(null, null, null);
		// @Value-injected fields — set via reflection since we're not using a Spring context here.
		// The values mirror the property defaults declared on the fields.
		ReflectionTestUtils.setField(searchController, "solrMaxLimit", 100);
		ReflectionTestUtils.setField(searchController, "solrMaxFacetLimit", 1000);
	}

	@ParameterizedTest(name = "limit {0} clamps to {1}")
	@CsvSource({
			"150, 100", // above the configured maximum — capped at solrMaxLimit
			"100, 100", // exactly the maximum — unchanged
			"42, 42",   // in range — unchanged
			"0, 0",     // 0 is allowed on purpose: enables count-only requests
			"-1, 50",   // negative — falls back to the default page size
	})
	void clampLimit_clampsToConfiguredBounds(int requested, int expected) {
		assertEquals(expected, searchController.clampLimit(requested));
	}

	@Test
	void solrMaxLimit_bindsFromTheConfiguredProperty() {
		// Pins the placeholder key itself, which the reflection-set fields above cannot: a misspelt
		// @Value key would leave every case above green while an operator's entrystore.solr.max-limit
		// was silently ignored and results stayed capped at the default.
		new ApplicationContextRunner()
				.withBean(PropertySourcesPlaceholderConfigurer.class)
				.withBean(SearchController.class, () -> new SearchController(null, null, null))
				.withPropertyValues("entrystore.solr.max-limit=7")
				.run(context -> assertEquals(7, context.getBean(SearchController.class).clampLimit(150)));
	}
}
