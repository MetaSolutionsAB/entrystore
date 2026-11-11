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

package org.entrystore.rest.springboot.service;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RejectedQueryWarningTest {

	private final MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
	private final List<String> warnings = new CopyOnWriteArrayList<>();
	private final RejectedQueryWarning warning = new RejectedQueryWarning(clock, warnings::add, 4,
			Duration.ofSeconds(1));

	@Test
	void record_firstRejection_warnsAtOnceWithTheLimitAndWait() {
		warning.record();

		assertEquals(List.of("Rejected 1 public SPARQL queries since startup: all 4 slots busy for longer than 1 s; "
				+ "consider raising entrystore.repository.public.sparql.max-concurrent-queries"), warnings);
	}

	@Test
	void record_rejectionsWithinAMinuteOfAWarning_areCountedIntoTheNextWarning() {
		warning.record();
		clock.advance(Duration.ofSeconds(10));
		warning.record();
		clock.advance(Duration.ofSeconds(20));
		warning.record();
		assertEquals(1, warnings.size(), "No second warning within the minute");

		clock.advance(Duration.ofSeconds(31));
		warning.record();

		assertEquals(2, warnings.size());
		assertEquals("Rejected 3 public SPARQL queries in the last 61 s: all 4 slots busy for longer than 1 s; "
				+ "consider raising entrystore.repository.public.sparql.max-concurrent-queries", warnings.get(1));
	}

	@Test
	void record_firstRejectionAfterAQuietPeriod_warnsAtOnce() {
		warning.record();
		clock.advance(Duration.ofMinutes(5));

		warning.record();

		assertEquals(2, warnings.size());
		assertEquals("Rejected 1 public SPARQL queries in the last 300 s: all 4 slots busy for longer than 1 s; "
				+ "consider raising entrystore.repository.public.sparql.max-concurrent-queries", warnings.get(1));
	}

	@Test
	void record_concurrentRejections_warnOnceAndCountEveryRejection() throws Exception {
		CountDownLatch start = new CountDownLatch(1);
		try (ExecutorService executor = Executors.newFixedThreadPool(8)) {
			for (int i = 0; i < 8; i++) {
				executor.submit(() -> {
					start.await();
					warning.record();
					return null;
				});
			}
			start.countDown();
		}
		assertEquals(1, warnings.size());

		clock.advance(Duration.ofMinutes(1));
		warning.record();

		assertEquals(2, warnings.size());
		assertEquals("Rejected 8 public SPARQL queries in the last 60 s: all 4 slots busy for longer than 1 s; "
				+ "consider raising entrystore.repository.public.sparql.max-concurrent-queries", warnings.get(1));
	}

	private static final class MutableClock extends Clock {

		private volatile Instant now;

		MutableClock(Instant now) {
			this.now = now;
		}

		void advance(Duration duration) {
			now = now.plus(duration);
		}

		@Override
		public Instant instant() {
			return now;
		}

		@Override
		public ZoneId getZone() {
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(ZoneId zone) {
			throw new UnsupportedOperationException();
		}
	}
}
