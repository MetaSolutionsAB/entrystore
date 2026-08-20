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

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class WebResourceUrlsTest {

	@ParameterizedTest(name = "{0} -> {1}")
	@CsvSource({
			// A reverse proxy mounting EntryStore under a prefix: the browser must ask for the
			// prefixed path even though the application itself only ever sees /css/entrystore.css.
			"http://entrystore.org/store/,  /store/css/entrystore.css",
			// Same prefix, no trailing slash on the configured value.
			"http://entrystore.org/store,   /store/css/entrystore.css",
			// Running at the root, with and without a trailing slash.
			"http://localhost:8080/,        /css/entrystore.css",
			"http://localhost:8080,         /css/entrystore.css",
			// Nested prefix.
			"https://example.org/a/b/,      /a/b/css/entrystore.css",
			// Unparsable base URL degrades to the root rather than failing startup.
			"'http://exa mple.org/store/',  /css/entrystore.css",
	})
	void derivesStylesheetPathFromConfiguredBaseUrl(String baseUrl, String expectedPath) {
		assertThat(new WebResourceUrls(baseUrl).getStylesheetPath()).isEqualTo(expectedPath);
	}
}
