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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.saml2.provider.service.authentication.Saml2AuthenticationToken;
import org.springframework.security.saml2.provider.service.authentication.Saml2PostAuthenticationRequest;
import org.springframework.security.saml2.provider.service.registration.RelyingPartyRegistration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class LegacyAcsTokenConverterTest {

	private static final String SAML_RESPONSE = "<samlp:Response/>";

	@Test
	void fiveXAcsPostWithoutIdp_validatesAgainstTheAcsLocationWithoutIdp() {
		var token = token(registration("huddinge", "https://h/store/auth/saml?idp=huddinge"));

		var converted = (Saml2AuthenticationToken) convert(token, samlResponsePost("/auth/saml", null));

		assertEquals("https://h/store/auth/saml",
				converted.getRelyingPartyRegistration().getAssertionConsumerServiceLocation());
		assertEquals("huddinge", converted.getRelyingPartyRegistration().getRegistrationId());
		assertEquals("https://idp.example.org",
				converted.getRelyingPartyRegistration().getAssertingPartyMetadata().getEntityId());
		assertEquals(SAML_RESPONSE, converted.getSaml2Response());
		assertSame(token.getAuthenticationRequest(), converted.getAuthenticationRequest());
	}

	@Test
	void fiveXAcsPostWithoutIdp_keepsTheOtherQueryParametersOfTheAcsLocation() {
		var token = token(registration("huddinge", "https://h/store/auth/saml?x=1&idp=huddinge"));

		var converted = (Saml2AuthenticationToken) convert(token, samlResponsePost("/auth/saml", null));

		assertEquals("https://h/store/auth/saml?x=1",
				converted.getRelyingPartyRegistration().getAssertionConsumerServiceLocation());
	}

	@Test
	void fiveXAcsPostWithIdp_keepsTheToken() {
		var token = token(registration("huddinge", "https://h/store/auth/saml?idp=huddinge"));

		assertSame(token, convert(token, samlResponsePost("/auth/saml", "huddinge")));
	}

	@Test
	void springAcsPathWithRegistrationId_keepsTheToken() {
		var token = token(registration("huddinge", "https://h/store/auth/saml?idp=huddinge"));

		assertSame(token, convert(token, samlResponsePost("/login/saml2/sso/huddinge", null)));
	}

	@Test
	void springAcsPathWithoutRegistrationId_keepsTheToken() {
		var token = token(registration("huddinge", "https://h/store/auth/saml?idp=huddinge"));

		assertSame(token, convert(token, samlResponsePost("/login/saml2/sso", null)));
	}

	@Test
	void acsLocationWithoutIdp_keepsTheToken() {
		// The 5.x single-IdP form, and every registration configured with the 6.x keys.
		var token = token(registration("huddinge", "https://h/store/auth/saml"));

		assertSame(token, convert(token, samlResponsePost("/auth/saml", null)));
	}

	@Test
	void acsLocationWithTheIdpOfAnotherRegistration_keepsTheToken() {
		var token = token(registration("huddinge", "https://h/store/auth/saml?idp=other"));

		assertSame(token, convert(token, samlResponsePost("/auth/saml", null)));
	}

	@ParameterizedTest(name = "{0} -> {1}")
	@CsvSource(nullValues = "null", value = {
			"https://h/store/auth/saml?idp=huddinge, https://h/store/auth/saml",
			"https://h/store/auth/saml?x=1&idp=huddinge, https://h/store/auth/saml?x=1",
			"https://h/store/auth/saml?idp=huddinge&x=1, https://h/store/auth/saml?x=1",
			"https://h/store/auth/saml?x=a%26b&idp=huddinge&y=2, https://h/store/auth/saml?x=a%26b&y=2",
			"https://h/store/auth/saml, null",
			"https://h/store/auth/saml?x=1, null",
			"https://h/store/auth/saml?idp=other, null",
			"https://h/store/auth/saml?idp=huddinge&idp=huddinge, null",
			"https://h/store/auth/saml?idp=huddinge&idp=other, null",
			"https://h/store/auth/saml?idp, null",
			"https://h/store/auth/saml?idp=, null",
			"https://h/store/auth/saml?xidp=huddinge, null"
	})
	void withoutIdpParameter_removesOnlyASingleIdpParameterNamingTheRegistration(String location, String expected) {
		assertEquals(expected, LegacyAcsTokenConverter.withoutIdpParameter(location, "huddinge"));
	}

	@Test
	void noToken_returnsNull() {
		var converter = new LegacyAcsTokenConverter(request -> null, new SamlAcsRequestMatcher());

		assertNull(converter.convert(samlResponsePost("/auth/saml", null)));
	}

	private static Object convert(Saml2AuthenticationToken token, MockHttpServletRequest request) {
		return new LegacyAcsTokenConverter(r -> token, new SamlAcsRequestMatcher()).convert(request);
	}

	private static RelyingPartyRegistration registration(String registrationId, String acsLocation) {
		return RelyingPartyRegistration.withRegistrationId(registrationId)
				.entityId("https://h/store")
				.assertionConsumerServiceLocation(acsLocation)
				.assertingPartyMetadata(party -> party
						.entityId("https://idp.example.org")
						.singleSignOnServiceLocation("https://idp.example.org/sso"))
				.build();
	}

	private static Saml2AuthenticationToken token(RelyingPartyRegistration registration) {
		var authenticationRequest = Saml2PostAuthenticationRequest.withRelyingPartyRegistration(registration)
				.samlRequest("request")
				.relayState("relay-state")
				.id("request-id")
				.build();
		return new Saml2AuthenticationToken(registration, SAML_RESPONSE, authenticationRequest);
	}

	private static MockHttpServletRequest samlResponsePost(String path, String idp) {
		var request = new MockHttpServletRequest("POST", "/store" + path);
		request.setContextPath("/store");
		request.setParameter("SAMLResponse", "PHNhbWxwOlJlc3BvbnNlLz4=");
		if (idp != null) {
			request.setParameter("idp", idp);
		}
		return request;
	}
}
