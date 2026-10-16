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

import org.apache.logging.log4j.Level;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OldestFirstBoundedCacheTest {

	private static final Logger log = LoggerFactory.getLogger(OldestFirstBoundedCacheTest.class);
	private static final Duration LIFETIME = Duration.ofMinutes(15);

	private final AtomicLong nanos = new AtomicLong();
	private final OldestFirstBoundedCache<String, String> cache =
			new OldestFirstBoundedCache<>(LIFETIME, 3, nanos::get, log, "test");

	@Test
	void evictsTheOldestEntryFirst() {
		putOneSecondApart("a", "b", "c", "d");

		assertNull(cache.getIfPresent("a"));
		assertEquals("b", cache.getIfPresent("b"));
		assertEquals("c", cache.getIfPresent("c"));
		assertEquals("d", cache.getIfPresent("d"));
		assertEquals(3, cache.caffeineCache().estimatedSize());
	}

	// Caffeine's maximumSize would keep the often-read entry and evict the new one instead.
	@Test
	void evictsTheOldestEntryEvenWhenItIsReadOften() {
		putOneSecondApart("a", "b", "c");
		for (int i = 0; i < 20; i++) {
			cache.getIfPresent("a");
		}

		putOneSecondApart("d");

		assertNull(cache.getIfPresent("a"));
		assertEquals("d", cache.getIfPresent("d"));
	}

	@Test
	void neverHoldsMoreThanItsCapacity() {
		for (int i = 0; i < 100; i++) {
			cache.put("key-" + i, "value");
			assertTrue(cache.caffeineCache().estimatedSize() <= 3, "size after put " + i);
		}
	}

	// Concurrent writers may overshoot the cap by the number of threads, never for good: the next put evicts the
	// excess, so a flood from many request threads cannot grow the cache without bound.
	@Test
	void concurrentPutsSettleAtTheCapacityOnTheNextPut() throws Exception {
		var bounded = new OldestFirstBoundedCache<String, String>(LIFETIME, 1_000, nanos::get, log, "test");
		var start = new CountDownLatch(1);
		var executor = Executors.newFixedThreadPool(8);
		try {
			List<Future<?>> writers = IntStream.range(0, 8).<Future<?>>mapToObj(thread -> executor.submit(() -> {
				start.await();
				for (int i = 0; i < 10_000; i++) {
					bounded.put(thread + "-" + i, "value");
				}
				return null;
			})).toList();
			start.countDown();
			for (var writer : writers) {
				writer.get(30, TimeUnit.SECONDS);
			}
		} finally {
			executor.shutdownNow();
		}

		bounded.put("last", "value");

		assertEquals(1_000, bounded.caffeineCache().estimatedSize());
		assertEquals("value", bounded.getIfPresent("last"));
	}

	@Test
	void evictingALiveEntryWarnsAndCountsAsAnEviction() {
		try (var appender = CapturingAppender.attachTo(OldestFirstBoundedCacheTest.class)) {
			putOneSecondApart("a", "b", "c", "d");

			assertEquals(1, appender.countAt(Level.WARN), appender::toString);
			assertTrue(appender.messagesAt(Level.WARN).allMatch(message -> message.contains("capacity")));
			assertEquals(1, cache.caffeineCache().stats().evictionCount());
		}
	}

	// Expired entries still count in estimatedSize until Caffeine removes them; they must not push out a live entry.
	@Test
	void expiredEntriesMakeRoomWithoutACapacityEviction() {
		try (var appender = CapturingAppender.attachTo(OldestFirstBoundedCacheTest.class)) {
			putOneSecondApart("a", "b", "c");
			nanos.addAndGet(LIFETIME.toNanos());
			putOneSecondApart("d", "e", "f");

			assertEquals("d", cache.getIfPresent("d"));
			assertEquals("e", cache.getIfPresent("e"));
			assertEquals("f", cache.getIfPresent("f"));
			assertEquals(0, appender.countAt(Level.WARN), appender::toString);
		}
	}

	@Test
	void removeReturnsTheValueOnce() {
		cache.put("a", "value");

		assertEquals("value", cache.remove("a"));
		assertNull(cache.remove("a"));
		assertNull(cache.getIfPresent("a"));
	}

	private void putOneSecondApart(String... keys) {
		for (String key : keys) {
			nanos.addAndGet(Duration.ofSeconds(1).toNanos());
			cache.put(key, key);
		}
	}
}
