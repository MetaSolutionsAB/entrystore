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
import com.github.benmanes.caffeine.cache.Ticker;
import lombok.extern.slf4j.Slf4j;
import org.entrystore.rest.springboot.configuration.CaffeineCacheSource;
import org.entrystore.rest.springboot.configuration.SamlCustomConfiguration;
import org.entrystore.rest.springboot.model.auth.AuthState;
import org.entrystore.rest.springboot.util.CapacityEvictionWarning;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Stores SAML authentication state keyed by the relay state token — the whitelist-validated
 * success/failure redirect URLs that must survive the IdP round-trip. Entries expire after
 * {@link SamlCustomConfiguration#requestLifetime()}, the lifetime of the authentication request saved
 * under the same token.
 *
 * <p>The cache is written on the anonymous login-initiation path, so {@code maximumSize} bounds the
 * heap and {@link CapacityEvictionWarning} reports when the bound bites — the same posture as
 * {@link OidcAuthStateCache}.
 */
@Slf4j
@Service
public class SamlAuthStateCache implements CaffeineCacheSource {

	// Cardinality bound: anonymous logins with a whitelisted successurl/failureurl mint entries, and
	// expireAfterWrite bounds only lifetime — cap the size so an initiation flood cannot exhaust the heap.
	static final long MAX_ENTRIES = 50_000;

	private final Cache<String, AuthState> requestCache;

	public SamlAuthStateCache(SamlCustomConfiguration samlConfiguration, Ticker ticker) {
		var capacityWarning = new CapacityEvictionWarning(log, "SAML auth-state", MAX_ENTRIES);
		this.requestCache = Caffeine.newBuilder()
				.ticker(ticker)
				.expireAfterWrite(samlConfiguration.requestLifetime())
				.maximumSize(MAX_ENTRIES)
				.evictionListener(capacityWarning.listener())
				.recordStats()
				.build();
	}

	@Override
	public Map<String, Cache<?, ?>> caffeineCaches() {
		return Map.of("saml-auth-state", requestCache);
	}

	public AuthState getAuthState(String id) {
		return requestCache.getIfPresent(id);
	}

	public void storeAuthState(String id, AuthState authState) {
		requestCache.put(id, authState);
	}
}
