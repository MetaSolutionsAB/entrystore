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

package org.entrystore.rest.springboot.util;

import com.github.benmanes.caffeine.cache.RemovalCause;
import com.github.benmanes.caffeine.cache.RemovalListener;
import org.slf4j.Logger;

import java.time.Duration;
import java.util.Objects;

/**
 * Reports capacity eviction on a size-capped Caffeine cache whose entries are minted on an anonymous,
 * un-rate-limited path. {@code maximumSize} bounds the heap; this class makes the bound observable: a
 * {@link RemovalCause#SIZE} eviction emits one WARN through the owning cache's logger, at most once per
 * minute via {@link LogThrottle}, because the listener runs inside the cache's eviction maintenance and
 * an unthrottled line per eviction would trade the heap bound for log amplification. Every other cause
 * — ordinary expiry above all — stays silent, or normal traffic (abandoned logins timing out) would
 * raise false flood alarms.
 *
 * <p>Owners pass their own logger so the warning is attributed to the cache it concerns and so a
 * log-capturing test can attach to the owner class.
 */
public final class CapacityEvictionWarning {

	private static final Duration WARN_INTERVAL = Duration.ofMinutes(1);

	private final Logger log;
	private final String cacheDescription;
	private final long maxEntries;
	private final LogThrottle throttle;

	public CapacityEvictionWarning(Logger log, String cacheDescription, long maxEntries) {
		this(log, cacheDescription, maxEntries, new LogThrottle(WARN_INTERVAL));
	}

	CapacityEvictionWarning(Logger log, String cacheDescription, long maxEntries, LogThrottle throttle) {
		this.log = Objects.requireNonNull(log, "log");
		this.cacheDescription = Objects.requireNonNull(cacheDescription, "cacheDescription");
		this.maxEntries = maxEntries;
		this.throttle = Objects.requireNonNull(throttle, "throttle");
	}

	/** Listener for {@code Caffeine.evictionListener(...)}; only the removal cause is inspected. */
	public <K, V> RemovalListener<K, V> listener() {
		return (key, value, cause) -> onEviction(cause);
	}

	public void onEviction(RemovalCause cause) {
		// Guard order matters: tryAcquire consumes the interval token (see its Javadoc).
		if (cause == RemovalCause.SIZE && throttle.tryAcquire()) {
			log.warn("{} cache is evicting at capacity ({}) — possible flood on its write path",
					cacheDescription, maxEntries);
		}
	}
}
