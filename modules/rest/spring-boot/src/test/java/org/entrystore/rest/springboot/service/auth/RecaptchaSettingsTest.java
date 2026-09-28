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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecaptchaSettingsTest {

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

	@ParameterizedTest(name = "public-key={0}, private-key={1} -> missing {2}")
	@CsvSource(nullValues = "null", delimiter = '|', value = {
			"null  | secret | [entrystore.auth.recaptcha.public-key]",
			"'  '  | secret | [entrystore.auth.recaptcha.public-key]",
			"site  | null   | [entrystore.auth.recaptcha.private-key]",
			"null  | ''     | [entrystore.auth.recaptcha.public-key, entrystore.auth.recaptcha.private-key]"
	})
	void enabled_withAMissingKey_failsStartupNamingOnlyTheMissingKeys(String siteKey, String secret, String missing) {
		var e = assertThrows(IllegalStateException.class, () -> new RecaptchaSettings(true, siteKey, secret));

		assertTrue(e.getMessage().endsWith("missing: " + missing), e.getMessage());
	}
}
