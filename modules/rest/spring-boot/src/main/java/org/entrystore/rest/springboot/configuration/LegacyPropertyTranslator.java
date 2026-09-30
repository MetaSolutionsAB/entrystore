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

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.logging.Log;
import org.entrystore.repository.config.Settings;
import org.entrystore.rest.springboot.security.SamlAcsRequestMatcher;
import org.opensaml.saml.saml2.core.NameIDType;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertyName;
import org.springframework.boot.context.properties.source.ConfigurationPropertySource;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.context.properties.source.ConfigurationPropertyState;
import org.springframework.boot.logging.DeferredLogFactory;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.MapPropertySource;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.IntStream;
import java.util.stream.StreamSupport;

/**
 * Translates EntryStore 5.x SAML, CAS and HTTP Basic settings that 6.x no longer reads into their 6.x keys, so a
 * 5.x configuration keeps working unchanged. Values are translated as 5.x read them, and each translation is
 * logged at WARN with the 6.x key to set instead. The translations go into one property source of the lowest
 * precedence, so a 6.x key that is set explicitly, in any spelling, always wins.
 *
 * <p>SAML registrations are emitted only while SAML is enabled (Spring Boot would otherwise build its own
 * repository for them), and only for IdP ids without 6.x registration keys.
 *
 * <p>Adding a future rename that keeps its value: append it to {@link #RENAMED_KEYS}.
 */
public final class LegacyPropertyTranslator implements EnvironmentPostProcessor, Ordered {

	static final String PROPERTY_SOURCE_NAME = "entrystore-legacy-translations";

	// Legacy key -> 6.x key, the value carried over unchanged.
	private static final Map<String, String> RENAMED_KEYS = Map.of(
			Settings.AUTH_CAS_SERVER_LOGIN_URL, "entrystore.auth.cas.server.url-login");
	// Legacy enable key -> the (lowercase) values with which 5.x enabled the feature; its 6.x key adds ".enabled".
	private static final Map<String, Set<String>> ENABLE_KEYS = Map.of(
			"entrystore.auth.saml", Set.of("on", "new"),
			"entrystore.auth.cas", Set.of("on"),
			"entrystore.auth.http-basic", Set.of("on", "true"));

	// 5.x per-IdP key suffix (entrystore.auth.saml.idp.<id>.<suffix>) -> the 5.x single-IdP key for the same setting.
	private static final Map<String, String> SINGLE_IDP_KEYS = Map.of(
			"metadata.url", Settings.AUTH_SAML_LEGACY_IDP_METADATA_URL,
			"relying-party-id", Settings.AUTH_SAML_LEGACY_RELYING_PARTY_ID,
			"redirect-method", Settings.AUTH_SAML_LEGACY_REDIRECT_METHOD,
			"metadata.max-age", Settings.AUTH_SAML_LEGACY_IDP_METADATA_MAXAGE,
			"user-auto-provisioning", Settings.AUTH_SAML_LEGACY_USER_AUTO_PROVISIONING);
	// 5.x per-IdP key suffix -> the key under spring.security.saml2.relyingparty.registration.<id>, value unchanged.
	private static final Map<String, String> REGISTRATION_KEYS = Map.of(
			"metadata.url", "assertingparty.metadata-uri",
			"relying-party-id", "entity-id");

	private static final String IDP_KEY_TEMPLATE = "entrystore.auth.saml.idp.%s.%s";
	private static final String REGISTRATION = "spring.security.saml2.relyingparty.registration.%s";
	private static final Pattern IDP_KEY = Pattern.compile("entrystore\\.auth\\.saml\\.idp\\.([^.\\[\\]]+)\\.(.+)");

	private final Log log;

	public LegacyPropertyTranslator(DeferredLogFactory logFactory) {
		this.log = logFactory.getLog(LegacyPropertyTranslator.class);
	}

