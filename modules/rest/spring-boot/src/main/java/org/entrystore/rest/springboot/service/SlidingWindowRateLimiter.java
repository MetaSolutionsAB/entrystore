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

import lombok.extern.slf4j.Slf4j;
import org.entrystore.rest.springboot.model.exception.CustomResponseException;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Slf4j
public abstract class SlidingWindowRateLimiter {

	private final ConcurrentMap<String, RateLimitRecord> attemptMap = new ConcurrentHashMap<>();

	private final int max;
	private final Duration window;
	private final String rateLimitName;

	protected SlidingWindowRateLimiter(int max, Duration window, String rateLimitName) {
		this.max = max;
		this.window = window;
		this.rateLimitName = rateLimitName;
	}

	public void acquirePermit(String key) {
		if (max <= 0 || !window.isPositive()) {
			return;
		}

		if (key == null || key.isBlank()) {
			log.warn("Cannot apply {} rate limit: key is null or blank, skipping", rateLimitName);
			return;
		}

		RateLimitRecord result = attemptMap.compute(key, (_, current) -> {
			Instant now = Instant.now();
			if (current == null || now.isAfter(current.windowStart().plus(window))) {
				return new RateLimitRecord(1, now);
			}
			return new RateLimitRecord(current.count() + 1, current.windowStart());
		});

		if (result.count() > max) {
			log.info("{} rate limit exceeded for key {}", rateLimitName, key);
			throw new CustomResponseException("Rate limit exceeded. Try again later.", HttpStatus.TOO_MANY_REQUESTS);
		}
	}

	@Scheduled(fixedDelay = 3_600_000)
	public void evictExpiredEntries() {
		if (max <= 0 || !window.isPositive()) {
			return;
		}

		Instant now = Instant.now();
		int removed = 0;
		var iterator = attemptMap.entrySet().iterator();
		while (iterator.hasNext()) {
			var entry = iterator.next();
			if (now.isAfter(entry.getValue().windowStart().plus(window))) {
				iterator.remove();
				removed++;
			}
		}
		if (removed > 0) {
			log.debug("Evicted {} expired {} rate limit entries", removed, rateLimitName);
		}
	}

	private record RateLimitRecord(int count, Instant windowStart) {}
}
