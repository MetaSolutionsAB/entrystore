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

import java.util.Map;

/**
 * Implemented by beans that own one or more native Caffeine {@link Cache} instances they want
 * surfaced under the Actuator {@code /management/caches} endpoint. {@link CacheRegistryConfiguration}
 * collects every {@code CaffeineCacheSource} bean at startup and registers its caches with the
 * application {@code CacheManager}, so each cache becomes observable (and evictable by an admin)
 * without its owner having to expose the mutable cache reference through a public getter.
 *
 * <p>The caches in this codebase are built directly via {@code Caffeine.newBuilder()} rather than
 * through Spring's {@code @Cacheable} abstraction; this interface is the bridge that keeps them
 * visible until that migration (ENTRYSTORE-1036) lands.
 */
@FunctionalInterface
public interface CaffeineCacheSource {

	/**
	 * @return the caches to expose, keyed by the name they should appear under in {@code /caches};
	 * never {@code null}, and empty when the source currently holds no live cache (for example a
	 * rate limiter disabled by configuration). Values must be non-null native Caffeine caches.
	 */
	Map<String, Cache<?, ?>> caffeineCaches();
}
