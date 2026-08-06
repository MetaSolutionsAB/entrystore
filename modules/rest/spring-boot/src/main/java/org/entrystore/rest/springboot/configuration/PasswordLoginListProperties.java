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
 * Binding for the password-login user whitelist and blacklist
 * ({@code entrystore.auth.password.whitelist.*} / {@code entrystore.auth.password.blacklist.*}).
 *
 * <p>EntryStore expresses lists in the legacy indexed form ({@code ...whitelist.1=admin},
 * {@code ...whitelist.2=...}), which Spring's binder reads into a {@link Map} keyed by the numeric
 * suffix rather than into a {@code List}. {@code CheckUsernamePasswordFilter} consumes
 * {@code whitelist().values()} / {@code blacklist().values()}, both as membership tests — iteration
 * order is unspecified, because {@code Map.copyOf} randomises it per JVM. Do not add a consumer that
 * depends on the configured order without sorting by index first, the way
 * {@code TraversalProperties.blacklistTuples} has to.
 *
 * <p>The prefix key itself is a scalar ({@code entrystore.auth.password=on|off|whitelist}); the
 * binder tolerates a scalar at the record's own prefix — pinned by
 * {@code PasswordLoginListPropertiesTest}, because a scalar there takes a different path through the
 * binder than an absent prefix does — and it is read separately via {@code @Value} in the filter.
 * Other {@code entrystore.auth.password.*} keys (e.g. {@code .require-current-password}) are ignored
 * here as unknown fields.
 *
 * <p>Only the indexed form binds, and several config shapes changed meaning relative to
 * {@code Config.getStringList} — here that can admit a username the legacy reader ignored (whitelist) or
 * stop blocking one it blocked (blacklist). {@link IndexedListConfigValidator} documents the shapes and
 * reports them at startup.
 *
 * <p>The copy below is via {@code Map.copyOf}, which rejects null values. {@code CheckUsernamePasswordFilter}
 * relies on that: it matches with {@code equalsIgnoreCase} and no longer null-filters, so do not swap the
 * copy for {@code Collections.unmodifiableMap} or a {@code LinkedHashMap} copy.
 */
@ConfigurationProperties(prefix = "entrystore.auth.password")
public record PasswordLoginListProperties(Map<String, String> whitelist, Map<String, String> blacklist) {

	public PasswordLoginListProperties {
		// Copy so the singleton never hands out the binder's mutable LinkedHashMap by reference.
		whitelist = (whitelist == null) ? Map.of() : Map.copyOf(whitelist);
		blacklist = (blacklist == null) ? Map.of() : Map.copyOf(blacklist);
	}
}
