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

import com.github.benmanes.caffeine.cache.Cache;
import org.apache.logging.log4j.Level;
import org.entrystore.rest.springboot.util.CapturingAppender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.saml2.provider.service.authentication.AbstractSaml2AuthenticationRequest;
import org.springframework.security.saml2.provider.service.authentication.Saml2PostAuthenticationRequest;
import org.springframework.security.saml2.provider.service.registration.RelyingPartyRegistration;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The repository must key strictly on the {@code RelayState} request parameter — never the HTTP
 * session — so the ACS endpoint still finds the in-flight authentication request when
 * SameSite=Strict makes the browser withhold the session cookie on the IdP's cross-site POST.
 */
class CacheSaml2AuthenticationRequestRepositoryTest {

	private static final String RELAY_STATE = "relay-state-token";

	private static final RelyingPartyRegistration REGISTRATION = RelyingPartyRegistration.withRegistrationId("keycloak")
			.entityId("https://sp.example.com/saml2/service-provider-metadata/keycloak")
			.assertionConsumerServiceLocation("https://sp.example.com/login/saml2/sso/keycloak")
			.assertingPartyMetadata(party -> party
					.entityId("https://idp.example.com/realms/test")
					.singleSignOnServiceLocation("https://idp.example.com/realms/test/protocol/saml"))
			.build();

	private CacheSaml2AuthenticationRequestRepository repository;

	@BeforeEach
	void setUp() {
		repository = new CacheSaml2AuthenticationRequestRepository();
	}

	private static AbstractSaml2AuthenticationRequest authnRequest(String relayState) {
		return Saml2PostAuthenticationRequest.withRelyingPartyRegistration(REGISTRATION)
				.samlRequest("PHNhbWxwOkF1dGhuUmVxdWVzdC8+")
				.relayState(relayState)
				.id("_" + relayState)
				.build();
	}

	private static MockHttpServletRequest acsRequest(String relayState) {
		var request = new MockHttpServletRequest("POST", "/login/saml2/sso/keycloak");
		if (relayState != null) {
			request.setParameter("RelayState", relayState);
		}
		return request;
	}

	@Test
	void savedRequestIsLoadedByRelayStateWithoutASession() {
		var authnRequest = authnRequest(RELAY_STATE);
		// Deliberately a different request instance (and no session) on save vs. load — the
		// cross-site ACS POST arrives cookie-less on a fresh exchange.
		repository.saveAuthenticationRequest(authnRequest, acsRequest(null), new MockHttpServletResponse());

		assertSame(authnRequest, repository.loadAuthenticationRequest(acsRequest(RELAY_STATE)));
	}

	@Test
	void loadWithoutRelayStateParameterReturnsNull() {
		repository.saveAuthenticationRequest(authnRequest(RELAY_STATE), acsRequest(null), new MockHttpServletResponse());

		assertNull(repository.loadAuthenticationRequest(acsRequest(null)));
	}

	@Test
	void loadWithUnknownRelayStateReturnsNull() {
		repository.saveAuthenticationRequest(authnRequest(RELAY_STATE), acsRequest(null), new MockHttpServletResponse());

		assertNull(repository.loadAuthenticationRequest(acsRequest("other-relay-state")));
	}

	@Test
	void savingNullRequestStoresNothing() {
		repository.saveAuthenticationRequest(null, acsRequest(null), new MockHttpServletResponse());

		assertEquals(0L, nativeCache().estimatedSize());
	}

	@Test
	void savingRequestWithoutRelayStateStoresNothing() {
		repository.saveAuthenticationRequest(authnRequest(null), acsRequest(null), new MockHttpServletResponse());

		assertEquals(0L, nativeCache().estimatedSize());
	}

	@Test
	void removeReturnsTheRequestOnceAndThenForgetsIt() {
		var authnRequest = authnRequest(RELAY_STATE);
		repository.saveAuthenticationRequest(authnRequest, acsRequest(null), new MockHttpServletResponse());

		assertSame(authnRequest, repository.removeAuthenticationRequest(acsRequest(RELAY_STATE), new MockHttpServletResponse()));
		// A second remove for the same RelayState finds nothing.
		assertNull(repository.removeAuthenticationRequest(acsRequest(RELAY_STATE), new MockHttpServletResponse()));
	}

	@Test
	void removeWithoutRelayStateParameterReturnsNull() {
		repository.saveAuthenticationRequest(authnRequest(RELAY_STATE), acsRequest(null), new MockHttpServletResponse());

		assertNull(repository.removeAuthenticationRequest(acsRequest(null), new MockHttpServletResponse()));
	}

	// Exactly one of N concurrent removes for the same RelayState may return the request: an atomic map
	// remove has one winner, whereas a getIfPresent + invalidate pair can hand the request to several.
	@Test
	void concurrentRemovesOfTheSameRelayStateAdmitExactlyOne() {
		repository.saveAuthenticationRequest(authnRequest(RELAY_STATE), acsRequest(null), new MockHttpServletResponse());
		int callers = 64;
		var start = new CountDownLatch(1);
		var winners = new ConcurrentLinkedQueue<AbstractSaml2AuthenticationRequest>();

		try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
			for (int i = 0; i < callers; i++) {
				pool.submit(() -> {
					start.await();
					var removed = repository.removeAuthenticationRequest(acsRequest(RELAY_STATE), new MockHttpServletResponse());
					if (removed != null) {
						winners.add(removed);
					}
					return null;
				});
			}
			start.countDown();
		}

		assertEquals(1, winners.size(), "exactly one concurrent remove may return the request");
	}

	// The maximumSize bound and its throttled SIZE-eviction warn are the DoS defences on this
	// anonymously writable cache (every GET /auth/saml mints an entry) — this pins both: the cache
	// never grows past its cap, and sustained capacity eviction emits one throttled WARN rather than
	// one line per eviction.
	@Test
	void capacityEvictionIsBoundedAndWarnsOnce() {
		try (var appender = CapturingAppender.attachTo(CacheSaml2AuthenticationRequestRepository.class)) {
			var request = acsRequest(null);
			var response = new MockHttpServletResponse();
			for (int i = 0; i < CacheSaml2AuthenticationRequestRepository.MAX_ENTRIES + 100; i++) {
				repository.saveAuthenticationRequest(authnRequest("relay-" + i), request, response);
			}
			var cache = nativeCache();
			cache.cleanUp();

			assertTrue(cache.estimatedSize() <= CacheSaml2AuthenticationRequestRepository.MAX_ENTRIES);
			// Both bounds pinned against literals — see CacheOAuth2AuthorizationRequestRepositoryTest
			// for why the constant and the effective cap are asserted separately.
			assertTrue(CacheSaml2AuthenticationRequestRepository.MAX_ENTRIES >= 10_000,
					"declared cap shrunk below legitimate login concurrency");
			assertTrue(cache.estimatedSize() >= 9_900,
					"effective cache cap shrunk below legitimate login concurrency");
			assertEquals(1, appender.countAt(Level.WARN), appender::toString);
			assertTrue(appender.messagesAt(Level.WARN).allMatch(message -> message.contains("capacity")));
		}
	}

	private Cache<?, ?> nativeCache() {
		return repository.caffeineCaches().get("saml2-authn-requests");
	}
}
