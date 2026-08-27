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

package org.entrystore.rest.springboot.service.auth;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.RemovalCause;
import lombok.extern.slf4j.Slf4j;
import org.entrystore.rest.springboot.configuration.CaffeineCacheSource;
import org.entrystore.rest.springboot.model.auth.AuthState;
import org.entrystore.rest.springboot.util.LogThrottle;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * A cache service for storing and retrieving OIDC authentication state keyed by the OAuth2
 * {@code state} parameter — the OIDC counterpart of {@link SamlAuthStateCache}. Entries carry
 * the whitelist-validated success/failure redirect URLs through the provider round-trip and
 * expire after a fixed duration.
 *
 * <p>DoS posture: written on the anonymous login-initiation path, hence the size cap and the
 * throttled capacity warn in {@link #warnIfCapacityEviction(RemovalCause)} — see
 * {@code CacheOAuth2AuthorizationRequestRepository}'s Javadoc for the shared rationale.
 */
@Slf4j
@Service
public class OidcAuthStateCache implements CaffeineCacheSource {

	// Cardinality bound: anonymous logins with a whitelisted successurl/failureurl mint entries, and
	// expireAfterWrite bounds only lifetime — cap the size so an initiation flood cannot exhaust the
	// heap (mirrors CacheOAuth2AuthorizationRequestRepository).
	static final long MAX_ENTRIES = 10_000;

	private final LogThrottle evictionWarnThrottle = new LogThrottle(Duration.ofMinutes(1));

	private final Cache<String, AuthState> requestCache = Caffeine.newBuilder()
			.expireAfterWrite(2, TimeUnit.MINUTES)
			.maximumSize(MAX_ENTRIES)
			.evictionListener((String state, AuthState value, RemovalCause cause) -> warnIfCapacityEviction(cause))
			.recordStats()
			.build();

	// Package-private so the test can drive the real listener body per cause (class Javadoc).
	void warnIfCapacityEviction(RemovalCause cause) {
		// Guard order matters: tryAcquire consumes the interval token (see its Javadoc).
		if (cause == RemovalCause.SIZE && evictionWarnThrottle.tryAcquire()) {
			log.warn("OIDC auth-state cache is evicting at capacity ({}) — "
					+ "possible login-initiation flood", MAX_ENTRIES);
		}
	}

	@Override
	public Map<String, Cache<?, ?>> caffeineCaches() {
		return Map.of("oidc-auth-state", requestCache);
	}

	public AuthState getAuthState(String id) {
		return requestCache.getIfPresent(id);
	}

	public void storeAuthState(String id, AuthState authState) {
		requestCache.put(id, authState);
	}
}
