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

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Policy;
import com.github.benmanes.caffeine.cache.RemovalCause;
import com.github.benmanes.caffeine.cache.Ticker;
import com.github.benmanes.caffeine.cache.stats.ConcurrentStatsCounter;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.time.Duration;
import java.util.List;

/**
 * A Caffeine cache whose entries expire {@code lifetime} after they are written and which holds at most
 * {@code maxEntries}, evicting the oldest entry first. Caffeine's {@code maximumSize} evicts by frequency instead:
 * once full, it admits a new entry only if that entry was used more often than the eviction victim. When every entry
 * is written once, as on an anonymous login start, a flood therefore evicts each new entry within seconds and keeps
 * the old ones. Evicting the oldest first keeps every entry for at least {@code maxEntries} ÷ write rate. Capacity
 * evictions are reported through {@link CapacityEvictionWarning} and counted in the cache's stats.
 *
 * <p>Concurrent writes to a full cache can exceed {@code maxEntries} by the number of writing threads, until the next
 * write evicts the excess.
 */
public final class OldestFirstBoundedCache<K, V> {

	private final Cache<K, V> cache;
	private final Policy.FixedExpiration<K, V> writeOrder;
	private final ConcurrentStatsCounter stats = new ConcurrentStatsCounter();
	private final long maxEntries;
	private final CapacityEvictionWarning capacityWarning;

	/** {@code log} and {@code description} name the cache in the capacity WARN, as {@link CapacityEvictionWarning}. */
	public OldestFirstBoundedCache(Duration lifetime, long maxEntries, Ticker ticker, Logger log, String description) {
		this.cache = Caffeine.newBuilder()
				.ticker(ticker)
				.expireAfterWrite(lifetime)
				.recordStats(() -> stats)
				.build();
		this.writeOrder = cache.policy().expireAfterWrite().orElseThrow();
		this.maxEntries = maxEntries;
		this.capacityWarning = new CapacityEvictionWarning(log, description, maxEntries + " entries");
	}

	/** The underlying cache, for {@code CaffeineCacheSource}. A write to it directly bypasses the bound. */
	public Cache<K, V> caffeineCache() {
		return cache;
	}

	public @Nullable V getIfPresent(K key) {
		return cache.getIfPresent(key);
	}

	/** Removes and returns the entry for {@code key} atomically, so of two concurrent callers only one gets it. */
	public @Nullable V remove(K key) {
		return cache.asMap().remove(key);
	}

	public void put(K key, V value) {
		cache.put(key, value);
		if (cache.estimatedSize() > maxEntries) {
			evictOldest();
		}
	}

	private void evictOldest() {
		// oldest() first removes the expired entries under the eviction lock, so the excess counts only live entries
		List<Policy.CacheEntry<K, V>> victims = writeOrder.oldest(entries -> entries
				.limit(Math.max(0, cache.estimatedSize() - maxEntries))
				.toList());
		for (var victim : victims) {
			// Caffeine counts an explicit removal as no eviction, so the stats are told here
			if (cache.asMap().remove(victim.getKey(), victim.getValue())) {
				stats.recordEviction(1, RemovalCause.SIZE);
				capacityWarning.onEviction(RemovalCause.SIZE);
			}
		}
	}
}
