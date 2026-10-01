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

import com.github.benmanes.caffeine.cache.Ticker;
import org.entrystore.rest.springboot.configuration.SamlCustomConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.saml2.provider.service.registration.InMemoryRelyingPartyRegistrationRepository;
import org.springframework.security.saml2.provider.service.registration.RelyingPartyRegistration;
import org.springframework.security.saml2.provider.service.web.OpenSaml5AuthenticationTokenConverter;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SamlAcsRequestMatcherTest {

	private final SamlAcsRequestMatcher matcher = new SamlAcsRequestMatcher();

	@Test
	void fiveXAcsPost_exposesTheIdpParameterAsRegistrationId() {
		var result = matcher.matcher(samlResponsePost("/auth/saml", "keycloak"));

		assertTrue(result.isMatch());
		assertEquals(Map.of("registrationId", "keycloak"), result.getVariables());
	}

	@Test
	void fiveXAcsPostWithoutIdp_matchesWithoutRegistrationId() {
		// The 5.x single-IdP form posts without ?idp; the converter then uses the saved AuthnRequest.
		var result = matcher.matcher(samlResponsePost("/auth/saml", " "));

		assertTrue(result.isMatch());
		assertTrue(result.getVariables().isEmpty());
	}

	@Test
	void fiveXAcsPostWithoutSamlResponse_doesNotMatch() {
		var request = new MockHttpServletRequest("POST", "/auth/saml");
		request.setParameter("idp", "keycloak");

		assertFalse(matcher.matches(request));
	}

	@Test
	void otherMethodsOnTheFiveXAcsPath_doNotMatch() {
		// GET /auth/saml starts a login in AuthController and must not reach the SAML response filter.
		for (String method : new String[]{"GET", "PUT"}) {
			var request = samlResponsePost("/auth/saml", "keycloak");
			request.setMethod(method);
			assertFalse(matcher.matches(request), method + " /auth/saml must not match");
		}
	}

	@Test
	void springAcsPath_keepsItsRegistrationIdVariable() {
		var result = matcher.matcher(samlResponsePost("/login/saml2/sso/keycloak", null));

		assertTrue(result.isMatch());
		assertEquals("keycloak", result.getVariables().get("registrationId"));
	}

	@Test
	void springAcsPathWithoutRegistrationId_matches() {
		assertTrue(matcher.matches(samlResponsePost("/login/saml2/sso", null)));
	}

	@Test
	void tokenConverter_resolvesTheRegistrationNamedByTheIdpParameter() {
		var registration = RelyingPartyRegistration.withRegistrationId("keycloak")
				.entityId("EntrystoreDev1")
				.assertionConsumerServiceLocation("https://store.example.org/store/auth/saml?idp=keycloak")
				.assertingPartyMetadata(party -> party
						.entityId("https://idp.example.org")
						.singleSignOnServiceLocation("https://idp.example.org/sso"))
				.build();
		var converter = new OpenSaml5AuthenticationTokenConverter(new InMemoryRelyingPartyRegistrationRepository(registration));
		converter.setRequestMatcher(matcher);
		converter.setAuthenticationRequestRepository(new CacheSaml2AuthenticationRequestRepository(
				new SamlCustomConfiguration(true, null, List.of(), Map.of(), null, null, null), Ticker.systemTicker()));

		var token = converter.convert(samlResponsePost("/auth/saml", "keycloak"));

		assertNotNull(token);
		assertEquals("keycloak", token.getRelyingPartyRegistration().getRegistrationId());
	}

	@ParameterizedTest(name = "{0} /store{1} -> {2}")
	@CsvSource({
			"POST, /login/saml2/sso/keycloak, true",
			"POST, /login/saml2/sso, true",
			"POST, /auth/saml, true",
			"GET, /auth/saml, false",
			"GET, /login/saml2/sso/keycloak, false",
			"POST, /auth/cookie, false",
			"POST, /sparql, false"
	})
	void postToAnyAcsPath_matchesPostsToTheAcsPathsUnderTheContextPath(String method, String path, boolean expected) {
		var request = new MockHttpServletRequest(method, "/store" + path);
		request.setContextPath("/store");

		assertEquals(expected, SamlAcsRequestMatcher.postToAnyAcsPath().matches(request));
	}

	private static MockHttpServletRequest samlResponsePost(String path, String idp) {
		var request = new MockHttpServletRequest("POST", path);
		request.setParameter("SAMLResponse",
				Base64.getEncoder().encodeToString("<samlp:Response/>".getBytes(StandardCharsets.UTF_8)));
		if (idp != null) {
			request.setParameter("idp", idp);
		}
		return request;
	}
}
