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

import org.entrystore.rest.springboot.model.exception.BadRequestException;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UserSettingsRequestBodyTest {

	private final JsonMapper mapper = JsonMapper.builder().build();

	private UserSettingsRequestBody parse(String json) {
		return mapper.readValue(json, UserSettingsRequestBody.class);
	}

	@Test
	void absentMember_isNotPresentSoTheSettingIsLeftAlone() {
		UserSettingsRequestBody body = parse("{\"name\":\"alice\"}");

		assertTrue(body.hasName());
		assertFalse(body.hasLanguage());
		assertFalse(body.hasPassword());
		assertFalse(body.hasDisabled());
		assertFalse(body.hasCustomProperties());
	}

	/**
	 * The distinction the whole {@link tools.jackson.databind.JsonNode} choice exists for: an explicit
	 * {@code null} is a member the caller sent, so it is present-and-malformed rather than absent. With
	 * {@code Optional<String>} components Jackson collapses both to {@code Optional.empty()} and this
	 * body would silently do nothing instead of reporting a bad request.
	 */
	@Test
	void explicitNullMember_isPresentAndRejectedRatherThanIgnored() {
		UserSettingsRequestBody body = parse("{\"language\":null}");

		assertTrue(body.hasLanguage());
		assertThrows(BadRequestException.class, body::languageValue);
	}

	@Test
	void stringMember_isReturnedAsIs() {
		assertEquals("sv", parse("{\"language\":\"sv\"}").languageValue());
		assertEquals("alice", parse("{\"name\":\"alice\"}").nameValue());
		assertEquals("ctx1", parse("{\"homecontext\":\"ctx1\"}").homeContextValue());
	}

	/**
	 * Empty string is a real instruction on this endpoint — it clears the preferred language — so it must
	 * survive as a value rather than being treated as absent.
	 */
	@Test
	void emptyStringLanguage_isAValueNotAnAbsence() {
		UserSettingsRequestBody body = parse("{\"language\":\"\"}");

		assertTrue(body.hasLanguage());
		assertEquals("", body.languageValue());
	}

	/** Coercion would let {@code "language": 5} be stored as the language tag "5". */
	@Test
	void nonStringWhereAStringBelongs_isRejectedRatherThanCoerced() {
		assertThrows(BadRequestException.class, () -> parse("{\"language\":5}").languageValue());
		assertThrows(BadRequestException.class, () -> parse("{\"name\":true}").nameValue());
		assertThrows(BadRequestException.class, () -> parse("{\"password\":[\"a\"]}").passwordValue());
	}

	@Test
	void disabled_trueAndFalse_areReadAsGiven() {
		assertTrue(parse("{\"disabled\":true}").disabledValue());
		assertFalse(parse("{\"disabled\":false}").disabledValue());
	}

	/**
	 * org.json's {@code getBoolean} coerces the strings "true" and "false", and clients rely on it —
	 * {@code ResourceIT} and {@code PasswordResetResourceIT} both send {@code "disabled": "true"} and
	 * expect the user to end up disabled.
	 */
	@Test
	void disabled_stringTrueOrFalse_isCoercedAsOrgJsonDid() {
		assertTrue(parse("{\"disabled\":\"true\"}").disabledValue());
		assertTrue(parse("{\"disabled\":\"TRUE\"}").disabledValue());
		assertFalse(parse("{\"disabled\":\"false\"}").disabledValue());
	}

	/**
	 * Stricter than the {@code optBoolean("disabled", false)} this replaced, deliberately: resolving a
	 * malformed value to false answered 200 UPDATED while leaving the account enabled, and against an
	 * already-disabled account it silently re-enabled it. A security control should not have a quiet
	 * default.
	 */
	@Test
	void disabled_neitherBooleanNorTrueFalseString_isRejected() {
		assertThrows(BadRequestException.class, () -> parse("{\"disabled\":1}").disabledValue());
		assertThrows(BadRequestException.class, () -> parse("{\"disabled\":\"yes\"}").disabledValue());
		assertThrows(BadRequestException.class, () -> parse("{\"disabled\":null}").disabledValue());
		assertThrows(BadRequestException.class, () -> parse("{\"disabled\":[true]}").disabledValue());
	}

	/** Absent is not malformed — it means "leave the disabled status alone". */
	@Test
	void disabled_absent_readsFalseWithoutFailing() {
		assertFalse(parse("{\"name\":\"alice\"}").disabledValue());
	}

	@Test
	void customProperties_objectOfStrings_isReadAsAMap() {
		Map<String, String> properties =
				parse("{\"customProperties\":{\"a\":\"1\",\"b\":\"2\"}}").customPropertiesValue();

		assertEquals(Map.of("a", "1", "b", "2"), properties);
	}

	@Test
	void customProperties_nonObjectOrNonStringValues_areRejected() {
		assertThrows(BadRequestException.class,
				() -> parse("{\"customProperties\":\"nope\"}").customPropertiesValue());
		assertThrows(BadRequestException.class,
				() -> parse("{\"customProperties\":{\"a\":5}}").customPropertiesValue());
		assertThrows(BadRequestException.class,
				() -> parse("{\"customProperties\":null}").customPropertiesValue());
	}

	@Test
	void customProperties_emptyObject_clearsRatherThanFailing() {
		UserSettingsRequestBody body = parse("{\"customProperties\":{}}");

		assertTrue(body.hasCustomProperties());
		assertTrue(body.customPropertiesValue().isEmpty());
	}

	/** Members this endpoint does not act on must not make the whole update fail. */
	@Test
	void unknownMembers_areIgnored() {
		UserSettingsRequestBody body = parse("{\"name\":\"alice\",\"somethingElse\":\"x\"}");

		assertEquals("alice", body.nameValue());
	}
}
