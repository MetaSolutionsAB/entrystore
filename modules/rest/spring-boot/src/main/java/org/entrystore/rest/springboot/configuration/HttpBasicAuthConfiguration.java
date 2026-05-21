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

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Bindings for {@code entrystore.auth.http-basic.*}. The cache settings only take effect when
 * {@link #enabled} is {@code true}, since the wrapper that consumes them is installed only on
 * that branch in {@code SecurityConfig#passwordEncoder}.
 *
 * <p>The size bound applies one entry per {@code (encoded-hash × raw-password)} pair in flight.
 * A password rotation produces a new PBKDF2 {@code encoded} and therefore a new cache key, so
 * the old and new entries coexist until TTL eviction — size for the rotation window, not just
 * steady state. 10 000 fits typical EntryStore deployments; raise it for fleets with many
 * active basic-auth principals.
 */
@ConfigurationProperties(prefix = "entrystore.auth.http-basic")
public record HttpBasicAuthConfiguration(
		@DefaultValue("false") boolean enabled,
		Cache cache
) {
	public HttpBasicAuthConfiguration {
		if (cache == null) cache = new Cache(Duration.ofHours(1), 10_000L);
		if (cache.ttl() == null || cache.ttl().isZero() || cache.ttl().isNegative()) {
			throw new IllegalArgumentException(
					"entrystore.auth.http-basic.cache.ttl must be positive, got " + cache.ttl());
		}
		if (cache.maxSize() <= 0L) {
			throw new IllegalArgumentException(
					"entrystore.auth.http-basic.cache.max-size must be positive, got " + cache.maxSize());
		}
	}

	public record Cache(
			@DefaultValue("1h") Duration ttl,
			@DefaultValue("10000") long maxSize) {}
}
