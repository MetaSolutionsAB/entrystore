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

import org.apache.commons.logging.Log;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.boot.logging.DeferredLogFactory;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Detects legacy {@code entrystore.*} property keys that were renamed in 6.0 and are no longer read
 * by any production consumer. Without this detector Spring Boot would silently ignore them, leaving
 * the renamed feature misconfigured on upgrade (an auth method disabled, or a SAML IdP unconfigured)
 * with no startup diagnostic. The diagnostic depends on the values present: WARN when only falsy
 * legacy values are detected; fail-fast {@link IllegalStateException} when any value is truthy, with
 * the falsy hits surfaced in the exception message rather than logged separately.
 *
 * <p>Adding a future rename: append one {@code map.put(...)} call in {@link #buildLegacyKeys()} — but
 * only if the legacy string does not also bind to a live new key (see the exclusion note in
 * {@link #buildLegacyKeys()}).
 */
public final class LegacyPropertyKeyDetector implements EnvironmentPostProcessor, Ordered {

	private static final Map<String, String> LEGACY_KEYS = buildLegacyKeys();

	private static final Set<String> TRUTHY_VALUES = Set.of("true", "on", "yes", "enabled", "1");

	private final Log log;

	public LegacyPropertyKeyDetector(DeferredLogFactory logFactory) {
		this.log = logFactory.getLog(LegacyPropertyKeyDetector.class);
	}

	@Override
	public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
		var truthyHits = new LinkedHashMap<String, String>();
		var falsyHits = new LinkedHashMap<String, String>();

		for (var entry : LEGACY_KEYS.entrySet()) {
			String value = environment.getProperty(entry.getKey());
			if (value == null) {
				continue;
			}
			if (isTruthy(value)) {
				truthyHits.put(entry.getKey(), entry.getValue());
			} else {
				falsyHits.put(entry.getKey(), entry.getValue());
			}
		}

		if (!truthyHits.isEmpty()) {
			throw new IllegalStateException(buildFailFastMessage(truthyHits, falsyHits));
		}
		for (var entry : falsyHits.entrySet()) {
			log.warn(legacyMessage(entry.getKey(), entry.getValue()));
		}
	}

	// Must run after ConfigDataEnvironmentPostProcessor so entrystore.properties
	// (imported via spring.config.import) is part of the Environment when we scan.
	@Override
	public int getOrder() {
		return Ordered.LOWEST_PRECEDENCE;
	}

	static boolean isTruthy(String value) {
		if (value == null) {
			return false;
		}
		return TRUTHY_VALUES.contains(value.trim().toLowerCase(Locale.ROOT));
	}

	// Exposed for tests; production code reads LEGACY_KEYS directly.
	static Map<String, String> legacyKeys() {
		return LEGACY_KEYS;
	}

	private static Map<String, String> buildLegacyKeys() {
		var map = new LinkedHashMap<String, String>();
		// Auth-method enable keys renamed in 6.0.
		map.put("entrystore.auth.http-basic", "entrystore.auth.http-basic.enabled");
		map.put("entrystore.auth.saml", "entrystore.auth.saml.enabled");
		map.put("entrystore.auth.cas", "entrystore.auth.cas.enabled");
		// Per-prefix SAML IdP keys whose legacy string does not also resolve to a live new key.
		// Siblings in Settings.AUTH_SAML_LEGACY_* that share a string with a current new key are
		// intentionally omitted — Spring binds those through the new shape.
		map.put("entrystore.auth.saml.relying-party-id", "entrystore.auth.saml.idp.<idp-id>.relying-party-id");
		map.put("entrystore.auth.saml.idp-metadata.url", "entrystore.auth.saml.idp.<idp-id>.metadata.url");
		map.put("entrystore.auth.saml.idp-metadata.max-age", "entrystore.auth.saml.idp.<idp-id>.metadata.max-age");
		map.put("entrystore.auth.saml.user-auto-provisioning", "entrystore.auth.saml.idp.<idp-id>.user-auto-provisioning");
		map.put("entrystore.auth.saml.redirect-method", "entrystore.auth.saml.idp.<idp-id>.redirect-method");
		return Map.copyOf(map);
	}

	private static String legacyMessage(String legacy, String replacement) {
		return "Legacy EntryStore property '" + legacy + "' is no longer read after rename in 6.0; "
				+ "rename it to '" + replacement + "'. The legacy key is silently ignored.";
	}

	private static String buildFailFastMessage(
			Map<String, String> truthyHits, Map<String, String> falsyHits) {
		var sb = new StringBuilder(
				"EntryStore startup aborted: one or more legacy property keys are set to a truthy value "
						+ "but are no longer read after rename in 6.0. Rename them to their new keys.\n");
		appendBullets(sb, truthyHits);
		if (!falsyHits.isEmpty()) {
			sb.append("Additionally, the following legacy keys are present with falsy values "
					+ "(would have produced WARNs):\n");
			appendBullets(sb, falsyHits);
		}
		return sb.toString();
	}

	private static void appendBullets(StringBuilder sb, Map<String, String> hits) {
		for (var entry : hits.entrySet()) {
			sb.append("  - '").append(entry.getKey())
					.append("' -> '").append(entry.getValue()).append("'\n");
		}
	}
}
