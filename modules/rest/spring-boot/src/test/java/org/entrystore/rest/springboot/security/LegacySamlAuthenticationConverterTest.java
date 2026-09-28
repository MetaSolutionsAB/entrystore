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

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.saml2.provider.service.authentication.Saml2AuthenticationException;
import org.springframework.security.saml2.provider.service.authentication.Saml2AuthenticationToken;
import org.springframework.security.saml2.provider.service.authentication.Saml2PostAuthenticationRequest;
import org.springframework.security.saml2.provider.service.authentication.OpenSaml5AuthenticationProvider;
import org.springframework.security.saml2.provider.service.registration.InMemoryRelyingPartyRegistrationRepository;
import org.springframework.security.saml2.provider.service.registration.RelyingPartyRegistration;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LegacySamlAuthenticationConverterTest {

	private final RelyingPartyRegistration acme = registration("acme");
	private final RelyingPartyRegistration other = registration("other");
	private final CacheSaml2AuthenticationRequestRepository requests = new CacheSaml2AuthenticationRequestRepository();
	private final LegacySamlAuthenticationConverter converter = new LegacySamlAuthenticationConverter(
			new SamlAcsRequestMatcher(""), new InMemoryRelyingPartyRegistrationRepository(acme, other), requests);

	@Test
	void legacyCallbackRetainsSavedRequestForSpringValidation() {
		var request = callback("/auth/saml");
		request.setParameter("idp", "acme");
		request.setParameter("RelayState", "state");
		var saved = Saml2PostAuthenticationRequest.withRelyingPartyRegistration(acme)
				.samlRequest("request").relayState("state").id("_request").build();
		requests.saveAuthenticationRequest(saved, request, new MockHttpServletResponse());

		var token = (Saml2AuthenticationToken) converter.convert(request);

		assertEquals("acme", token.getRelyingPartyRegistration().getRegistrationId());
		assertSame(saved, token.getAuthenticationRequest());
	}

	@Test
	void modernCallbackStillResolvesRegistrationFromPath() {
		var token = (Saml2AuthenticationToken) converter.convert(callback("/login/saml2/sso/acme"));
		assertNotNull(token);
		assertEquals("acme", token.getRelyingPartyRegistration().getRegistrationId());
	}

	@Test
	void legacyRoutingDoesNotAuthenticateAnUnsignedResponse() {
		var request = callback("/auth/saml");
		request.setParameter("idp", "acme");
		var token = converter.convert(request);
		assertThrows(Saml2AuthenticationException.class,
				() -> new OpenSaml5AuthenticationProvider().authenticate(token));
	}

	@Test
	void unknownLegacyIdpFailsBeforeResponseConversion() {
		var request = callback("/auth/saml");
		request.setParameter("idp", "unknown");
		assertThrows(Saml2AuthenticationException.class, () -> converter.convert(request));
	}

	@Test
	void missingLegacyIdpDoesNotGuessFromIssuer() {
		assertThrows(Saml2AuthenticationException.class, () -> converter.convert(callback("/auth/saml")));
	}

	@Test
	void duplicateLegacyIdpIsRejected() {
		var request = callback("/auth/saml");
		request.setParameter("idp", "acme", "other");
		assertThrows(Saml2AuthenticationException.class, () -> converter.convert(request));
	}

	@Test
	void legacyIdpCannotOverrideSavedRegistration() {
		var request = callback("/auth/saml");
		request.setParameter("idp", "other");
		request.setParameter("RelayState", "state");
		var saved = Saml2PostAuthenticationRequest.withRelyingPartyRegistration(acme)
				.samlRequest("request").relayState("state").id("_request").build();
		requests.saveAuthenticationRequest(saved, request, new MockHttpServletResponse());
		assertThrows(Saml2AuthenticationException.class, () -> converter.convert(request));
	}

	private static MockHttpServletRequest callback(String path) {
		var request = new MockHttpServletRequest("POST", path);
		String response = """
				<saml2p:Response xmlns:saml2p="urn:oasis:names:tc:SAML:2.0:protocol"
				    xmlns:saml2="urn:oasis:names:tc:SAML:2.0:assertion" ID="_response" Version="2.0"
				    IssueInstant="2026-09-28T00:00:00Z">
				  <saml2:Issuer>https://idp.example</saml2:Issuer>
				  <saml2p:Status><saml2p:StatusCode Value="urn:oasis:names:tc:SAML:2.0:status:Success"/></saml2p:Status>
				</saml2p:Response>
				""";
		request.setParameter("SAMLResponse", Base64.getEncoder().encodeToString(response.getBytes(StandardCharsets.UTF_8)));
		return request;
	}

	private static RelyingPartyRegistration registration(String id) {
		return RelyingPartyRegistration.withRegistrationId(id).entityId("sp")
				.assertionConsumerServiceLocation("https://sp.example/auth/saml?idp=" + id)
				.assertingPartyMetadata(p -> p.entityId("https://idp.example")
						.singleSignOnServiceLocation("https://idp.example/login"))
				.build();
	}
}
