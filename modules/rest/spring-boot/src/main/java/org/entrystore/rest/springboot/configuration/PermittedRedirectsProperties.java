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

/**
 * Binding for the permitted post-login redirect base URLs ({@code entrystore.auth.permitted.redirects.*}).
 *
 * <p>EntryStore expresses lists in the legacy indexed form ({@code ...redirects.1=https://...},
 * {@code ...redirects.2=...}), which Spring's binder reads into a {@link Map} keyed by the numeric
 * suffix rather than into a {@code List}. {@code RedirectUrlValidator} consumes {@code redirects().values()}
 * as a membership test, so iteration order is unspecified — {@code Map.copyOf} randomises it per JVM.
 * An absent setting binds to an empty map, in which case only the repository base URL is permitted.
 *
 * <p>Only the indexed form binds, and several config shapes changed meaning relative to
 * {@code Config.getStringList} — here that can permit a post-login redirect target the legacy reader
 * rejected, which is an open redirect. {@link IndexedListConfigValidator} documents the shapes and
 * aborts startup on them, naming the key and the remedy.
 */
@ConfigurationProperties(prefix = "entrystore.auth.permitted")
public record PermittedRedirectsProperties(Map<String, String> redirects) {

	public PermittedRedirectsProperties {
		// Copy so the singleton never hands out the binder's mutable LinkedHashMap by reference.
		redirects = (redirects == null) ? Map.of() : Map.copyOf(redirects);
	}
}
