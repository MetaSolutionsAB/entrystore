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
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import lombok.extern.slf4j.Slf4j;
import org.entrystore.rest.springboot.model.exception.CustomResponseException;
import org.springframework.http.HttpStatus;

import java.time.Duration;

@Slf4j
public abstract class SlidingWindowRateLimiter {

	private final Cache<String, Integer> attemptMap;
	private final int max;
	private final Duration window;
	private final String rateLimitName;

	protected SlidingWindowRateLimiter(int max, Duration window, String rateLimitName) {
		this(max, window, rateLimitName, Ticker.systemTicker());
	}

	protected SlidingWindowRateLimiter(int max, Duration window, String rateLimitName, Ticker ticker) {
		this.max = max;
		this.window = window;
		this.rateLimitName = rateLimitName;
		this.attemptMap = (max > 0 && window.isPositive())
				? Caffeine.newBuilder().ticker(ticker).maximumSize(100_000).expireAfterWrite(window).build()
				: null;
	}

	public void acquirePermit(String key) {
		if (max <= 0 || !window.isPositive()) {
			return;
		}

		String effectiveKey = (key == null || key.isBlank()) ? "__unknown__" : key;

		Integer result = attemptMap.asMap().compute(effectiveKey, (_, current) ->
				current == null ? 1 : current + 1);

		if (result > max) {
			log.warn("Rate limit exceeded [event=rate_limit_exceeded, limiter={}, key={}, count={}, max={}]",
					rateLimitName, effectiveKey, result, max);
			throw new CustomResponseException("Rate limit exceeded. Try again later.", HttpStatus.TOO_MANY_REQUESTS);
		}
	}
}