	@Override
	public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
		var translated = new LinkedHashMap<String, Object>();
		var translation = new Translation(environment, log, translated);
		ENABLE_KEYS.forEach((key, enablingValues) -> translation.transform(key, key + ".enabled",
				value -> Boolean.toString(enablingValues.contains(value.toLowerCase(Locale.ROOT)))));
		RENAMED_KEYS.forEach(translation::rename);
		translation.saml();
		translation.list(Settings.AUTH_SAML_REDIRECT_DOMAIN_WHITELIST);
		translation.idpIds(suffix -> suffix.startsWith("domains."))
				.forEach(id -> translation.list(IDP_KEY_TEMPLATE.formatted(id, "domains")));
		if (!translated.isEmpty()) {
			environment.getPropertySources().addLast(new MapPropertySource(PROPERTY_SOURCE_NAME, translated));
		}
	}

	// Must run after ConfigDataEnvironmentPostProcessor so entrystore.properties (imported via
	// spring.config.import) is part of the Environment.
	@Override
	public int getOrder() {
		return Ordered.LOWEST_PRECEDENCE - 2;
	}

	private record Translation(ConfigurableEnvironment environment, Log log, Map<String, Object> translated) {

		private void saml() {
			boolean enabled = Binder.get(environment).bind(Settings.AUTH_SAML_ENABLED, Boolean.class)
					.orElseGet(() -> "true".equals(translated.get(Settings.AUTH_SAML_ENABLED)));
			if (!enabled) {
				return;
			}
			String selector = StringUtils.lowerCase(legacy("entrystore.auth.saml"), Locale.ROOT);
			boolean singleIdpKeys = legacy(SINGLE_IDP_KEYS.get("relying-party-id")) != null
					|| legacy(SINGLE_IDP_KEYS.get("metadata.url")) != null;
			if ("on".equals(selector) || (!"new".equals(selector) && singleIdpKeys)) {
				singleIdp();
			} else {
				multiIdp();
			}
		}

		private void singleIdp() {
			String id = Objects.requireNonNullElse(legacy(Settings.AUTH_SAML_DEFAULT_IDP), "default");
			if (!registration(id, SINGLE_IDP_KEYS::get, UnaryOperator.identity())) {
				return;
			}
			rename(SINGLE_IDP_KEYS.get("metadata.max-age"), IDP_KEY_TEMPLATE.formatted(id, "metadata.max-age"));
			String provisioningKey = IDP_KEY_TEMPLATE.formatted(id, "user-auto-provisioning");
			transform(SINGLE_IDP_KEYS.get("user-auto-provisioning"), provisioningKey,
					value -> Boolean.toString("on".equalsIgnoreCase(value)));
			// Always set: it also makes the IdP known to the domain routing, which then sends every login to it.
			imply(provisioningKey, "false");
			imply(Settings.AUTH_SAML_DEFAULT_IDP, id);
		}

		private void multiIdp() {
			String bareIdps = legacy(Settings.AUTH_SAML_IDPS);
			Collection<String> ids = bareIdps != null ? List.of(bareIdps) : numberedValues(Settings.AUTH_SAML_IDPS);
			if (ids.isEmpty()) {
				ids = idpIds(suffix -> suffix.equals("metadata.url"));
			}
			for (String id : ids) {
				registration(id, suffix -> IDP_KEY_TEMPLATE.formatted(id, suffix), acsUrl -> acsUrl
						+ (acsUrl.contains("?") ? "&" : "?") + SamlAcsRequestMatcher.LEGACY_IDP_PARAMETER + "=" + id);
			}
		}

		/**
		 * Emits the relying-party registration 5.x built for one IdP, reading the 5.x key of each per-IdP setting
		 * through {@code legacyKey} (a per-IdP suffix to its key); returns whether it did.
		 */
		private boolean registration(String id, UnaryOperator<String> legacyKey, UnaryOperator<String> acsLocation) {
			String metadataUrlKey = legacyKey.apply("metadata.url");
			if (legacy(metadataUrlKey) == null) {
				log.warn("5.x SAML IdP '" + id + "' is not translated: " + metadataUrlKey + " is not set.");
				return false;
			}
			String registration = REGISTRATION.formatted(id);
			var registrationName = ConfigurationPropertyName.adapt(registration, '.');
			if (anySource(source -> source.containsDescendantOf(registrationName) == ConfigurationPropertyState.PRESENT)) {
				log.warn("5.x SAML settings for IdP '" + id + "' are ignored because " + registration + ".* is set.");
				return false;
			}
			REGISTRATION_KEYS.forEach((suffix, key) -> rename(legacyKey.apply(suffix), registration + "." + key));
			transform(Settings.AUTH_SAML_LEGACY_ASSERTION_CONSUMER_SERVICE_URL, registration + ".acs.location",
					acsLocation);
			String bindingKey = registration + ".assertingparty.singlesignon.binding";
			// 5.x sent the authentication request with GET unless redirect-method was post.
			transform(legacyKey.apply("redirect-method"), bindingKey,
					value -> "post".equalsIgnoreCase(value) ? "post" : "redirect");
			imply(bindingKey, "redirect");
			// 5.x always asked for this format; without one, an IdP may return a NameID that names another user.
			imply(registration + ".name-id-format", NameIDType.UNSPECIFIED);
			// 5.x never signed authentication requests, and some IdPs' metadata asks for signed ones.
			imply(registration + ".assertingparty.singlesignon.sign-request", "false");
			return true;
		}

		/**
		 * Translates a list in the 5.x numbering ({@code key.1}, {@code key.2}, … up to the first gap), which 6.x
		 * binds as an empty list, into {@code key[0]}, {@code key[1]}, …. A bare value or an entry at index 0 is
		 * left to Spring, which binds it. A bare value, even an empty one, overrides numbered entries, as in 5.x,
		 * and a WARN says so.
		 */
		private void list(String key) {
			if (isSet(key)) {
				if (environment.containsProperty(key + ".1")) {
					log.warn("EntryStore property '" + key + ".1', '" + key + ".2', ... is ignored because '" + key
							+ "' is set.");
				}
				return;
			}
			if (isSet(key + "[0]")) {
				return;
			}
			List<String> values = numberedValues(key);
			if (values.isEmpty()) {
				return;
			}
			for (int i = 0; i < values.size(); i++) {
				translated.put(key + "[" + i + "]", values.get(i));
			}
			log.warn("EntryStore property '" + key + ".1', '" + key + ".2', ... uses the 5.x numbering and was applied "
					+ "as '" + key + "=" + String.join(",", values) + "'; set that key instead.");
		}

		/** The ids of {@code entrystore.auth.saml.idp.<id>.<suffix>} keys whose suffix matches. */
		private Set<String> idpIds(Predicate<String> suffix) {
			Set<String> ids = new TreeSet<>();
			for (var source : environment.getPropertySources()) {
				if (source instanceof EnumerablePropertySource<?> enumerable) {
					for (String name : enumerable.getPropertyNames()) {
						Matcher matcher = IDP_KEY.matcher(name);
						if (matcher.matches() && suffix.test(matcher.group(2))) {
							ids.add(matcher.group(1));
						}
					}
				}
			}
			return ids;
		}

		private List<String> numberedValues(String key) {
			return IntStream.iterate(1, i -> i + 1)
					.mapToObj(i -> environment.getProperty(key + "." + i))
					.takeWhile(Objects::nonNull)
					.toList();
		}

		private void rename(String legacyKey, String newKey) {
			transform(legacyKey, newKey, UnaryOperator.identity());
		}

		/** Maps a set {@code legacyKey} to {@code newKey}, unless the transformation yields null or newKey is set. */
		private void transform(String legacyKey, String newKey, UnaryOperator<String> transformation) {
			String legacyValue = legacy(legacyKey);
			String value = legacyValue == null ? null : transformation.apply(legacyValue);
			if (value == null) {
				return;
			}
			if (isSet(newKey)) {
				log.warn("EntryStore property '" + legacyKey + "' is deprecated and ignored because '" + newKey
						+ "' is set.");
				return;
			}
			translated.put(newKey, value);
			log.warn("EntryStore property '" + legacyKey + "' is deprecated since 6.0 and was applied as '" + newKey
					+ "=" + value + "'; set that key instead.");
		}

		/** Sets a key that 5.x behaviour implies, unless it is set explicitly or by a translation. */
		private void imply(String key, String value) {
			if (!isSet(key)) {
				translated.putIfAbsent(key, value);
			}
		}

		private String legacy(String key) {
			return StringUtils.stripToNull(environment.getProperty(key));
		}

		private boolean isSet(String key) {
			var name = ConfigurationPropertyName.adapt(key, '.');
			return anySource(source -> source.getConfigurationProperty(name) != null);
		}

		private boolean anySource(Predicate<ConfigurationPropertySource> test) {
			return StreamSupport.stream(ConfigurationPropertySources.get(environment).spliterator(), false).anyMatch(test);
		}
	}
}
