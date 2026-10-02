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
import org.entrystore.rest.springboot.configuration.SamlCustomConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.saml2.provider.service.authentication.AbstractSaml2AuthenticationRequest;
import org.springframework.security.saml2.provider.service.authentication.Saml2PostAuthenticationRequest;
import org.springframework.security.saml2.provider.service.registration.RelyingPartyRegistration;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

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

	private final AtomicLong nanos = new AtomicLong();
	private CacheSaml2AuthenticationRequestRepository repository;

	@BeforeEach
	void setUp() {
		repository = repositoryWithLifetime(null);
	}

	private CacheSaml2AuthenticationRequestRepository repositoryWithLifetime(Duration requestLifetime) {
		var samlConfiguration = new SamlCustomConfiguration(true, null, List.of(), Map.of(), null, null, requestLifetime);
		return new CacheSaml2AuthenticationRequestRepository(samlConfiguration, nanos::get);
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

	// A user may spend minutes at the IdP (MFA, consent, password change); 5.x accepted such responses.
	@Test
	void requestIsStillLoadedFiveMinutesAfterItWasSaved() {
		var authnRequest = authnRequest(RELAY_STATE);
		repository.saveAuthenticationRequest(authnRequest, acsRequest(null), new MockHttpServletResponse());

		nanos.addAndGet(Duration.ofMinutes(5).toNanos());

		assertSame(authnRequest, repository.loadAuthenticationRequest(acsRequest(RELAY_STATE)));
	}

	@Test
	void requestExpiresAfterTheDefaultLifetimeOfFifteenMinutes() {
		repository.saveAuthenticationRequest(authnRequest(RELAY_STATE), acsRequest(null), new MockHttpServletResponse());

		nanos.addAndGet(Duration.ofMinutes(15).toNanos());

		assertNull(repository.loadAuthenticationRequest(acsRequest(RELAY_STATE)));
	}

	@Test
	void requestHonoursAConfiguredLifetime() {
		repository = repositoryWithLifetime(Duration.ofMinutes(30));
		var authnRequest = authnRequest(RELAY_STATE);
		repository.saveAuthenticationRequest(authnRequest, acsRequest(null), new MockHttpServletResponse());

		nanos.addAndGet(Duration.ofMinutes(20).toNanos());
		assertSame(authnRequest, repository.loadAuthenticationRequest(acsRequest(RELAY_STATE)));

		nanos.addAndGet(Duration.ofMinutes(10).toNanos());
		assertNull(repository.loadAuthenticationRequest(acsRequest(RELAY_STATE)));
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

	private Cache<?, ?> nativeCache() {
		return repository.caffeineCaches().get("saml2-authn-requests");
	}
}
