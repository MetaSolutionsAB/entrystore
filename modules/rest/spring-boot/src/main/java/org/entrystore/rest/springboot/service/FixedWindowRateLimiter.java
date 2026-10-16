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
import com.github.benmanes.caffeine.cache.Expiry;
import com.github.benmanes.caffeine.cache.Ticker;
import lombok.extern.slf4j.Slf4j;
import org.entrystore.rest.springboot.configuration.CaffeineCacheSource;
import org.entrystore.rest.springboot.model.exception.CustomResponseException;
import org.entrystore.rest.springboot.util.HttpUtil;
import org.jetbrains.annotations.Nullable;
import org.slf4j.event.Level;
import org.springframework.http.HttpStatus;

import java.time.Duration;
import java.util.Map;

/**
 * Per-key fixed-window rate limiter backed by a Caffeine cache. The first request for a key
 * starts a window; subsequent requests within that window increment a counter and are rejected
 * once the count exceeds {@code max}. Cache entries expire {@code window} after the first write,
 * which means at the boundary between two adjacent windows a busy key can land up to {@code 2*max}
 * requests just over {@code window} apart — acceptable for use cases where the goal is to bound
 * the average rate rather than guarantee a strict sliding-window cap.
 */
@Slf4j
public abstract class FixedWindowRateLimiter implements CaffeineCacheSource {

	/** The budget shared by every key whose client cannot be identified. */
	protected static final String UNKNOWN_KEY = "__unknown__";

	private final Cache<String, Integer> attemptMap;
	private final int max;
	private final Duration window;
	private final String rateLimitName;

	protected FixedWindowRateLimiter(int max, Duration window, String rateLimitName, Ticker ticker) {
		this(max, window, rateLimitName, ticker, true);
	}

	/**
	 * @param warnWhenDisabled whether a disabled limiter logs at WARN; pass {@code false} for limiters
	 *                         that are off by default so a default startup does not warn.
	 */
	protected FixedWindowRateLimiter(int max, Duration window, String rateLimitName, Ticker ticker,
			boolean warnWhenDisabled) {
		this.max = max;
		this.window = window;
		this.rateLimitName = rateLimitName;
		if (max > 0 && window.isPositive()) {
			this.attemptMap = Caffeine.newBuilder()
					.ticker(ticker)
					.maximumSize(100_000)
					// Not expireAfterWrite: each counted request would push the window back, so steady traffic
					// below the limit would never reset its count
					.expireAfter(Expiry.creating((String key, Integer count) -> window))
					.recordStats()
					.build();
			log.info("Rate limiter [{}] enabled: max={}, window={}", rateLimitName, max, window);
		} else {
			this.attemptMap = null;
			log.atLevel(warnWhenDisabled ? Level.WARN : Level.INFO)
					.log("Rate limiter [{}] DISABLED (max={}, window={}); all requests will pass",
							rateLimitName, max, window);
		}
	}

	@Override
	public Map<String, Cache<?, ?>> caffeineCaches() {
		// A disabled limiter (max <= 0 or non-positive window) holds no cache; expose nothing then.
		return attemptMap == null ? Map.of() : Map.of("rate-limit-" + rateLimitName, attemptMap);
	}

	/**
	 * The budget {@code key} counts against, never null. A subclass may map several keys to one budget, such as
	 * the spellings of one address, or keys that name no client to {@link #UNKNOWN_KEY}.
	 */
	protected String permitKey(@Nullable String key) {
		return key == null || key.isBlank() ? UNKNOWN_KEY : key;
	}

	/** Counts a request against the budget of {@code key}, answering 429 once that budget is used up. */
	public void acquirePermit(@Nullable String key) {
		if (!tryAcquirePermit(key)) {
			throw new CustomResponseException("Rate limit exceeded. Try again later.", HttpStatus.TOO_MANY_REQUESTS);
		}
	}

	/**
	 * Counts a request against the budget of {@code key}; false once that budget is used up in the current window,
	 * for callers that cannot answer through an exception, such as a servlet filter.
	 */
	public boolean tryAcquirePermit(@Nullable String key) {
		if (attemptMap == null) {
			return true;
		}

		String effectiveKey = permitKey(key);

		// Read-only fast path once over the cap, so a flood of rejected requests writes nothing
		Integer existing = attemptMap.getIfPresent(effectiveKey);
		if (existing != null && existing > max) {
			return false;
		}

		int result = attemptMap.asMap().merge(effectiveKey, 1, Integer::sum);

		if (result <= max) {
			return true;
		}
		// The atomic merge hands max + 1 to exactly one request per window, so the WARN is logged once
		if (result == max + 1) {
			if (effectiveKey.equals(key)) {
				log.warn("Rate limit exceeded [event=rate_limit_exceeded, limiter={}, key={}, count={}, max={}]",
						rateLimitName, effectiveKey, result, max);
			} else {
				// permitKey replaced what the client sent, which the key alone no longer shows
				log.warn("Rate limit exceeded [event=rate_limit_exceeded, limiter={}, key={}, sent={}, count={}, "
						+ "max={}]", rateLimitName, effectiveKey, HttpUtil.sanitizeForLog(key), result, max);
			}
		}
		return false;
	}
}
