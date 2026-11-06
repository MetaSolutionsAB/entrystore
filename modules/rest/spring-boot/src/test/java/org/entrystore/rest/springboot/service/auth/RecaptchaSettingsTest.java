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

package org.entrystore.rest.springboot.service.auth;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecaptchaSettingsTest {

	private static final String MISSING_PRIVATE_KEY = "missing: [entrystore.auth.recaptcha.private-key]";

	@Test
	void enabled_withBothKeys_isEnabledAndExposesTheSiteKey() {
		RecaptchaSettings settings = new RecaptchaSettings(true, "site", "secret");

		assertTrue(settings.isEnabled());
		assertEquals("site", settings.getSiteKey());
	}

	@Test
	void disabled_withoutKeys_isDisabledAndDoesNotFailStartup() {
		assertFalse(new RecaptchaSettings(false, null, null).isEnabled());
	}

	@Test
	void disabled_withAConfiguredSiteKey_hidesTheSiteKeySoNoWidgetRenders() {
		RecaptchaSettings settings = new RecaptchaSettings(false, "site", "secret");

		assertFalse(settings.isEnabled());
		assertNull(settings.getSiteKey());
	}

	@Test
	void enabled_withoutPublicKey_staysEnabledAndVerifiesWithThePrivateKey() {
		RecaptchaSettings settings = new RecaptchaSettings(true, null, "secret");

		assertTrue(settings.isEnabled());
		assertNull(settings.getSiteKey());
		assertEquals("secret", settings.getSecret());
	}

	@Test
	void enabled_withBlankPublicKey_exposesNoSiteKeySoNoBrokenWidgetRenders() {
		RecaptchaSettings settings = new RecaptchaSettings(true, "  ", "secret");

		assertTrue(settings.isEnabled());
		assertNull(settings.getSiteKey());
	}

	@Test
	void enabled_withoutPrivateKey_failsStartup() {
		var e = assertThrows(IllegalStateException.class, () -> new RecaptchaSettings(true, "site", null));

		assertTrue(e.getMessage().endsWith(MISSING_PRIVATE_KEY), e.getMessage());
	}

	@Test
	void enabled_withBlankPrivateKey_failsStartup() {
		var e = assertThrows(IllegalStateException.class, () -> new RecaptchaSettings(true, "site", "  "));

		assertTrue(e.getMessage().endsWith(MISSING_PRIVATE_KEY), e.getMessage());
	}

	@Test
	void enabled_withoutAnyKey_failsStartupNamingOnlyThePrivateKey() {
		var e = assertThrows(IllegalStateException.class, () -> new RecaptchaSettings(true, null, null));

		assertTrue(e.getMessage().endsWith(MISSING_PRIVATE_KEY), e.getMessage());
	}
}
