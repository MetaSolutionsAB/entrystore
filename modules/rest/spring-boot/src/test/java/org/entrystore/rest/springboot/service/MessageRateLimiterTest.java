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
	void windowExpiryIsNotExtendedByContinuousOverLimitPolling() {
		// Caffeine's expireAfterWrite resets on every successful compute, including one that
		// returns the current value unchanged. A naive over-limit implementation that rewrites
		// the entry on the reject path would keep pushing the expiry forward, so a client polling
		// at sub-window spacing would never recover from HTTP 429. The fix uses a read-only fast
		// path (`getIfPresent`) once the cap is known to be exceeded, so polling cannot extend the
		// window past the first over-limit write.
		var nanos = new AtomicLong();
		var rateLimiter = new MessageRateLimiter(1, Duration.ofSeconds(1), nanos::get);
		String user = "http://example.com/user/1";

		rateLimiter.acquirePermit(user);                                          // t=0, success
		// Sustained over-limit polling within the window.
		for (long millis : new long[] {500, 800, 1100, 1400}) {
			nanos.set(Duration.ofMillis(millis).toNanos());
			assertThrows(CustomResponseException.class,
					() -> rateLimiter.acquirePermit(user));
		}
		// Past the bounded expiry: the first over-limit write at t=0.5s extended expiry to t=1.5s,
		// but subsequent polls take the read-only path and cannot push expiry further. Without the
		// fix every poll resets the timer and the entry stays alive past t=1.6s.
		nanos.set(Duration.ofMillis(1600).toNanos());
		assertDoesNotThrow(() -> rateLimiter.acquirePermit(user));
	}

	@Test
	void allowsFirstMessageWithoutPriorRecord() {
		var rateLimiter = new MessageRateLimiter(1, Duration.ofHours(1), Ticker.systemTicker());

		assertDoesNotThrow(() -> rateLimiter.acquirePermit("http://example.com/user/new"));
	}
}
