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
import static org.junit.jupiter.api.Assertions.assertFalse;
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

			// Bounded from both sides — see the CacheOAuth2AuthorizationRequestRepository test.
			assertTrue(caffeine.estimatedSize() <= OidcAuthStateCache.MAX_ENTRIES);
			assertTrue(caffeine.estimatedSize() >= OidcAuthStateCache.MAX_ENTRIES - 100);
			assertEquals(1, appender.countAt(Level.WARN), appender::toString);
			assertTrue(appender.messagesAt(Level.WARN).allMatch(message -> message.contains("capacity")));
		}
	}

	// The throttle caps the WARN count at 1 whether or not the cause filter exists, so the capacity
	// test above cannot detect its removal — without it, ordinary expiry would warn forever.
	@Test
	void onlySizeEvictionsWarn() {
		assertTrue(OidcAuthStateCache.shouldWarnFor(RemovalCause.SIZE));
		assertFalse(OidcAuthStateCache.shouldWarnFor(RemovalCause.EXPIRED));
		assertFalse(OidcAuthStateCache.shouldWarnFor(RemovalCause.EXPLICIT));
		assertFalse(OidcAuthStateCache.shouldWarnFor(RemovalCause.REPLACED));
	}
}
