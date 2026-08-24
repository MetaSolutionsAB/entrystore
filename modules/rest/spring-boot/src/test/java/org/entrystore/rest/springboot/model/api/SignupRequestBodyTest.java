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

package org.entrystore.rest.springboot.model.api;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SignupRequestBodyTest {

	private final JsonMapper mapper = JsonMapper.builder().build();

	@Test
	void deserialize_mapsTheLowerCaseWireNamesOntoCamelCaseComponents() {
		String json = """
				{"email":"user@example.com","password":"secret12","firstname":"Ada","lastname":"Lovelace",
				 "urlsuccess":"https://ok.example.com","urlfailure":"https://no.example.com",
				 "grecaptcharesponse":"token"}""";

		SignupRequestBody body = mapper.readValue(json, SignupRequestBody.class);

		assertEquals("user@example.com", body.email());
		assertEquals("secret12", body.password());
		assertEquals("Ada", body.firstName());
		assertEquals("Lovelace", body.lastName());
		assertEquals("https://ok.example.com", body.urlSuccess());
		assertEquals("https://no.example.com", body.urlFailure());
		assertEquals("token", body.rcResponseV2());
	}

	/**
	 * The endpoint used to bind a {@code HashMap<String, String>} and remove the seven known keys, so
	 * whatever remained was carried onto the new user. Binding a record instead only preserves that if
	 * the unrecognised members survive deserialization rather than being dropped or rejected.
	 */
	@Test
	void deserialize_keepsUnrecognisedMembersAsExtraProperties() {
		String json = """
				{"email":"user@example.com","password":"secret12","firstname":"Ada","lastname":"Lovelace",
				 "custom_organisation":"MetaSolutions","custom_role":"admin","unprefixed":"kept too"}""";

		SignupRequestBody body = mapper.readValue(json, SignupRequestBody.class);

		assertEquals(Map.of("custom_organisation", "MetaSolutions", "custom_role", "admin",
				"unprefixed", "kept too"), body.extraProperties());
	}

	/**
	 * Clients that serialise their empty optional fields send {@code "custom_dept": null}. The any-setter
	 * stores that null, and handing it to {@code Map.copyOf} threw inside the record constructor — Jackson
	 * rewrapped it until the caller received a bare JSON "Bad Request", refusing a sign-up the untyped
	 * {@code Map} binding used to complete, and in JSON where this endpoint answers HTML.
	 */
	@Test
	void deserialize_unrecognisedMemberWithAnExplicitNull_isDroppedRatherThanFatal() {
		String json = """
				{"email":"user@example.com","password":"secret12","firstname":"Ada","lastname":"Lovelace",
				 "custom_dept":null,"custom_org":"MetaSolutions"}""";

		SignupRequestBody body = mapper.readValue(json, SignupRequestBody.class);

		assertEquals(Map.of("custom_org", "MetaSolutions"), body.extraProperties());
	}

	@Test
	void deserialize_bodyWithoutExtraMembers_yieldsAnEmptyMapNotNull() {
		String json = """
				{"email":"user@example.com","password":"secret12","firstname":"Ada","lastname":"Lovelace"}""";

		SignupRequestBody body = mapper.readValue(json, SignupRequestBody.class);

		assertTrue(body.extraProperties().isEmpty());
	}

	@Test
	void email_isLowerCasedSoTheEchoedAddressMatchesTheOneTheFlowUses() {
		SignupRequestBody body = new SignupRequestBody("User.Name@EXAMPLE.COM", "secret12",
				"Ada", "Lovelace", null, null, null, Map.of());

		assertEquals("user.name@example.com", body.email());
	}

	/**
	 * Turkish locale folds {@code I} to a dotless {@code ı}, so a default-locale fold would produce a
	 * domain that the sign-up whitelist — which normalises with {@code Locale.ROOT} — can never match.
	 */
	@Test
	void email_isLowerCasedWithLocaleRootRegardlessOfTheDefaultLocale() {
		Locale original = Locale.getDefault();
		try {
			Locale.setDefault(Locale.forLanguageTag("tr-TR"));
			SignupRequestBody body = new SignupRequestBody("USER@DOMAIN.INFO", "secret12",
					"Ada", "Lovelace", null, null, null, Map.of());

			assertEquals("user@domain.info", body.email());
		} finally {
			Locale.setDefault(original);
		}
	}

	@Test
	void email_null_staysNullSoNotEmptyReportsItRatherThanThrowing() {
		SignupRequestBody body = new SignupRequestBody(null, "secret12", "Ada", "Lovelace",
				null, null, null, Map.of());

		assertEquals(null, body.email());
	}

	@Test
	void extraProperties_areCopiedSoLaterChangesToTheSourceMapDoNotShowThrough() {
		Map<String, String> source = new LinkedHashMap<>();
		source.put("custom_a", "1");
		SignupRequestBody body = new SignupRequestBody("user@example.com", "secret12", "Ada",
				"Lovelace", null, null, null, source);

		source.put("custom_b", "2");

		assertEquals(Map.of("custom_a", "1"), body.extraProperties());
		assertThrows(UnsupportedOperationException.class, () -> body.extraProperties().put("custom_c", "3"));
	}

	/**
	 * The form endpoint spells the captcha field {@code g-recaptcha-response} — not a legal record
	 * component name — so it assembles the body itself. The seven consumed keys must not also arrive as
	 * custom properties.
	 */
	@Test
	void fromFormParameters_consumesTheKnownKeysAndKeepsTheRest() {
		Map<String, String> parameters = new LinkedHashMap<>();
		parameters.put("email", "User@Example.com");
		parameters.put("password", "secret12");
		parameters.put("firstname", "Ada");
		parameters.put("lastname", "Lovelace");
		parameters.put("urlsuccess", "https://ok.example.com");
		parameters.put("urlfailure", "https://no.example.com");
		parameters.put("g-recaptcha-response", "token");
		parameters.put("custom_organisation", "MetaSolutions");

		SignupRequestBody body = SignupRequestBody.fromFormParameters(parameters);

		assertEquals("user@example.com", body.email());
		assertEquals("token", body.rcResponseV2());
		assertEquals(Map.of("custom_organisation", "MetaSolutions"), body.extraProperties());
	}

	@Test
	void fromFormParameters_doesNotMutateTheCallersParameterMap() {
		Map<String, String> parameters = new LinkedHashMap<>();
		parameters.put("email", "user@example.com");
		parameters.put("custom_a", "1");

		SignupRequestBody.fromFormParameters(parameters);

		assertEquals(Map.of("email", "user@example.com", "custom_a", "1"), parameters);
	}
}
