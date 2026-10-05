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

package org.entrystore.rest.springboot.configuration;

import org.apache.commons.lang3.exception.ExceptionUtils;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfirmationUrlPropertiesTest {

	private static final String PWRESET = "entrystore.auth.confirmation.url.pwreset";
	private static final String SIGNUP = "entrystore.auth.confirmation.url.signup";

	@Test
	void unset_leavesBothTargetsNull() {
		var urls = bind(Map.of());

		assertNull(urls.pwreset());
		assertNull(urls.signup());
	}

	@Test
	void blank_countsAsUnset() {
		var urls = bind(Map.of(PWRESET, "  "));

		assertNull(urls.pwreset());
	}

	@Test
	void eachFlowBindsIndependently() {
		var urls = bind(Map.of(PWRESET, "https://example.com/resetpassword?confirm=__CONFIRMATION_TOKEN__"));

		assertEquals("https://example.com/resetpassword?confirm=__CONFIRMATION_TOKEN__", urls.pwreset());
		assertNull(urls.signup());
	}

	@Test
	void templateWithOtherQueryParameters_isAccepted() {
		var urls = bind(Map.of(SIGNUP, "https://example.com/confirmsignup?lang=sv&confirm=__CONFIRMATION_TOKEN__"));

		assertEquals("https://example.com/confirmsignup?lang=sv&confirm=__CONFIRMATION_TOKEN__", urls.signup());
	}

	@Test
	void templateWithoutPlaceholder_failsNamingTheKey() {
		var message = configErrorMessage(assertThrows(BindException.class,
				() -> bind(Map.of(SIGNUP, "https://example.com/confirmsignup?confirm="))));

		assertTrue(message.contains(SIGNUP + " must contain __CONFIRMATION_TOKEN__"), message);
	}

	@Test
	void relativeTemplate_isAccepted() {
		var urls = bind(Map.of(PWRESET, "/resetpassword?confirm=__CONFIRMATION_TOKEN__"));

		assertEquals("/resetpassword?confirm=__CONFIRMATION_TOKEN__", urls.pwreset());
	}

	@Test
	void unparseableTemplate_fails() {
		var message = configErrorMessage(assertThrows(BindException.class,
				() -> bind(Map.of(PWRESET, "https://example.com/reset password?confirm=__CONFIRMATION_TOKEN__"))));

		assertTrue(message.contains(PWRESET + " is not a valid URL"), message);
	}

	private static ConfirmationUrlProperties bind(Map<String, String> properties) {
		var source = new MapConfigurationPropertySource(properties);
		return new Binder(source).bindOrCreate("entrystore.auth.confirmation.url", ConfirmationUrlProperties.class);
	}

	private static String configErrorMessage(BindException e) {
		return ExceptionUtils.throwableOfType(e, IllegalArgumentException.class).getMessage();
	}
}
