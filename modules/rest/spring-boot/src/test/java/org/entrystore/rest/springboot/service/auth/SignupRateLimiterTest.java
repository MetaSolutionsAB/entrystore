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

package org.entrystore.rest.springboot.service.auth;

import org.entrystore.rest.springboot.model.exception.CustomResponseException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SignupRateLimiterTest {

	@Test
	void allowsRequestsUnderLimit() {
		var rateLimiter = new SignupRateLimiter(3, Duration.ofHours(1));
		String ip = "192.168.1.1";

		rateLimiter.acquirePermit(ip);
		rateLimiter.acquirePermit(ip);

		assertDoesNotThrow(() -> rateLimiter.acquirePermit(ip));
	}

	@Test
	void throwsWhenLimitExceeded() {
		var rateLimiter = new SignupRateLimiter(2, Duration.ofHours(1));
		String ip = "192.168.1.1";

		rateLimiter.acquirePermit(ip);
		rateLimiter.acquirePermit(ip);

		CustomResponseException ex = assertThrows(CustomResponseException.class,
				() -> rateLimiter.acquirePermit(ip));
		assertEquals(HttpStatus.TOO_MANY_REQUESTS, ex.getStatus());
	}

	@Test
	void differentIpsHaveSeparateLimits() {
		var rateLimiter = new SignupRateLimiter(1, Duration.ofHours(1));
		String ip1 = "10.0.0.1";
		String ip2 = "10.0.0.2";

		rateLimiter.acquirePermit(ip1);

		assertDoesNotThrow(() -> rateLimiter.acquirePermit(ip2));
	}

	@Test
	void disabledWhenMaxIsZero() {
		var rateLimiter = new SignupRateLimiter(0, Duration.ofHours(1));
		String ip = "192.168.1.1";

		assertDoesNotThrow(() -> {
			for (int i = 0; i < 100; i++) {
				rateLimiter.acquirePermit(ip);
			}
		});
	}

	@Test
	void resetsCounterAfterWindowExpires() {
		var nanos = new AtomicLong();
		var rateLimiter = new SignupRateLimiter(1, Duration.ofSeconds(1), nanos::get);
		String ip = "192.168.1.1";

		rateLimiter.acquirePermit(ip);
		nanos.set(Duration.ofSeconds(2).toNanos());

		assertDoesNotThrow(() -> rateLimiter.acquirePermit(ip));
	}

	@Test
	void windowRolloverResetsCount() {
		var nanos = new AtomicLong();
		var rateLimiter = new SignupRateLimiter(2, Duration.ofSeconds(1), nanos::get);
		String ip = "192.168.1.1";

		rateLimiter.acquirePermit(ip);
		rateLimiter.acquirePermit(ip);
		assertThrows(CustomResponseException.class, () -> rateLimiter.acquirePermit(ip));

		nanos.set(Duration.ofSeconds(2).toNanos());

		rateLimiter.acquirePermit(ip);
		rateLimiter.acquirePermit(ip);
		CustomResponseException ex = assertThrows(CustomResponseException.class,
				() -> rateLimiter.acquirePermit(ip));
		assertEquals(HttpStatus.TOO_MANY_REQUESTS, ex.getStatus());
	}

	@Test
	void allowsFirstRequestFromNewIp() {
		var rateLimiter = new SignupRateLimiter(1, Duration.ofHours(1));

		assertDoesNotThrow(() -> rateLimiter.acquirePermit("203.0.113.42"));
	}

	@Test
	void nullKeyUsesSentinelBucket() {
		var rateLimiter = new SignupRateLimiter(1, Duration.ofHours(1));

		rateLimiter.acquirePermit(null);
		assertThrows(CustomResponseException.class, () -> rateLimiter.acquirePermit(null));
	}

	@Test
	void blankKeyUsesSentinelBucket() {
		var rateLimiter = new SignupRateLimiter(1, Duration.ofHours(1));

		rateLimiter.acquirePermit("");
		assertThrows(CustomResponseException.class, () -> rateLimiter.acquirePermit("   "));
	}
}
