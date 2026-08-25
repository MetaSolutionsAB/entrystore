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

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class SyndicationPropertiesTest {

	@Test
	void binder_bindsUrlTemplateKeysByName() {
		// The cases below construct the record directly, so only this one pins the prefix and the
		// url-template component name. A wrong name there binds an empty map and every feed link
		// silently degrades to the entry resource URI.
		new ApplicationContextRunner()
				.withUserConfiguration(EnableSyndicationProperties.class)
				.withPropertyValues(
						"entrystore.syndication.url-template.default=https://example.com/view/{entryid}",
						"entrystore.syndication.url-template.custom=https://example.com/{contextid}/{entryid}")
				.run(context -> {
					SyndicationProperties properties = context.getBean(SyndicationProperties.class);

					assertEquals("https://example.com/view/{entryid}", properties.template(null));
					assertEquals("https://example.com/{contextid}/{entryid}", properties.template("custom"));
				});
	}

	@Test
	void binder_bracketedName_isKeptVerbatim() {
		// The escape hatch the javadoc points operators at: whatever canonicalisation does to a plain key,
		// a bracketed one binds exactly as written.
		bind("entrystore.syndication.url-template[my_feed]=https://example.com/{entryid}")
				.run(context -> assertEquals("https://example.com/{entryid}",
						context.getBean(SyndicationProperties.class).template("my_feed")));
	}

	@Test
	void binder_plainNameWithUnderscore_bindsUnderTheNameAsWritten() {
		// Characterisation, not a requirement: pins what Spring's map-key handling actually does with an
		// un-bracketed name containing '_'. If this fails, canonicalisation alters the key and the record
		// javadoc must say so — the assertion is the source of truth here, not the prose.
		bind("entrystore.syndication.url-template.my_feed=https://example.com/{entryid}")
				.run(context -> assertEquals("https://example.com/{entryid}",
						context.getBean(SyndicationProperties.class).template("my_feed")));
	}

	@Test
	void template_nullName_fallsBackToDefaultTemplate() {
		var properties = new SyndicationProperties(Map.of("default", "https://example.com/view/{entryid}"));

		assertEquals("https://example.com/view/{entryid}", properties.template(null));
	}

	@Test
	void template_namedTemplate_isResolvedByName() {
		var properties = new SyndicationProperties(Map.of(
				"default", "https://example.com/view/{entryid}",
				"custom", "https://example.com/{contextid}/{entryid}"));

		assertEquals("https://example.com/{contextid}/{entryid}", properties.template("custom"));
	}

	@Test
	void template_unknownName_returnsNull() {
		var properties = new SyndicationProperties(Map.of("default", "https://example.com/view/{entryid}"));

		assertNull(properties.template("nonexistent"));
	}

	@Test
	void template_nothingConfigured_returnsNull() {
		assertNull(new SyndicationProperties(null).template(null));
	}

	private static ApplicationContextRunner bind(String... properties) {
		return new ApplicationContextRunner()
				.withUserConfiguration(EnableSyndicationProperties.class)
				.withPropertyValues(properties);
	}

	@EnableConfigurationProperties(SyndicationProperties.class)
	static class EnableSyndicationProperties {
	}
}
