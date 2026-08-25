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

package org.entrystore.rest.springboot.util;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LogThrottleTest {

	@Test
	void firstOccurrenceIsAdmittedAndRepeatsWithinTheIntervalAreNot() {
		var clock = new AtomicLong(1_000_000L);
		var throttle = new LogThrottle(Duration.ofMinutes(1), clock::get);

		assertTrue(throttle.shouldLog());
		assertFalse(throttle.shouldLog());
		clock.addAndGet(Duration.ofSeconds(59).toNanos());
		assertFalse(throttle.shouldLog());
	}

	@Test
	void nextOccurrenceAfterTheIntervalIsAdmittedAgain() {
		var clock = new AtomicLong(1_000_000L);
		var throttle = new LogThrottle(Duration.ofMinutes(1), clock::get);
		assertTrue(throttle.shouldLog());

		clock.addAndGet(Duration.ofMinutes(1).toNanos());
		assertTrue(throttle.shouldLog());
		assertFalse(throttle.shouldLog());
	}
}
