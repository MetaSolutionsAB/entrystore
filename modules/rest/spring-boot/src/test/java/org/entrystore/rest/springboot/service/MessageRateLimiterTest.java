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

import org.entrystore.config.Config;
import org.entrystore.rest.springboot.model.exception.CustomResponseException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.time.Duration;

import static org.entrystore.repository.config.Settings.MESSAGE_RATE_LIMIT_MAX;
import static org.entrystore.repository.config.Settings.MESSAGE_RATE_LIMIT_WINDOW;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MessageRateLimiterTest {

	@Mock
	private Config config;

	private MessageRateLimiter rateLimiter;

	@BeforeEach
	void setUp() {
		rateLimiter = new MessageRateLimiter(config);
	}

	@Test
	void allowsMessagesUnderLimit() {
		when(config.getInt(MESSAGE_RATE_LIMIT_MAX, 10)).thenReturn(3);
		when(config.getDuration(MESSAGE_RATE_LIMIT_WINDOW, Duration.ofHours(1))).thenReturn(Duration.ofHours(1));
		rateLimiter.init();

		String user = "http://example.com/user/1";

		rateLimiter.acquirePermit(user);
		rateLimiter.acquirePermit(user);

		assertDoesNotThrow(() -> rateLimiter.acquirePermit(user));
	}

	@Test
	void throwsWhenLimitExceeded() {
		when(config.getInt(MESSAGE_RATE_LIMIT_MAX, 10)).thenReturn(2);
		when(config.getDuration(MESSAGE_RATE_LIMIT_WINDOW, Duration.ofHours(1))).thenReturn(Duration.ofHours(1));
		rateLimiter.init();

		String user = "http://example.com/user/1";

		rateLimiter.acquirePermit(user);
		rateLimiter.acquirePermit(user);

		CustomResponseException ex = assertThrows(CustomResponseException.class,
				() -> rateLimiter.acquirePermit(user));
		assertEquals(HttpStatus.TOO_MANY_REQUESTS, ex.getStatus());
	}

	@Test
	void differentUsersHaveSeparateLimits() {
		when(config.getInt(MESSAGE_RATE_LIMIT_MAX, 10)).thenReturn(1);
		when(config.getDuration(MESSAGE_RATE_LIMIT_WINDOW, Duration.ofHours(1))).thenReturn(Duration.ofHours(1));
		rateLimiter.init();

		String user1 = "http://example.com/user/1";
		String user2 = "http://example.com/user/2";

		rateLimiter.acquirePermit(user1);

		assertDoesNotThrow(() -> rateLimiter.acquirePermit(user2));
	}

	@Test
	void disabledWhenMaxIsZero() {
		when(config.getInt(MESSAGE_RATE_LIMIT_MAX, 10)).thenReturn(0);
		when(config.getDuration(MESSAGE_RATE_LIMIT_WINDOW, Duration.ofHours(1))).thenReturn(Duration.ofHours(1));
		rateLimiter.init();

		String user = "http://example.com/user/1";

		// Should never throw even without recording
		assertDoesNotThrow(() -> rateLimiter.acquirePermit(user));
	}

	@Test
	void resetsCounterAfterWindowExpires() throws InterruptedException {
		when(config.getInt(MESSAGE_RATE_LIMIT_MAX, 10)).thenReturn(1);
		when(config.getDuration(MESSAGE_RATE_LIMIT_WINDOW, Duration.ofHours(1))).thenReturn(Duration.ofMillis(50));
		rateLimiter.init();

		String user = "http://example.com/user/1";
		rateLimiter.acquirePermit(user);
		Thread.sleep(100);
		assertDoesNotThrow(() -> rateLimiter.acquirePermit(user));
	}

	@Test
	void allowsFirstMessageWithoutPriorRecord() {
		when(config.getInt(MESSAGE_RATE_LIMIT_MAX, 10)).thenReturn(1);
		when(config.getDuration(MESSAGE_RATE_LIMIT_WINDOW, Duration.ofHours(1))).thenReturn(Duration.ofHours(1));
		rateLimiter.init();

		assertDoesNotThrow(() -> rateLimiter.acquirePermit("http://example.com/user/new"));
	}
}
