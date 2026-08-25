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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class CaseFoldingTest {

	private Locale originalDefaultLocale;

	@BeforeEach
	void rememberDefaultLocale() {
		originalDefaultLocale = Locale.getDefault();
	}

	@AfterEach
	void restoreDefaultLocale() {
		Locale.setDefault(originalDefaultLocale);
	}

	// The reason this helper exists: under a Turkish default locale, the bare toLowerCase() folds
	// "I" to a dotless "ı" (U+0131), which silently breaks hostname/domain/username matching.
	@Test
	void foldsIndependentlyOfTurkishDefaultLocale() {
		Locale.setDefault(Locale.of("tr", "TR"));
		assertEquals("idp.example.com", CaseFolding.toLowerCase("IDP.EXAMPLE.COM"));
	}

	@Test
	void nullIsPassedThrough() {
		assertNull(CaseFolding.toLowerCase(null));
	}
}
