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
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LogThrottleTest {

	@Test
	void firstOccurrenceIsAdmittedAndRepeatsWithinTheIntervalAreNot() {
		var clock = new AtomicLong(1_000_000L);
		var throttle = new LogThrottle(Duration.ofMinutes(1), clock::get);

		assertTrue(throttle.tryAcquire());
		assertFalse(throttle.tryAcquire());
		clock.addAndGet(Duration.ofSeconds(59).toNanos());
		assertFalse(throttle.tryAcquire());
	}

	// The interval is measured from the last ADMITTED call: the suppressed call at +30s must not
	// push the window out, or under a sustained flood (one rejected call per eviction) the warn
	// would fire once and then go permanently silent.
	@Test
	void rejectedCallsDoNotExtendTheInterval() {
		var clock = new AtomicLong(1_000_000L);
		var throttle = new LogThrottle(Duration.ofMinutes(1), clock::get);
		assertTrue(throttle.tryAcquire());

		clock.addAndGet(Duration.ofSeconds(30).toNanos());
		assertFalse(throttle.tryAcquire());
		clock.addAndGet(Duration.ofSeconds(30).toNanos());
		assertTrue(throttle.tryAcquire());
		assertFalse(throttle.tryAcquire());
	}

	// Contract test for the class Javadoc's exactly-one-admission guarantee. Today's eviction-
	// listener call sites are serialized by Caffeine's evictionLock, but this is a public util
	// that invites genuinely concurrent per-request call sites. Deterministic: with a frozen
	// clock every thread reads the same stale timestamp, so exactly one CAS can succeed
	// regardless of scheduling — it cannot flake on correct code, and a plain set() admits all.
	@Test
	void concurrentCallersAdmitExactlyOne() throws Exception {
		var frozenClock = new AtomicLong(1_000_000L);
		var throttle = new LogThrottle(Duration.ofMinutes(1), frozenClock::get);
		int threads = 8;
		var barrier = new CyclicBarrier(threads);
		var admitted = new AtomicInteger();

		try (ExecutorService executor = Executors.newFixedThreadPool(threads)) {
			var futures = IntStream.range(0, threads)
					.mapToObj(i -> executor.submit(() -> {
						barrier.await();
						if (throttle.tryAcquire()) {
							admitted.incrementAndGet();
						}
						return null;
					}))
					.toList();
			for (Future<?> future : futures) {
				future.get();
			}
		}

		assertEquals(1, admitted.get());
	}

	@Test
	void nonPositiveIntervalIsRejected() {
		assertThrows(IllegalArgumentException.class, () -> new LogThrottle(Duration.ZERO));
		assertThrows(IllegalArgumentException.class, () -> new LogThrottle(Duration.ofSeconds(-1)));
	}
}
