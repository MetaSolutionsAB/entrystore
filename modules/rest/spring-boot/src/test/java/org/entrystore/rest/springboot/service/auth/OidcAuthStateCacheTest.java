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
import org.entrystore.rest.springboot.model.auth.AuthState;
import org.entrystore.rest.springboot.util.CapturingAppender;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OidcAuthStateCacheTest {

	private static final int FLOOD_PER_SECOND = 100;

	private final AtomicLong nanos = new AtomicLong();
	private final OidcAuthStateCache cache = new OidcAuthStateCache(nanos::get);

	@Test
	void storedStateIsRetrievableByIdAndUnknownIdReturnsNull() {
		var authState = new AuthState("http://app.example.com/ok", "http://app.example.com/fail");
		cache.storeAuthState("state-token", authState);

		assertEquals(authState, cache.getAuthState("state-token"));
		assertNull(cache.getAuthState("other-state"));
	}

	// The URLs must live exactly as long as the authorization request saved under the same state, or a slow login
	// still completes but lands on the default URL.
	@Test
	void storedStateExpiresTwoMinutesAfterItWasStored() {
		var authState = new AuthState("http://app.example.com/ok", "http://app.example.com/fail");
		cache.storeAuthState("state-token", authState);

		nanos.addAndGet(Duration.ofSeconds(119).toNanos());
		assertEquals(authState, cache.getAuthState("state-token"));

		nanos.addAndGet(Duration.ofSeconds(1).toNanos());
		assertNull(cache.getAuthState("state-token"));
	}

	// A flood from many clients keeps the cache full. Oldest-first eviction keeps the URLs of a login started during
	// it for MAX_ENTRIES ÷ rate, 100 s at 100 req/s; Caffeine's maximumSize would evict them within about a second.
	@Test
	void loginStartedDuringAFloodKeepsItsStateOneMinuteLater() {
		var authState = new AuthState("http://app.example.com/ok", "http://app.example.com/fail");

		flood(OidcAuthStateCache.MAX_ENTRIES);
		cache.storeAuthState("state-token", authState);
		flood(60 * FLOOD_PER_SECOND);

		assertEquals(authState, cache.getAuthState("state-token"));
	}

	// The oldest-first bound and its throttled SIZE-eviction warn are the DoS defences on this
	// anonymously writable cache — this pins both: the cache never grows past its cap, and
	// sustained capacity eviction emits one throttled WARN rather than one line per eviction.
	@Test
	void capacityEvictionIsBoundedAndWarnsOnce() {
		try (var appender = CapturingAppender.attachTo(OidcAuthStateCache.class)) {
			var authState = new AuthState("http://app.example.com/ok", null);
			for (int i = 0; i < OidcAuthStateCache.MAX_ENTRIES + 100; i++) {
				cache.storeAuthState("state-" + i, authState);
			}

			assertEquals(OidcAuthStateCache.MAX_ENTRIES, cache.caffeineCaches().get("oidc-auth-state").estimatedSize());
			assertTrue(OidcAuthStateCache.MAX_ENTRIES >= 10_000, "cap shrunk below legitimate login concurrency");
			assertEquals(1, appender.countAt(Level.WARN), appender::toString);
			assertTrue(appender.messagesAt(Level.WARN).allMatch(message -> message.contains("capacity")));
		}
	}

	/** Starts {@code logins} logins at {@link #FLOOD_PER_SECOND}, advancing the ticker. */
	private void flood(long logins) {
		var authState = new AuthState("http://app.example.com/flood", null);
		for (long i = 0; i < logins; i++) {
			nanos.addAndGet(Duration.ofSeconds(1).toNanos() / FLOOD_PER_SECOND);
			cache.storeAuthState("flood-" + nanos.get(), authState);
		}
	}
}
