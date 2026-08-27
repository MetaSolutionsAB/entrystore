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

import com.github.benmanes.caffeine.cache.RemovalCause;
import org.apache.logging.log4j.Level;
import org.entrystore.rest.springboot.util.CapturingAppender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The repository must key strictly on the {@code state} request parameter — never the HTTP
 * session — so the code-exchange step still finds the in-flight authorization request when
 * SameSite=Strict makes the browser withhold the session cookie on the provider's callback
 * redirect.
 */
class CacheOAuth2AuthorizationRequestRepositoryTest {

	private static final String STATE = "state-token";

	private CacheOAuth2AuthorizationRequestRepository repository;

	@BeforeEach
	void setUp() {
		repository = new CacheOAuth2AuthorizationRequestRepository();
	}

	private static OAuth2AuthorizationRequest authorizationRequest(String state) {
		return OAuth2AuthorizationRequest.authorizationCode()
				.authorizationUri("https://idp.example.com/authorize")
				.clientId("entrystore")
				.state(state)
				.build();
	}

	private static MockHttpServletRequest callbackRequest(String state) {
		var request = new MockHttpServletRequest("GET", "/login/oauth2/code/keycloak");
		if (state != null) {
			request.setParameter("state", state);
		}
		return request;
	}

	@Test
	void savedRequestIsLoadedByStateParameterWithoutASession() {
		var authorizationRequest = authorizationRequest(STATE);
		// Deliberately a different request instance (and no session) on save vs. load — the
		// cross-site callback arrives cookie-less on a fresh exchange.
		repository.saveAuthorizationRequest(authorizationRequest, callbackRequest(null), new MockHttpServletResponse());

		assertSame(authorizationRequest, repository.loadAuthorizationRequest(callbackRequest(STATE)));
	}

	@Test
	void loadWithoutStateParameterReturnsNull() {
		repository.saveAuthorizationRequest(authorizationRequest(STATE), callbackRequest(null), new MockHttpServletResponse());

		assertNull(repository.loadAuthorizationRequest(callbackRequest(null)));
	}

	@Test
	void loadWithUnknownStateReturnsNull() {
		repository.saveAuthorizationRequest(authorizationRequest(STATE), callbackRequest(null), new MockHttpServletResponse());

		assertNull(repository.loadAuthorizationRequest(callbackRequest("other-state")));
	}

	// The maximumSize bound and its throttled SIZE-eviction warn are the DoS defences on this
	// anonymously writable cache — this pins both: the cache never grows past its cap, and
	// sustained capacity eviction emits one throttled WARN rather than one line per eviction
	// (which would trade the heap bound for log amplification).
	@Test
	void capacityEvictionIsBoundedAndWarnsOnce() {
		try (var appender = CapturingAppender.attachTo(CacheOAuth2AuthorizationRequestRepository.class)) {
			var request = callbackRequest(null);
			var response = new MockHttpServletResponse();
			for (int i = 0; i < CacheOAuth2AuthorizationRequestRepository.MAX_ENTRIES + 100; i++) {
				repository.saveAuthorizationRequest(authorizationRequest("state-" + i), request, response);
			}
			var cache = repository.caffeineCaches().get("oauth2-authz-requests");
			cache.cleanUp();

			assertTrue(cache.estimatedSize() <= CacheOAuth2AuthorizationRequestRepository.MAX_ENTRIES);
			// Both bounds pinned against literals: the declared constant AND the effective builder
			// cap must stay large enough for legitimate concurrent in-flight logins, or callbacks
			// start failing with authorization_request_not_found. Only the estimatedSize bound
			// catches a shrunken .maximumSize(...) wiring; only the constant bound catches a
			// shrunken MAX_ENTRIES.
			assertTrue(CacheOAuth2AuthorizationRequestRepository.MAX_ENTRIES >= 10_000,
					"declared cap shrunk below legitimate login concurrency");
			assertTrue(cache.estimatedSize() >= 9_900,
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
		try (var appender = CapturingAppender.attachTo(CacheOAuth2AuthorizationRequestRepository.class)) {
			repository.warnIfCapacityEviction(RemovalCause.EXPIRED);
			assertEquals(0, appender.countAt(Level.WARN), appender::toString);

			repository.warnIfCapacityEviction(RemovalCause.SIZE);
			assertEquals(1, appender.countAt(Level.WARN), appender::toString);
		}
	}

	@Test
	void removeReturnsTheRequestOnceAndThenForgetsIt() {
		var authorizationRequest = authorizationRequest(STATE);
		repository.saveAuthorizationRequest(authorizationRequest, callbackRequest(null), new MockHttpServletResponse());

		assertSame(authorizationRequest, repository.removeAuthorizationRequest(callbackRequest(STATE), new MockHttpServletResponse()));
		// A replayed callback with the same state must not find the request again.
		assertNull(repository.removeAuthorizationRequest(callbackRequest(STATE), new MockHttpServletResponse()));
	}

	@Test
	void removeWithoutStateParameterReturnsNull() {
		repository.saveAuthorizationRequest(authorizationRequest(STATE), callbackRequest(null), new MockHttpServletResponse());

		assertNull(repository.removeAuthorizationRequest(callbackRequest(null), new MockHttpServletResponse()));
	}

	@Test
	void savingNullRemovesTheExistingRequest() {
		// Mirrors the contract of Spring's session-based repository: a null request means removal.
		repository.saveAuthorizationRequest(authorizationRequest(STATE), callbackRequest(null), new MockHttpServletResponse());

		repository.saveAuthorizationRequest(null, callbackRequest(STATE), new MockHttpServletResponse());

		assertNull(repository.loadAuthorizationRequest(callbackRequest(STATE)));
	}
}
