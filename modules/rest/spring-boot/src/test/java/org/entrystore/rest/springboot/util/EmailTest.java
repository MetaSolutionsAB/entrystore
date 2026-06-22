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

package org.entrystore.rest.springboot.util;

import org.entrystore.Entry;
import org.entrystore.User;
import org.entrystore.config.Config;
import org.entrystore.repository.config.PropertiesConfiguration;
import org.entrystore.repository.config.Settings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EmailTest {

	@Test
	void sendMessage_returnsFalse_whenSmtpHostIsNull() {
		Config config = new PropertiesConfiguration("test");
		config.setProperty(Settings.SMTP_EMAIL_FROM, "noreply@example.com");

		assertFalse(
				assertDoesNotThrow(() -> Email.sendMessage(config, "user@example.com", "subj", "body")),
				"sendMessage must return false when SMTP host is unset");
	}

	@Test
	void sendMessage_returnsFalse_whenSmtpHostIsBlank() {
		Config config = new PropertiesConfiguration("test");
		config.setProperty(Settings.SMTP_HOST, "   ");
		config.setProperty(Settings.SMTP_EMAIL_FROM, "noreply@example.com");

		assertFalse(
				assertDoesNotThrow(() -> Email.sendMessage(config, "user@example.com", "subj", "body")),
				"sendMessage must return false when SMTP host is blank");
	}

	@Test
	void sendMessage_returnsFalse_whenSmtpFromAddressIsMissing() {
		Config config = new PropertiesConfiguration("test");
		config.setProperty(Settings.SMTP_HOST, "smtp.example.com");

		assertFalse(
				assertDoesNotThrow(() -> Email.sendMessage(config, "user@example.com", "subj", "body")),
				"sendMessage must return false when SMTP from-address is unset");
	}

	@Test
	void sendPasswordChangeConfirmation_doesNotThrow_whenBaseUrlIsMissing() {
		// Regression for ENTRYSTORE-1028: the password change is already committed by the time the
		// best-effort confirmation email is sent, so a missing/blank base URL (which used to NPE while
		// substituting __DOMAIN__) must never make the PUT return 500.
		assertPasswordChangeTemplateOnClasspath();
		Config config = new PropertiesConfiguration("test");
		config.setProperty(Settings.SMTP_HOST, "127.0.0.1");
		config.setProperty(Settings.SMTP_PORT, "65000"); // closed port -> send fails fast if reached
		config.setProperty(Settings.SMTP_EMAIL_FROM, "noreply@example.com");
		// BASE_URL deliberately unset -> pre-fix this throws NPE in the __DOMAIN__ substitution.

		User user = mock(User.class);
		when(user.getName()).thenReturn("changed@example.com"); // contains '@' -> the email path is exercised
		Entry entry = mock(Entry.class);
		when(entry.getResource()).thenReturn(user);

		assertDoesNotThrow(() -> Email.sendPasswordChangeConfirmation(config, entry));
	}

	@Test
	void sendPasswordChangeConfirmation_swallowsRuntimeExceptionWhileBuildingEmail() {
		// The best-effort confirmation email must never fail the (already-committed) password change,
		// even when building/sending it throws an unexpected RuntimeException. This exercises the
		// try/catch swallow directly, which the missing-base-URL case no longer reaches now that
		// resolveDomain is null-safe.
		assertPasswordChangeTemplateOnClasspath();
		Config config = mock(Config.class);
		when(config.getString(eq(Settings.AUTH_PASSWORD_CHANGE_SUBJECT), anyString()))
				.thenThrow(new RuntimeException("boom while building confirmation email"));

		User user = mock(User.class);
		when(user.getName()).thenReturn("changed@example.com");
		Entry entry = mock(Entry.class);
		when(entry.getResource()).thenReturn(user);

		assertDoesNotThrow(() -> Email.sendPasswordChangeConfirmation(config, entry));
	}

	@ParameterizedTest(name = "[{0}] -> \"{1}\"")
	@CsvSource(nullValues = "NULL", value = {
			"NULL,                              ''",
			"'   ',                             ''",
			"store,                             ''",
			"https://entrystore.example/store/, entrystore.example"
	})
	void resolveDomain_returnsHostOrEmpty(String baseUrl, String expectedDomain) {
		assertEquals(expectedDomain, Email.resolveDomain(configWithBaseUrl(baseUrl)));
	}

	private static void assertPasswordChangeTemplateOnClasspath() {
		assertNotNull(Thread.currentThread().getContextClassLoader().getResourceAsStream("email_pwchange.html"),
				"email_pwchange.html must be on the test classpath, otherwise these tests are vacuous");
	}

	private static Config configWithBaseUrl(String baseUrl) {
		Config config = new PropertiesConfiguration("test");
		if (baseUrl != null) {
			config.setProperty(Settings.BASE_URL, baseUrl);
		}
		return config;
	}
}
