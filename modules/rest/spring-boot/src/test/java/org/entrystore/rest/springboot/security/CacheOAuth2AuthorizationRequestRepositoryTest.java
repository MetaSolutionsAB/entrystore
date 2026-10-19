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

import org.apache.logging.log4j.Level;
import org.entrystore.rest.springboot.util.CapturingAppender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

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
	private static final int FLOOD_PER_SECOND = 100;

	private final AtomicLong nanos = new AtomicLong();
	private CacheOAuth2AuthorizationRequestRepository repository;

	@BeforeEach
	void setUp() {
		repository = new CacheOAuth2AuthorizationRequestRepository(nanos::get);
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

	@Test
	void requestExpiresTwoMinutesAfterItWasSaved() {
		var authorizationRequest = authorizationRequest(STATE);
		repository.saveAuthorizationRequest(authorizationRequest, callbackRequest(null), new MockHttpServletResponse());

		nanos.addAndGet(Duration.ofSeconds(119).toNanos());
		assertSame(authorizationRequest, repository.loadAuthorizationRequest(callbackRequest(STATE)));

		nanos.addAndGet(Duration.ofSeconds(1).toNanos());
		assertNull(repository.loadAuthorizationRequest(callbackRequest(STATE)));
	}

	// A flood from many clients keeps the cache full. Oldest-first eviction keeps a login started during it for
	// MAX_ENTRIES ÷ rate, 100 s at 100 req/s; Caffeine's maximumSize would evict it within about a second.
	@Test
	void loginStartedDuringAFloodIsStillLoadedOneMinuteLater() {
		var authorizationRequest = authorizationRequest(STATE);

		flood(CacheOAuth2AuthorizationRequestRepository.MAX_ENTRIES);
		repository.saveAuthorizationRequest(authorizationRequest, callbackRequest(null), new MockHttpServletResponse());
		flood(60 * FLOOD_PER_SECOND);

		assertSame(authorizationRequest, repository.loadAuthorizationRequest(callbackRequest(STATE)));
		assertSame(authorizationRequest, repository.removeAuthorizationRequest(callbackRequest(STATE),
				new MockHttpServletResponse()));
	}

	// The oldest-first bound and its throttled SIZE-eviction warn are the DoS defences on this
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

			assertEquals(CacheOAuth2AuthorizationRequestRepository.MAX_ENTRIES,
					repository.caffeineCaches().get("oauth2-authz-requests").estimatedSize());
			// Below this, legitimate concurrent logins would fail with authorization_request_not_found
			assertTrue(CacheOAuth2AuthorizationRequestRepository.MAX_ENTRIES >= 10_000,
					"cap shrunk below legitimate login concurrency");
			assertEquals(1, appender.countAt(Level.WARN), appender::toString);
			assertTrue(appender.messagesAt(Level.WARN).allMatch(message -> message.contains("capacity")));
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

	/** Starts {@code logins} logins at {@link #FLOOD_PER_SECOND}, advancing the ticker. */
	private void flood(long logins) {
		var request = callbackRequest(null);
		var response = new MockHttpServletResponse();
		for (long i = 0; i < logins; i++) {
			nanos.addAndGet(Duration.ofSeconds(1).toNanos() / FLOOD_PER_SECOND);
			repository.saveAuthorizationRequest(authorizationRequest("flood-" + nanos.get()), request, response);
		}
	}
}
