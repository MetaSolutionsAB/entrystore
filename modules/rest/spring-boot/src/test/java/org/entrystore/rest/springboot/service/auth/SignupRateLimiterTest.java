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
	void resetsCounterAfterWindowExpires() throws InterruptedException {
		var rateLimiter = new SignupRateLimiter(1, Duration.ofMillis(50));
		String ip = "192.168.1.1";

		rateLimiter.acquirePermit(ip);
		Thread.sleep(100);
		assertDoesNotThrow(() -> rateLimiter.acquirePermit(ip));
	}

	@Test
	void allowsFirstRequestFromNewIp() {
		var rateLimiter = new SignupRateLimiter(1, Duration.ofHours(1));

		assertDoesNotThrow(() -> rateLimiter.acquirePermit("203.0.113.42"));
	}

	@Test
	void skipsRateLimitForNullIp() {
		var rateLimiter = new SignupRateLimiter(1, Duration.ofHours(1));

		assertDoesNotThrow(() -> {
			rateLimiter.acquirePermit(null);
			rateLimiter.acquirePermit(null);
		});
	}

	@Test
	void skipsRateLimitForBlankIp() {
		var rateLimiter = new SignupRateLimiter(1, Duration.ofHours(1));

		assertDoesNotThrow(() -> {
			rateLimiter.acquirePermit("");
			rateLimiter.acquirePermit("   ");
		});
	}
}
