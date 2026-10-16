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
import com.github.benmanes.caffeine.cache.Ticker;
import lombok.extern.slf4j.Slf4j;
import org.entrystore.rest.springboot.configuration.CaffeineCacheSource;
import org.entrystore.rest.springboot.configuration.SamlCustomConfiguration;
import org.entrystore.rest.springboot.model.auth.AuthState;
import org.entrystore.rest.springboot.util.OldestFirstBoundedCache;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Stores SAML authentication state keyed by the relay state token — the whitelist-validated
 * success/failure redirect URLs that must survive the IdP round-trip. Entries expire after
 * {@link SamlCustomConfiguration#requestLifetime()}, the lifetime of the authentication request saved
 * under the same token.
 *
 * <p>The cache is written on the anonymous login-start path, which {@link SamlLoginRateLimiter} limits per client.
 * Against a flood from many clients, {@link OldestFirstBoundedCache} bounds the heap and evicts the oldest login first.
 */
@Slf4j
@Service
public class SamlAuthStateCache implements CaffeineCacheSource {

	static final long MAX_ENTRIES = 50_000;

	private final OldestFirstBoundedCache<String, AuthState> requestCache;

	public SamlAuthStateCache(SamlCustomConfiguration samlConfiguration, Ticker ticker) {
		this.requestCache = new OldestFirstBoundedCache<>(samlConfiguration.requestLifetime(), MAX_ENTRIES, ticker, log,
				"SAML auth-state");
	}

	@Override
	public Map<String, Cache<?, ?>> caffeineCaches() {
		return Map.of("saml-auth-state", requestCache.caffeineCache());
	}

	public AuthState getAuthState(String id) {
		return requestCache.getIfPresent(id);
	}

	public void storeAuthState(String id, AuthState authState) {
		requestCache.put(id, authState);
	}
}
