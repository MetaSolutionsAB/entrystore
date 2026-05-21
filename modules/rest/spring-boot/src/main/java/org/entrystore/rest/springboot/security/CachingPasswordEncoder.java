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

package org.entrystore.rest.springboot.security;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import org.entrystore.repository.security.Password;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Duration;
import java.util.Objects;

/**
 * Wraps a {@link PasswordEncoder} with a short-lived in-memory cache of successful verifications,
 * so repeated HTTP Basic auth requests skip the PBKDF2 cost after the first hit. The cache key is
 * {@code SHA-256(encoded + ":" + raw)} so two users with the same password get distinct entries
 * (the encoded hash carries a per-user salt). Only successful verifications are cached, and
 * password rotation invalidates entries naturally (a new salt yields a new key).
 *
 * <p>Disabled-user freshness: {@link #matches} is only ever reached for HTTP Basic — the wrapper
 * is installed only when basic auth is enabled, and {@code DaoAuthenticationProvider} is the
 * single caller. {@code DaoAuthenticationProvider} reloads {@code UserDetails} from
 * {@code ESUserDetailsService} on every basic-auth request and runs pre-auth checks (disabled,
 * locked, expired) <em>before</em> delegating to {@link #matches}, so a freshly-disabled account
 * is rejected even while its cache entry is still warm. Session-authenticated callers (form
 * login, SAML, CAS) never re-enter {@code matches}; their disabled-account freshness is provided
 * by {@code ReloadUserPropertiesFilter}, not by this cache.
 */
public class CachingPasswordEncoder implements PasswordEncoder {

	private final PasswordEncoder delegate;
	private final Cache<String, Boolean> cache;

	public CachingPasswordEncoder(PasswordEncoder delegate, Duration ttl, long maxSize, Ticker ticker) {
		this.delegate = Objects.requireNonNull(delegate, "delegate");
		Objects.requireNonNull(ttl, "ttl");
		Objects.requireNonNull(ticker, "ticker");
		if (ttl.isNegative() || ttl.isZero()) {
			throw new IllegalArgumentException("ttl must be positive, got " + ttl);
		}
		if (maxSize <= 0L) {
			throw new IllegalArgumentException("maxSize must be positive, got " + maxSize);
		}
		// Probe SHA-256 at construction so a JVM without the algorithm (FIPS-restricted
		// providers, custom java.security policy) fails startup instead of degrading every
		// basic-auth request to a silent cache-bypass under load.
		if (Password.sha256("probe") == null) {
			throw new IllegalStateException(
					"SHA-256 is unavailable in the JVM security providers; "
							+ "CachingPasswordEncoder cannot derive cache keys");
		}
		this.cache = Caffeine.newBuilder()
				.expireAfterWrite(ttl)
				.maximumSize(maxSize)
				.ticker(ticker)
				.build();
	}

	@Override
	public String encode(CharSequence rawPassword) {
		return delegate.encode(rawPassword);
	}

	@Override
	public boolean matches(CharSequence rawPassword, String encodedPassword) {
		String key = cacheKey(rawPassword, encodedPassword);
		if (key == null) {
			return delegate.matches(rawPassword, encodedPassword);
		}
		// cache.get(key, loader) deduplicates concurrent first-misses for the same key — a burst
		// of parallel basic-auth requests for one credential runs PBKDF2 once instead of N times.
		// The loader returns null on a failed match; Caffeine treats null as "do not store",
		// which keeps the no-negative-cache invariant.
		return cache.get(key, _ -> delegate.matches(rawPassword, encodedPassword) ? Boolean.TRUE : null) != null;
	}

	private static String cacheKey(CharSequence rawPassword, String encodedPassword) {
		if (rawPassword == null || encodedPassword == null) {
			return null;
		}
		return Password.sha256(encodedPassword + ":" + rawPassword);
	}

	// Test seam: lets CachingPasswordEncoderTest assert storage-level invariants — the
	// no-negative-cache contract (delegate false → cache stays empty) and the maxSize bound —
	// directly, rather than only through behavioural proxies a refactor could pass while
	// breaking the invariant.
	Cache<String, Boolean> cache() {
		return cache;
	}
}
