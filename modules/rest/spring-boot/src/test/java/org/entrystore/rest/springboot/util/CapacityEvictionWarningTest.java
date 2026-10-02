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

import com.github.benmanes.caffeine.cache.RemovalCause;
import com.github.benmanes.caffeine.cache.RemovalListener;
import org.apache.logging.log4j.Level;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The helper is the single DoS diagnostic shared by every size-capped cache written on an anonymous
 * path: it must report capacity eviction, stay silent for every other removal cause (ordinary expiry
 * under normal traffic would otherwise raise false flood alarms), and never emit more than one line
 * per interval, or the heap bound would be traded for log amplification.
 */
class CapacityEvictionWarningTest {

	// The helper logs through the logger its owner hands it, so the appender is attached to this
	// class — the same way a cache test attaches to the cache class.
	private static final Logger OWNER_LOG = LoggerFactory.getLogger(CapacityEvictionWarningTest.class);

	@ParameterizedTest(name = "{0}")
	@EnumSource(value = RemovalCause.class, names = "SIZE", mode = EnumSource.Mode.EXCLUDE)
	void nonCapacityEvictionsAreSilent(RemovalCause cause) {
		try (var appender = CapturingAppender.attachTo(CapacityEvictionWarningTest.class)) {
			var warning = new CapacityEvictionWarning(OWNER_LOG, "Widget", 10_000);

			warning.onEviction(cause);

			assertEquals(0, appender.countAt(Level.WARN), appender::toString);
		}
	}

	@Test
	void capacityEvictionWarnsThroughTheOwnersLoggerNamingCacheAndCap() {
		try (var appender = CapturingAppender.attachTo(CapacityEvictionWarningTest.class)) {
			var warning = new CapacityEvictionWarning(OWNER_LOG, "Widget", 10_000);

			warning.onEviction(RemovalCause.SIZE);

			assertEquals(1, appender.countAt(Level.WARN), appender::toString);
			String message = appender.messagesAt(Level.WARN).findFirst().orElseThrow();
			assertTrue(message.contains("capacity"), message);
			assertTrue(message.contains("Widget"), message);
			assertTrue(message.contains("10000"), message);
		}
	}

	@Test
	void sustainedCapacityEvictionWarnsOncePerInterval() {
		var clock = new AtomicLong(1_000_000L);
		var throttle = new LogThrottle(Duration.ofMinutes(1), clock::get);
		try (var appender = CapturingAppender.attachTo(CapacityEvictionWarningTest.class)) {
			var warning = new CapacityEvictionWarning(OWNER_LOG, "Widget", 10_000, throttle);

			warning.onEviction(RemovalCause.SIZE);
			warning.onEviction(RemovalCause.SIZE);
			warning.onEviction(RemovalCause.SIZE);
			assertEquals(1, appender.countAt(Level.WARN), appender::toString);

			clock.addAndGet(Duration.ofMinutes(1).toNanos());
			warning.onEviction(RemovalCause.SIZE);
			assertEquals(2, appender.countAt(Level.WARN), appender::toString);
		}
	}

	@Test
	void listenerForwardsOnlyTheRemovalCause() {
		try (var appender = CapturingAppender.attachTo(CapacityEvictionWarningTest.class)) {
			var warning = new CapacityEvictionWarning(OWNER_LOG, "Widget", 10_000);
			RemovalListener<String, String> listener = warning.listener();

			listener.onRemoval("key", "value", RemovalCause.EXPIRED);
			assertEquals(0, appender.countAt(Level.WARN), appender::toString);

			listener.onRemoval("key", "value", RemovalCause.SIZE);
			assertEquals(1, appender.countAt(Level.WARN), appender::toString);
		}
	}
}
