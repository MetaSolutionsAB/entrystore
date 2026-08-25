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

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Map;
import java.util.Objects;

/**
 * Binding for the syndication feed-link URL templates
 * ({@code entrystore.syndication.url-template.<name>=https://...&#123;entryid&#125;...}).
 *
 * <p>The template name segment is chosen freely by the operator and supplied by the client at
 * request time, so the keys bind into a {@link Map} keyed by template name, and {@link #template(String)}
 * matches what the client sent against that key verbatim.
 *
 * <p>An un-bracketed key goes through the binder's map-key canonicalisation, which does not necessarily
 * preserve a name containing anything beyond lowercase letters, digits and {@code -}; a name that does
 * not survive it binds under a spelling the client will never ask for, and the feed silently falls back
 * to entry resource URIs. Use the bracket form to keep such a name exactly as written:
 * {@code entrystore.syndication.url-template[my_feed]=...}. {@code SyndicationPropertiesTest} pins what
 * the binder actually does with each form.
 */
@ConfigurationProperties(prefix = "entrystore.syndication")
public record SyndicationProperties(Map<String, String> urlTemplate) {

	public SyndicationProperties {
		// Copy so the singleton never hands out the binder's mutable LinkedHashMap by reference.
		urlTemplate = (urlTemplate == null) ? Map.of() : Map.copyOf(urlTemplate);
	}

	/**
	 * Resolves the template configured under the given name, falling back to the {@code default}
	 * template name when {@code name} is null. Returns null when no such template is configured.
	 */
	public String template(String name) {
		return urlTemplate().get(Objects.requireNonNullElse(name, "default"));
	}
}
