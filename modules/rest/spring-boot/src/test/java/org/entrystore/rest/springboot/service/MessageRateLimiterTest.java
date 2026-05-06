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

import com.github.benmanes.caffeine.cache.Ticker;
import org.entrystore.rest.springboot.model.exception.CustomResponseException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MessageRateLimiterTest {

	@Test
	void allowsMessagesUnderLimit() {
		var rateLimiter = new MessageRateLimiter(3, Duration.ofHours(1), Ticker.systemTicker());
		String user = "http://example.com/user/1";

		rateLimiter.acquirePermit(user);
		rateLimiter.acquirePermit(user);

		assertDoesNotThrow(() -> rateLimiter.acquirePermit(user));
	}

	@Test
	void throwsWhenLimitExceeded() {
		var rateLimiter = new MessageRateLimiter(2, Duration.ofHours(1), Ticker.systemTicker());
		String user = "http://example.com/user/1";

		rateLimiter.acquirePermit(user);
		rateLimiter.acquirePermit(user);

		CustomResponseException ex = assertThrows(CustomResponseException.class,
				() -> rateLimiter.acquirePermit(user));
		assertEquals(HttpStatus.TOO_MANY_REQUESTS, ex.getStatus());
	}

	@Test
	void differentUsersHaveSeparateLimits() {
		var rateLimiter = new MessageRateLimiter(1, Duration.ofHours(1), Ticker.systemTicker());
		String user1 = "http://example.com/user/1";
		String user2 = "http://example.com/user/2";

		rateLimiter.acquirePermit(user1);

		assertDoesNotThrow(() -> rateLimiter.acquirePermit(user2));
	}

	@Test
	void disabledWhenMaxIsZero() {
		var rateLimiter = new MessageRateLimiter(0, Duration.ofHours(1), Ticker.systemTicker());
		String user = "http://example.com/user/1";

		assertDoesNotThrow(() -> rateLimiter.acquirePermit(user));
	}

	@Test
	void resetsCounterAfterWindowExpires() {
		var nanos = new AtomicLong();
		var rateLimiter = new MessageRateLimiter(1, Duration.ofSeconds(1), nanos::get);
		String user = "http://example.com/user/1";

		rateLimiter.acquirePermit(user);
		nanos.set(Duration.ofSeconds(2).toNanos());

		assertDoesNotThrow(() -> rateLimiter.acquirePermit(user));
	}

	@Test
	void allowsFirstMessageWithoutPriorRecord() {
		var rateLimiter = new MessageRateLimiter(1, Duration.ofHours(1), Ticker.systemTicker());

		assertDoesNotThrow(() -> rateLimiter.acquirePermit("http://example.com/user/new"));
	}
}
