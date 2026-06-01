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

package org.entrystore.rest.springboot.configuration;

import com.github.benmanes.caffeine.cache.Cache;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Publishes a single {@link CacheManager} that wraps the application's hand-built Caffeine caches
 * so they appear under the Actuator {@code /management/caches} endpoint. Without a managed
 * {@code CacheManager} the endpoint reports an empty {@code cacheManagers}, because the caches are
 * created directly via {@code Caffeine.newBuilder()} and are otherwise invisible to Spring.
 *
 * <p>This is the registration half of the {@link CaffeineCacheSource} bridge. The longer-term plan
 * (ENTRYSTORE-1036) is to adopt Spring's {@code @Cacheable} abstraction and retire this class.
 */
@Slf4j
@Configuration
public class CacheRegistryConfiguration {

	@Bean
	public CacheManager cacheManager(List<CaffeineCacheSource> sources, PasswordEncoder passwordEncoder) {
		var allSources = new ArrayList<>(sources);
		// passwordEncoder is published under type PasswordEncoder (the cache-bearing CachingPasswordEncoder
		// is only installed when basic auth is enabled), so type-based List<CaffeineCacheSource> injection
		// does not reliably include it — add it explicitly when it happens to carry a cache.
		if (passwordEncoder instanceof CaffeineCacheSource pe && !allSources.contains(pe)) {
			allSources.add(pe);
		}

		var manager = new CaffeineCacheManager();
		// Non-dynamic: serve only the caches registered below. A stray getCache() miss must not
		// silently materialise an empty cache that then shows up under /caches.
		manager.setCacheNames(List.of());

		var registered = new LinkedHashMap<String, Cache<?, ?>>();
		for (CaffeineCacheSource source : allSources) {
			Map<String, Cache<?, ?>> caches = source.caffeineCaches();
			if (caches == null) {
				throw new IllegalStateException(source.getClass().getName()
						+ " returned null from caffeineCaches() — it must return a (possibly empty) map");
			}
			caches.forEach((name, cache) -> {
				if (cache == null) {
					throw new IllegalStateException("Null cache for name '" + name + "' from "
							+ source.getClass().getName());
				}
				if (registered.putIfAbsent(name, cache) == null) {
					manager.registerCustomCache(name, asObjectCache(cache));
				} else {
					log.warn("Duplicate cache name '{}'; ignoring the second registration", name);
				}
			});
		}

		log.info("Exposed {} Caffeine cache(s) under the actuator caches endpoint: {}",
				registered.size(), registered.keySet());
		return manager;
	}

	// CaffeineCacheManager.registerCustomCache requires Cache<Object, Object>; the sources hold
	// concretely-typed caches (e.g. Cache<String, Integer>). The cast is safe by erasure (both are the
	// same runtime class) and the actuator only invokes type-agnostic operations on the cache
	// (getNativeCache().getClass() for reporting, clear() for eviction) — never typed key/value reads.
	@SuppressWarnings("unchecked")
	private static Cache<Object, Object> asObjectCache(Cache<?, ?> cache) {
		return (Cache<Object, Object>) cache;
	}
}
