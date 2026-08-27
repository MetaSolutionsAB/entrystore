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

import com.github.benmanes.caffeine.cache.RemovalCause;
import org.apache.logging.log4j.Level;
import org.entrystore.rest.springboot.model.auth.AuthState;
import org.entrystore.rest.springboot.util.CapturingAppender;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OidcAuthStateCacheTest {

	@Test
	void storedStateIsRetrievableByIdAndUnknownIdReturnsNull() {
		var cache = new OidcAuthStateCache();
		var authState = new AuthState("http://app.example.com/ok", "http://app.example.com/fail");
		cache.storeAuthState("state-token", authState);

		assertEquals(authState, cache.getAuthState("state-token"));
		assertNull(cache.getAuthState("other-state"));
	}

	// The maximumSize bound and its throttled SIZE-eviction warn are the DoS defences on this
	// anonymously writable cache — this pins both: the cache never grows past its cap, and
	// sustained capacity eviction emits one throttled WARN rather than one line per eviction
	// (mirrors the CacheOAuth2AuthorizationRequestRepository test).
	@Test
	void capacityEvictionIsBoundedAndWarnsOnce() {
		try (var appender = CapturingAppender.attachTo(OidcAuthStateCache.class)) {
			var cache = new OidcAuthStateCache();
			var authState = new AuthState("http://app.example.com/ok", null);
			for (int i = 0; i < OidcAuthStateCache.MAX_ENTRIES + 100; i++) {
				cache.storeAuthState("state-" + i, authState);
			}
			var caffeine = cache.caffeineCaches().get("oidc-auth-state");
			caffeine.cleanUp();

			assertTrue(caffeine.estimatedSize() <= OidcAuthStateCache.MAX_ENTRIES);
			// Both bounds pinned against literals — see the CacheOAuth2AuthorizationRequestRepository
			// test for the rationale.
			assertTrue(OidcAuthStateCache.MAX_ENTRIES >= 10_000,
					"declared cap shrunk below legitimate login concurrency");
			assertTrue(caffeine.estimatedSize() >= 9_900,
					"effective cache cap shrunk below legitimate login concurrency");
			assertEquals(1, appender.countAt(Level.WARN), appender::toString);
			assertTrue(appender.messagesAt(Level.WARN).allMatch(message -> message.contains("capacity")));
		}
	}

	// Drives the real listener body per cause: the throttle caps the WARN at 1 either way, so the
	// capacity test above cannot detect removal of the SIZE guard — without it, ordinary expiry
	// under normal login traffic (abandoned attempts timing out) would emit false flood WARNs.
	@Test
	void onlySizeEvictionsWarn() {
		try (var appender = CapturingAppender.attachTo(OidcAuthStateCache.class)) {
			var cache = new OidcAuthStateCache();
			cache.warnIfCapacityEviction(RemovalCause.EXPIRED);
			assertEquals(0, appender.countAt(Level.WARN), appender::toString);

			cache.warnIfCapacityEviction(RemovalCause.SIZE);
			assertEquals(1, appender.countAt(Level.WARN), appender::toString);
		}
	}
}
