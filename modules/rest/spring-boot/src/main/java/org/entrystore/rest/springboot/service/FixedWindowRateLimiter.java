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

/**
 * Per-key fixed-window rate limiter backed by a Caffeine cache. The first request for a key
 * starts a window; subsequent requests within that window increment a counter and are rejected
 * once the count exceeds {@code max}. Cache entries expire {@code window} after the first write,
 * which means at the boundary between two adjacent windows a busy key can land up to {@code 2*max}
 * requests just over {@code window} apart — acceptable for use cases where the goal is to bound
 * the average rate rather than guarantee a strict sliding-window cap.
 */
@Slf4j
public abstract class FixedWindowRateLimiter {

	private final Cache<String, Integer> attemptMap;
	private final int max;
	private final Duration window;
	private final String rateLimitName;

	protected FixedWindowRateLimiter(int max, Duration window, String rateLimitName, Ticker ticker) {
		this.max = max;
		this.window = window;
		this.rateLimitName = rateLimitName;
		if (max > 0 && window.isPositive()) {
			this.attemptMap = Caffeine.newBuilder()
					.ticker(ticker)
					.maximumSize(100_000)
					.expireAfterWrite(window)
					.build();
			log.info("Rate limiter [{}] enabled: max={}, window={}", rateLimitName, max, window);
		} else {
			this.attemptMap = null;
			log.warn("Rate limiter [{}] DISABLED (max={}, window={}); all requests will pass",
					rateLimitName, max, window);
		}
	}

	public void acquirePermit(String key) {
		if (attemptMap == null) {
			return;
		}

		String effectiveKey = (key == null || key.isBlank()) ? "__unknown__" : key;
		boolean[] firstOverLimit = new boolean[1];

		Integer result = attemptMap.asMap().compute(effectiveKey, (_, current) -> {
			int next = current == null ? 1 : current + 1;
			if (next > max + 1) {
				return current;
			}
			if (next == max + 1) {
				firstOverLimit[0] = true;
			}
			return next;
		});

		if (result > max) {
			if (firstOverLimit[0]) {
				log.warn("Rate limit exceeded [event=rate_limit_exceeded, limiter={}, key={}, count={}, max={}]",
						rateLimitName, effectiveKey, result, max);
			}
			throw new CustomResponseException("Rate limit exceeded. Try again later.", HttpStatus.TOO_MANY_REQUESTS);
		}
	}
}
