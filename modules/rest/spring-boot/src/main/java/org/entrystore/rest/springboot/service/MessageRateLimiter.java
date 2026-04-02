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

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.entrystore.config.Config;
import org.entrystore.rest.springboot.model.exception.CustomResponseException;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import static org.entrystore.repository.config.Settings.MESSAGE_RATE_LIMIT_MAX;
import static org.entrystore.repository.config.Settings.MESSAGE_RATE_LIMIT_WINDOW;

@Slf4j
@Service
@RequiredArgsConstructor
public class MessageRateLimiter {

	private final Config config;

	private final ConcurrentMap<String, MessageSendRecord> sendMap = new ConcurrentHashMap<>();

	private int maxMessages;
	private Duration window;

	@PostConstruct
	public void init() {
		this.maxMessages = config.getInt(MESSAGE_RATE_LIMIT_MAX, 10);
		this.window = config.getDuration(MESSAGE_RATE_LIMIT_WINDOW, Duration.ofHours(1));
	}

	public void acquirePermit(String userUri) {
		if (maxMessages <= 0 || !window.isPositive()) {
			return;
		}

		MessageSendRecord result = sendMap.compute(userUri, (_, current) -> {
			Instant now = Instant.now();
			if (current == null || now.isAfter(current.windowStart().plus(window))) {
				return new MessageSendRecord(1, now);
			}
			return new MessageSendRecord(current.count() + 1, current.windowStart());
		});

		if (result.count() > maxMessages) {
			throw new CustomResponseException("Rate limit exceeded. Try again later.", HttpStatus.TOO_MANY_REQUESTS);
		}
	}

	@Scheduled(fixedDelay = 3_600_000)
	public void evictExpiredEntries() {
		Instant now = Instant.now();
		int removed = 0;
		var iterator = sendMap.entrySet().iterator();
		while (iterator.hasNext()) {
			var entry = iterator.next();
			if (now.isAfter(entry.getValue().windowStart().plus(window))) {
				iterator.remove();
				removed++;
			}
		}
		if (removed > 0) {
			log.debug("Evicted {} expired rate limit entries", removed);
		}
	}

	record MessageSendRecord(int count, Instant windowStart) {}
}
