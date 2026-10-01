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

import org.apache.logging.log4j.Level;
import org.entrystore.rest.springboot.configuration.SamlCustomConfiguration;
import org.entrystore.rest.springboot.model.auth.AuthState;
import org.entrystore.rest.springboot.util.CapturingAppender;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

	// The maximumSize bound and its throttled SIZE-eviction warn are the DoS defences on this
	// anonymously writable cache (every GET /auth/saml with a whitelisted redirect mints an entry) —
	// this pins both: the cache never grows past its cap, and sustained capacity eviction emits one
	// throttled WARN rather than one line per eviction (mirrors OidcAuthStateCacheTest).
	@Test
	void capacityEvictionIsBoundedAndWarnsOnce() {
		try (var appender = CapturingAppender.attachTo(SamlAuthStateCache.class)) {
			var cache = cacheWithLifetime(null);
			var authState = new AuthState("http://app.example.com/ok", null);
			for (int i = 0; i < SamlAuthStateCache.MAX_ENTRIES + 100; i++) {
				cache.storeAuthState("relay-" + i, authState);
			}
			var caffeine = cache.caffeineCaches().get("saml-auth-state");
			caffeine.cleanUp();

			assertTrue(caffeine.estimatedSize() <= SamlAuthStateCache.MAX_ENTRIES);
			assertTrue(SamlAuthStateCache.MAX_ENTRIES >= 50_000,
					"declared cap shrunk below legitimate login concurrency");
			assertTrue(caffeine.estimatedSize() >= 49_000,
					"effective cache cap shrunk below legitimate login concurrency");
			assertEquals(1, appender.countAt(Level.WARN), appender::toString);
			assertTrue(appender.messagesAt(Level.WARN).allMatch(message -> message.contains("capacity")));
		}
	}

	private SamlAuthStateCache cacheWithLifetime(Duration requestLifetime) {
		var samlConfiguration = new SamlCustomConfiguration(true, null, List.of(), Map.of(), null, null, requestLifetime);
		return new SamlAuthStateCache(samlConfiguration, nanos::get);
	}
}
