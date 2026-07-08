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
 * Binding for the sign-up email-domain whitelist ({@code entrystore.auth.signup.whitelist.*}).
 *
 * <p>EntryStore expresses lists in the legacy indexed form ({@code ...whitelist.1=example.com},
 * {@code ...whitelist.2=...}), which Spring's binder reads into a {@link Map} keyed by the numeric
 * suffix rather than into a {@code List}. {@code AuthService} consumes {@code whitelist().values()};
 * an absent whitelist binds to an empty map, in which case any domain is allowed to sign up.
 *
 * <p>The prefix is {@code entrystore.auth.signup} (not {@code ...signup.whitelist}) so the map binds
 * to the {@code whitelist} component; other {@code entrystore.auth.signup.*} keys are ignored here.
 *
 * <p>Only the indexed form binds. The legacy {@code Config.getStringList} also accepted a bare,
 * un-indexed value ({@code entrystore.auth.signup.whitelist=example.com}); the {@code Map} binding does
 * not, so a bare-only value fails startup with a bind error and a mixed bare+indexed value silently
 * drops the bare entry (both fail-closed — the effective whitelist is never widened). The bare form was
 * never documented in {@code entrystore.properties_example}; use the indexed form.
 */
@ConfigurationProperties(prefix = "entrystore.auth.signup")
public record SignupWhitelistProperties(Map<String, String> whitelist) {

	public SignupWhitelistProperties {
		// Copy so the singleton never hands out the binder's mutable LinkedHashMap by reference.
		whitelist = (whitelist == null) ? Map.of() : Map.copyOf(whitelist);
	}
}
