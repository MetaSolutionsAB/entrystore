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

import org.entrystore.config.Config;
import org.entrystore.repository.config.PropertiesConfiguration;
import org.entrystore.repository.config.Settings;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;

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
}
