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

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Ticker;
import org.apache.logging.log4j.Level;
import org.entrystore.rest.springboot.model.exception.CustomResponseException;
import org.entrystore.rest.springboot.util.CapturingAppender;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.HttpStatus;

import java.time.Duration;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientAddressRateLimiterTest {

	@ParameterizedTest(name = "{0} and {1} share a budget")
	@CsvSource({
			"[2001:db8::1], 2001:db8::1",
			"[0:0:0:0:0:0:0:1], ::1",
			"[2001:DB8:0:0:0:0:0:1], [2001:db8::1]",
			"::ffff:192.0.2.60, 192.0.2.60",
			"[2a02:8108:1234:5678:9abc:def0:1234:5678], 2a02:8108:1234:5678:9abc:def0:1234:5678",
			"[203.0.113.7:51234], 203.0.113.7",
			"203.0.113.7:51234, [203.0.113.7:4711]",
			"[2001:db8::1]:4711, 2001:db8::1",
			"[0000:0000:0000:0000:0000:ffff:255.255.255.255]:65535, 255.255.255.255",
			"[0000:0000:0000:0000:0000:ffff:255.255.255.255]:655350, unknown",
			"unknown, _hidden",
			"not-an-ip, [2001:db8::1]:abc",
			"[fe80::1%eth0], [fe80::1%1]",
			"unknown, ",
	})
	void addressesShareABudget(String first, String second) {
		var rateLimiter = new SearchRateLimiter(1, Duration.ofHours(1), Ticker.systemTicker());

		rateLimiter.acquirePermit(first);

		CustomResponseException ex = assertThrows(CustomResponseException.class,
				() -> rateLimiter.acquirePermit(second));
		assertEquals(HttpStatus.TOO_MANY_REQUESTS, ex.getStatus());
	}

	@ParameterizedTest(name = "{0} and {1} have separate budgets")
	@CsvSource({
			"192.0.2.60, 192.0.2.61",
			"[2001:db8::1], [2001:db8::2]",
			"192.0.2.60, unknown",
			"[2001:db8::1], _hidden",
			"[203.0.113.7:51234], 203.0.113.8",
	})
	void addressesHaveSeparateBudgets(String first, String second) {
		var rateLimiter = new SearchRateLimiter(1, Duration.ofHours(1), Ticker.systemTicker());

		rateLimiter.acquirePermit(first);

		assertDoesNotThrow(() -> rateLimiter.acquirePermit(second));
	}

	@Test
	void distinctNonIpAddressesHoldOneCacheEntry() {
		var rateLimiter = new SearchRateLimiter(10, Duration.ofHours(1), Ticker.systemTicker());

		List.of("unknown", "_hidden", "not-an-ip", "x".repeat(8_000)).forEach(rateLimiter::acquirePermit);

		Cache<?, ?> cache = rateLimiter.caffeineCaches().get("rate-limit-search");
		assertEquals(Set.of(FixedWindowRateLimiter.UNKNOWN_KEY), cache.asMap().keySet());
	}

	@Test
	void warningNamesTheSentAddressCutShort() {
		var rateLimiter = new SearchRateLimiter(1, Duration.ofHours(1), Ticker.systemTicker());
		String address = "x".repeat(8_000);

		List<String> warnings;
		try (CapturingAppender appender = CapturingAppender.attachTo(FixedWindowRateLimiter.class)) {
			rateLimiter.acquirePermit(address);
			assertThrows(CustomResponseException.class, () -> rateLimiter.acquirePermit(address));
			warnings = appender.messagesAt(Level.WARN).toList();
		}

		assertEquals(1, warnings.size(), warnings::toString);
		String warning = warnings.getFirst();
		assertTrue(warning.contains("key=" + FixedWindowRateLimiter.UNKNOWN_KEY), warning);
		assertTrue(warning.contains("sent=" + "x".repeat(128) + "…,"), warning);
		assertTrue(warning.length() < 300, "the WARN must not carry the whole address: " + warning.length());
	}
}
