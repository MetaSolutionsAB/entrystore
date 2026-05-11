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
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MvcAsyncConfigurationTest {

	@Test
	void defaults_constructWithoutThrowing() {
		MvcAsyncConfiguration cfg = new MvcAsyncConfiguration(8, 32, 16, 15000L);
		assertEquals(8, cfg.corePoolSize());
		assertEquals(32, cfg.maxPoolSize());
		assertEquals(16, cfg.queueCapacity());
		assertEquals(15000L, cfg.defaultTimeoutMs());
	}

	static Stream<Arguments> invalidConfigurations() {
		return Stream.of(
				Arguments.of("corePoolSize negative",          -1, 32,  16, 15000L),
				// maxPoolSize >= corePoolSize: a regression dropping `< corePoolSize` would defer
				// failure to ThreadPoolTaskExecutor.initialize() instead of failing fast at binding.
				Arguments.of("maxPoolSize less than core",      8,  4,  16, 15000L),
				// max-pool-size must be >= max(1, corePoolSize); zero accepts no work. corePoolSize=1
				// here so the test fails *only* on the maxPoolSize floor (the corePoolSize >= 0 check
				// already passes), pinning the >= 1 clamp independently.
				Arguments.of("maxPoolSize zero",                1,  0,  16, 15000L),
				Arguments.of("queueCapacity negative",          8, 32,  -1, 15000L),
				// Zero/negative async timeout would let StreamingResponseBody hang indefinitely,
				// defeating one of the bounded-executor guarantees.
				Arguments.of("defaultTimeoutMs zero",           8, 32,  16,     0L),
				Arguments.of("defaultTimeoutMs negative",       8, 32,  16,    -1L)
		);
	}

	@ParameterizedTest(name = "{0} rejected at construction")
	@MethodSource("invalidConfigurations")
	void constructor_invalidValues_throws(String label, int core, int max, int queue, long timeout) {
		assertThrows(IllegalArgumentException.class,
				() -> new MvcAsyncConfiguration(core, max, queue, timeout));
	}
}
