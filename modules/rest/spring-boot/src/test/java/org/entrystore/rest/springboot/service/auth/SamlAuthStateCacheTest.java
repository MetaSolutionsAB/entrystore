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

import org.entrystore.rest.springboot.configuration.SamlCustomConfiguration;
import org.entrystore.rest.springboot.model.auth.AuthState;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class SamlAuthStateCacheTest {

	private final AtomicLong nanos = new AtomicLong();

	@Test
	void storedStateIsRetrievableByIdAndUnknownIdReturnsNull() {
		var cache = cacheWithLifetime(null);
		var authState = new AuthState("http://app.example.com/ok", "http://app.example.com/fail");
		cache.storeAuthState("relay-state", authState);

		assertEquals(authState, cache.getAuthState("relay-state"));
		assertNull(cache.getAuthState("other-relay-state"));
	}

	// The custom success/failure URLs must survive as long as the authentication request saved under the same
	// relay state, or a slow login lands on the default URL instead.
	@Test
	void storedStateSurvivesFiveMinutesAndExpiresAfterTheDefaultLifetime() {
		var cache = cacheWithLifetime(null);
		var authState = new AuthState("http://app.example.com/ok", null);
		cache.storeAuthState("relay-state", authState);

		nanos.addAndGet(Duration.ofMinutes(5).toNanos());
		assertEquals(authState, cache.getAuthState("relay-state"));

		nanos.addAndGet(Duration.ofMinutes(10).toNanos());
		assertNull(cache.getAuthState("relay-state"));
	}

	@Test
	void storedStateHonoursAConfiguredLifetime() {
		var cache = cacheWithLifetime(Duration.ofMinutes(2));
		cache.storeAuthState("relay-state", new AuthState("http://app.example.com/ok", null));

		nanos.addAndGet(Duration.ofMinutes(2).toNanos());

		assertNull(cache.getAuthState("relay-state"));
	}

	private SamlAuthStateCache cacheWithLifetime(Duration requestLifetime) {
		var samlConfiguration = new SamlCustomConfiguration(true, null, List.of(), Map.of(), null, null, requestLifetime);
		return new SamlAuthStateCache(samlConfiguration, nanos::get);
	}
}
