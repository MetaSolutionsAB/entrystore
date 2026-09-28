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
import org.springframework.core.env.PropertySource;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.IntStream;

/**
 * Translates EntryStore 5.x SAML, CAS and HTTP Basic settings that 6.x no longer reads into their 6.x keys, so a
 * 5.x configuration keeps working unchanged. Each translation is logged at WARN with the 6.x key to use instead.
 * The translated values go into one property source of the lowest precedence, so a 6.x key that is set
 * explicitly, in any spelling, always wins over a translation.
 *
 * <p>The translation reproduces how 5.x read each setting, not how 6.x parses the new key: the legacy enable
 * keys follow the 5.x value checks ({@code entrystore.auth.saml=on} selected the single-IdP form, {@code new}
 * the multi-IdP form, and every other value left SAML off), lists are read the way {@code Config.getStringList}
 * read them ({@code .1}, {@code .2}, … up to the first gap, and a bare value wins), and a SAML IdP is only
 * translated when it was complete enough for 5.x to load it. 5.x SAML registrations are only emitted while
 * SAML is enabled, because Spring Boot would otherwise build its own registration repository for them.
 *
 * <p>Startup aborts only for shapes that cannot be translated without guessing: both SAML forms present with
 * no {@code entrystore.auth.saml} value to choose between them, a list numbered both from {@code .0} and from
 * {@code .1}, an IdP id that cannot be a 6.x registration id, and a metadata max-age that 6.x rejects.
 *
 * <p>An {@link EnvironmentPostProcessor} rather than a bean so the translated keys are in place before any
 * condition or binding reads them. Adding a future rename that keeps its value: append it to
 * {@link #RENAMED_KEYS}.
 */
public final class LegacyPropertyTranslator implements EnvironmentPostProcessor, Ordered {

	static final String PROPERTY_SOURCE_NAME = "entrystore-legacy-translations";

	// Legacy key -> 6.x key, the value carried over unchanged.
	private static final Map<String, String> RENAMED_KEYS = Map.of(
			Settings.AUTH_CAS_SERVER_LOGIN_URL, "entrystore.auth.cas.server.url-login");

	private static final String SAML = "entrystore.auth.saml";
	// Legacy enable key -> the (lowercase) values with which 5.x enabled the feature; its 6.x key adds ".enabled".
	private static final Map<String, Set<String>> ENABLE_KEYS = Map.of(
			SAML, Set.of("on", "new"),
			"entrystore.auth.cas", Set.of("on"),
			"entrystore.auth.http-basic", Set.of("on", "true"));

	private static final List<String> SINGLE_IDP_KEYS = List.of(Settings.AUTH_SAML_LEGACY_RELYING_PARTY_ID,
			Settings.AUTH_SAML_LEGACY_IDP_METADATA_URL, Settings.AUTH_SAML_LEGACY_IDP_METADATA_MAXAGE,
			Settings.AUTH_SAML_LEGACY_REDIRECT_METHOD, Settings.AUTH_SAML_LEGACY_USER_AUTO_PROVISIONING);
	// The per-IdP keys only 5.x reads; domains, user-auto-provisioning and metadata.max-age are live 6.x keys.
	private static final Set<String> MULTI_IDP_SUFFIXES = Set.of("metadata.url", "relying-party-id", "redirect-method");
	private static final String SINGLE_IDP_DEFAULT_ID = "default";
	private static final String REGISTRATION_PREFIX = "spring.security.saml2.relyingparty.registration.";
	// A registration id is a property-map key and a URL path segment.
	private static final Pattern REGISTRATION_ID = Pattern.compile("[A-Za-z0-9_-]+");
	private static final Pattern IDP_KEY = Pattern.compile("entrystore\\.auth\\.saml\\.idp\\.([^.\\[\\]]+)\\.(.+)");

	private final Log log;

	public LegacyPropertyTranslator(DeferredLogFactory logFactory) {
		this.log = logFactory.getLog(LegacyPropertyTranslator.class);
	}

	@Override
	public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
		new Translation(environment, log).run();
	}

	// Must run after ConfigDataEnvironmentPostProcessor so entrystore.properties (imported via
	// spring.config.import) is part of the Environment.
	@Override
	public int getOrder() {
		return Ordered.LOWEST_PRECEDENCE;
	}

	/** The state of one translation run: the translated keys and the findings that abort startup. */
	private static final class Translation {

		private final ConfigurableEnvironment environment;
		private final Log log;
		private final Map<String, String> translated = new LinkedHashMap<>();
		private final List<String> findings = new ArrayList<>();
		private final List<String> propertyNames;
		// IdP id -> the suffixes of its entrystore.auth.saml.idp.<id>.* keys.
		private final Map<String, Set<String>> idpKeySuffixes;

		Translation(ConfigurableEnvironment environment, Log log) {
			this.environment = environment;
			this.log = log;
			this.propertyNames = propertyNames(environment);
			this.idpKeySuffixes = idpKeySuffixes(propertyNames);
		}

		void run() {
			ENABLE_KEYS.forEach((legacyKey, enablingValues) -> {
				String value = legacy(legacyKey);
				if (value != null) {
					translate(legacyKey, legacyKey + ".enabled",
							Boolean.toString(enablingValues.contains(value.toLowerCase(Locale.ROOT))));
				}
			});
			saml();
			RENAMED_KEYS.forEach(this::rename);
			list(Settings.AUTH_SAML_REDIRECT_DOMAIN_WHITELIST);
			idpIds(suffix -> suffix.startsWith("domains."))
					.forEach(id -> list(Settings.AUTH_SAML_IDP_DOMAINS.formatted(id)));

			if (!findings.isEmpty()) {
				var message = new StringBuilder("EntryStore startup aborted: the following 5.x settings cannot be "
						+ "translated to 6.x and must be migrated by hand.\n");
				findings.forEach(finding -> message.append("  - ").append(finding).append('\n'));
				throw new IllegalStateException(message.toString());
			}
			if (!translated.isEmpty()) {
				environment.getPropertySources().addLast(
						new MapPropertySource(PROPERTY_SOURCE_NAME, Collections.unmodifiableMap(translated)));
			}
		}

		private void saml() {
			boolean singleIdpKeys = SINGLE_IDP_KEYS.stream().anyMatch(key -> legacy(key) != null);
			boolean idpsList = legacy(Settings.AUTH_SAML_IDPS) != null
					|| propertyNames.stream().anyMatch(name -> name.startsWith(Settings.AUTH_SAML_IDPS + "."));
			SortedSet<String> multiIdpIds = idpIds(MULTI_IDP_SUFFIXES::contains);
			boolean multiIdpKeys = idpsList || !multiIdpIds.isEmpty();
			if (!singleIdpKeys && !multiIdpKeys) {
				return;
			}
			if (!samlEnabled()) {
				log.warn("5.x SAML IdP settings are present but SAML is disabled (" + Settings.AUTH_SAML_ENABLED
						+ "=false); they are not translated.");
				return;
			}
			// The form 5.x used: the one entrystore.auth.saml selected, else the one whose keys are present.
			String selector = legacy(SAML);
			boolean selectsSingle = "on".equalsIgnoreCase(selector);
			boolean selectsMulti = "new".equalsIgnoreCase(selector);
			if (!selectsSingle && !selectsMulti && singleIdpKeys && multiIdpKeys) {
				findings.add("Both the 5.x single-IdP SAML settings (" + Settings.AUTH_SAML_LEGACY_RELYING_PARTY_ID
						+ ", " + Settings.AUTH_SAML_LEGACY_IDP_METADATA_URL + ", ...) and the multi-IdP settings ("
						+ Settings.AUTH_SAML_IDPS + ", " + Settings.AUTH_SAML_IDP_METADATA_URL.formatted("<id>")
						+ ", ...) are set, and " + SAML + " (on/new) does not say which one 5.x used. Remove the "
						+ "unused settings.");
				return;
			}
			boolean singleIdp = selectsSingle || (!selectsMulti && singleIdpKeys);
			if (singleIdp && multiIdpKeys) {
				log.warn("5.x multi-IdP SAML settings (" + Settings.AUTH_SAML_IDPS + ", "
						+ Settings.AUTH_SAML_IDP_METADATA_URL.formatted("<id>") + ", ...) are ignored, as in 5.x, "
						+ "because " + SAML + "=on selects the single-IdP settings.");
			} else if (!singleIdp && singleIdpKeys) {
				log.warn("5.x single-IdP SAML settings (" + Settings.AUTH_SAML_LEGACY_RELYING_PARTY_ID + ", "
						+ Settings.AUTH_SAML_LEGACY_IDP_METADATA_URL + ", ...) are ignored, as in 5.x, because the "
						+ "multi-IdP settings are used.");
			}
			String acsUrl = legacy(Settings.AUTH_SAML_LEGACY_ASSERTION_CONSUMER_SERVICE_URL);
			if (acsUrl == null) {
				log.warn("5.x SAML IdP settings are not translated: "
						+ Settings.AUTH_SAML_LEGACY_ASSERTION_CONSUMER_SERVICE_URL + " is not set, and 5.x loaded no "
						+ "IdP without it.");
				return;
			}
			warnIfAcsPathUnmatched(acsUrl);
			if (singleIdp) {
				singleIdp(acsUrl);
			} else {
				multiIdp(acsUrl, idpsList ? listedIdpIds(multiIdpIds) : List.copyOf(multiIdpIds));
			}
		}

		private boolean samlEnabled() {
			if (isSet(Settings.AUTH_SAML_ENABLED)) {
				return Binder.get(environment).bind(Settings.AUTH_SAML_ENABLED, Boolean.class).orElse(false);
			}
			return Boolean.parseBoolean(translated.get(Settings.AUTH_SAML_ENABLED));
		}

		private void singleIdp(String acsUrl) {
			String id = Objects.requireNonNullElse(legacy(Settings.AUTH_SAML_DEFAULT_IDP), SINGLE_IDP_DEFAULT_ID);
			if (legacy(Settings.AUTH_SAML_LEGACY_RELYING_PARTY_ID) == null
					|| legacy(Settings.AUTH_SAML_LEGACY_IDP_METADATA_URL) == null) {
				log.warn("5.x single-IdP SAML settings are not translated: 5.x required both "
						+ Settings.AUTH_SAML_LEGACY_RELYING_PARTY_ID + " and "
						+ Settings.AUTH_SAML_LEGACY_IDP_METADATA_URL + ".");
				return;
			}
			if (!usableRegistration(id, Settings.AUTH_SAML_DEFAULT_IDP)) {
				return;
			}
			registration(id, Settings.AUTH_SAML_LEGACY_IDP_METADATA_URL, Settings.AUTH_SAML_LEGACY_RELYING_PARTY_ID,
					Settings.AUTH_SAML_LEGACY_REDIRECT_METHOD, acsUrl);

			String maxAgeKey = Settings.AUTH_SAML_IDP_METADATA_MAXAGE.formatted(id);
			String maxAge = legacy(Settings.AUTH_SAML_LEGACY_IDP_METADATA_MAXAGE);
			if (maxAge != null) {
				if (isValidMaxAge(maxAge)) {
					translate(Settings.AUTH_SAML_LEGACY_IDP_METADATA_MAXAGE, maxAgeKey, maxAge);
				} else {
					findings.add("'" + Settings.AUTH_SAML_LEGACY_IDP_METADATA_MAXAGE + "=" + maxAge + "' must be a "
							+ "number of seconds of at least " + SamlCustomConfiguration.Idp.Metadata.MIN_MAX_AGE_SECONDS
							+ " in 6.x; set '" + maxAgeKey + "' instead.");
				}
			}
			// Always emitted: it also makes the IdP known to the domain routing, whose wildcard default then
			// sends every login to it, as 5.x did.
			String provisioningKey = Settings.AUTH_SAML_IDP_USER_AUTO_PROVISIONING.formatted(id);
			String provisioning = legacy(Settings.AUTH_SAML_LEGACY_USER_AUTO_PROVISIONING);
			String provisioningEnabled = Boolean.toString("on".equalsIgnoreCase(provisioning));
			if (provisioning != null) {
				translate(Settings.AUTH_SAML_LEGACY_USER_AUTO_PROVISIONING, provisioningKey, provisioningEnabled);
			} else {
				imply(provisioningKey, provisioningEnabled);
			}
			imply(Settings.AUTH_SAML_DEFAULT_IDP, id);
		}

		private void multiIdp(String acsUrl, List<String> ids) {
			for (String id : ids) {
				String relyingPartyIdKey = Settings.AUTH_SAML_IDP_RELYING_PARTY_ID.formatted(id);
				String metadataUrlKey = Settings.AUTH_SAML_IDP_METADATA_URL.formatted(id);
				if (legacy(relyingPartyIdKey) == null || legacy(metadataUrlKey) == null) {
					log.warn("5.x SAML IdP '" + id + "' is not translated: 5.x required both " + relyingPartyIdKey
							+ " and " + metadataUrlKey + ", and skipped the IdP without them.");
					continue;
				}
				if (!usableRegistration(id, Settings.AUTH_SAML_IDPS)) {
					continue;
				}
				String acsWithIdp = acsUrl + (acsUrl.contains("?") ? "&" : "?")
						+ SamlAcsRequestMatcher.LEGACY_IDP_PARAMETER + "=" + id;
				registration(id, metadataUrlKey, relyingPartyIdKey, Settings.AUTH_SAML_IDP_REDIRECT_METHOD.formatted(id),
						acsWithIdp);
			}
		}

		/**
		 * The IdP ids 5.x loaded from {@code idps}: its bare value, else {@code idps.1}, {@code idps.2}, … up to
		 * the first gap. Without an {@code idps} list, which 6.0 deployments dropped after an earlier migration
		 * hint, the caller uses every id with a 5.x-only per-IdP key instead.
		 */
		private List<String> listedIdpIds(SortedSet<String> idsWithFiveXKeys) {
			SortedSet<String> numbered = IndexedKeySuffixes.of(environment, Settings.AUTH_SAML_IDPS).dottedNumeric();
			List<String> ids;
			if (legacy(Settings.AUTH_SAML_IDPS) != null) {
				ids = List.of(legacy(Settings.AUTH_SAML_IDPS));
				warnIfIgnoredEntries(Settings.AUTH_SAML_IDPS, numbered, "because the bare value is set, as in 5.x");
			} else {
				ids = numberedValues(Settings.AUTH_SAML_IDPS);
				warnIfIgnoredEntries(Settings.AUTH_SAML_IDPS, IndexedKeySuffixes.droppedByLegacyReader(numbered),
						"because 5.x stopped reading at the first missing index");
			}
			idsWithFiveXKeys.stream()
					.filter(id -> !ids.contains(id))
					.forEach(id -> log.warn("5.x SAML settings entrystore.auth.saml.idp." + id + ".* are ignored, "
							+ "as in 5.x, because '" + id + "' is not listed in " + Settings.AUTH_SAML_IDPS + "."));
			return ids;
		}

		/** Emits the relying-party registration 5.x built for one IdP. */
		private void registration(String id, String metadataUrlKey, String relyingPartyIdKey, String redirectMethodKey,
								  String acsLocation) {
			String prefix = REGISTRATION_PREFIX + id + ".";
			rename(metadataUrlKey, prefix + "assertingparty.metadata-uri");
			rename(relyingPartyIdKey, prefix + "entity-id");
			translate(Settings.AUTH_SAML_LEGACY_ASSERTION_CONSUMER_SERVICE_URL, prefix + "acs.location", acsLocation);
			// 5.x never signed authentication requests, and some IdPs' metadata asks for signed ones.
			imply(prefix + "assertingparty.singlesignon.sign-request", "false");
			String redirectMethod = legacy(redirectMethodKey);
			if (redirectMethod == null) {
				return;
			}
			switch (redirectMethod.toLowerCase(Locale.ROOT)) {
				case "get" -> translate(redirectMethodKey, prefix + "assertingparty.singlesignon.binding", "redirect");
				case "post" -> translate(redirectMethodKey, prefix + "assertingparty.singlesignon.binding", "post");
				default -> log.warn("EntryStore property '" + redirectMethodKey + "=" + redirectMethod + "' is neither "
						+ "get nor post and is ignored; the binding is taken from the IdP metadata.");
			}
		}

		/**
		 * Whether {@code id} can receive translated registration keys: it must be usable as a registration id,
		 * and no 6.x registration keys may exist for it, since a partial migration must not be overridden.
		 */
		private boolean usableRegistration(String id, String sourceKey) {
			if (!REGISTRATION_ID.matcher(id).matches()) {
				findings.add("SAML IdP id '" + id + "' (from " + sourceKey + ") cannot be a 6.x registration id; "
						+ "use letters, digits, '-' and '_' only, and change the IdP's assertion consumer service URL "
						+ "to match.");
				return false;
			}
			ConfigurationPropertyName registration = ConfigurationPropertyName.adapt(REGISTRATION_PREFIX + id, '.');
			if (anySource(source -> source.containsDescendantOf(registration) == ConfigurationPropertyState.PRESENT)) {
				log.warn("5.x SAML settings for IdP '" + id + "' are ignored because " + REGISTRATION_PREFIX + id
						+ ".* is set.");
				return false;
			}
			return true;
		}

		private void warnIfAcsPathUnmatched(String acsUrl) {
			String path;
			try {
				path = URI.create(acsUrl).getPath();
			} catch (IllegalArgumentException e) {
				path = null;
			}
			if (path == null || !path.endsWith(SamlAcsRequestMatcher.LEGACY_ACS_PATH)) {
				log.warn("The SAML assertion consumer service URL '" + acsUrl + "' ("
						+ Settings.AUTH_SAML_LEGACY_ASSERTION_CONSUMER_SERVICE_URL + ") does not end in "
						+ SamlAcsRequestMatcher.LEGACY_ACS_PATH + "; SAML responses posted to it only reach EntryStore "
						+ "if a reverse proxy maps it there.");
			}
		}

		/**
		 * Translates a list written in the 5.x numbering ({@code key.1}, {@code key.2}, …), which 6.x binds as an
		 * empty list, into the Spring-native {@code key[0]}, {@code key[1]}, … for the entries 5.x read.
		 */
		private void list(String key) {
			IndexedKeySuffixes suffixes = IndexedKeySuffixes.of(environment, key);
			SortedSet<String> environmentNumeric = suffixes.environmentNumeric();
			if (!environmentNumeric.isEmpty() && !environmentNumeric.contains("0")) {
				findings.add("'" + key + "' is set through environment variables numbered from _1 " + environmentNumeric
						+ ", which 6.x cannot bind; number them from _0 or use a single comma-separated variable.");
				return;
			}
			SortedSet<String> dotted = suffixes.dottedNumeric();
			if (dotted.isEmpty()) {
				return;
			}
			if (suffixes.hasBareValue()) {
				warnIfIgnoredEntries(key, dotted, "because the bare value is set, as in 5.x");
				return;
			}
			if (!suffixes.bracketNumeric().isEmpty() || !environmentNumeric.isEmpty()) {
				warnIfIgnoredEntries(key, dotted, "because " + key + "[n] entries are set");
				return;
			}
			if (dotted.contains("0")) {
				if (dotted.size() > 1) {
					findings.add("'" + key + "' has entries numbered from .0 and from .1 " + dotted + "; 5.x read .1 "
							+ "onwards and 6.x reads .0 onwards. Write it as a comma-separated '" + key + "' instead.");
				}
				return; // numbered from .0 only: the Spring-native form, bound as it is
			}
			List<String> values = numberedValues(key);
			for (int i = 0; i < values.size(); i++) {
				translated.put(key + "[" + i + "]", values.get(i));
			}
			if (!values.isEmpty()) {
				log.warn("EntryStore property '" + key + ".1', '" + key + ".2', ... uses the 5.x numbering, which 6.x "
						+ "no longer reads; applied as '" + key + "=" + String.join(",", values) + "'. Write it "
						+ "comma-separated.");
			}
			warnIfIgnoredEntries(key, IndexedKeySuffixes.droppedByLegacyReader(dotted),
					"because 5.x stopped reading at the first missing index");
		}

		/** {@code key.1}, {@code key.2}, … up to the first missing index, as {@code Config.getStringList} read them. */
		private List<String> numberedValues(String key) {
			return IntStream.iterate(1, i -> i + 1)
					.mapToObj(i -> environment.getProperty(key + "." + i))
					.takeWhile(Objects::nonNull)
					.toList();
		}

		private void warnIfIgnoredEntries(String key, SortedSet<String> suffixes, String reason) {
			if (!suffixes.isEmpty()) {
				log.warn("EntryStore property '" + key + "' entries " + suffixes.stream().map(s -> "." + s).toList()
						+ " are ignored " + reason + ".");
			}
		}

		/** The ids of {@code entrystore.auth.saml.idp.<id>.<suffix>} keys whose suffix matches. */
		private SortedSet<String> idpIds(Predicate<String> suffix) {
			SortedSet<String> ids = new TreeSet<>();
			idpKeySuffixes.forEach((id, suffixes) -> {
				if (suffixes.stream().anyMatch(suffix)) {
					ids.add(id);
				}
			});
			return ids;
		}

		/** Maps {@code legacyKey} to {@code newKey} with its value unchanged, when it is set. */
		private void rename(String legacyKey, String newKey) {
			String value = legacy(legacyKey);
			if (value != null) {
				translate(legacyKey, newKey, value);
			}
		}

		/** Maps {@code legacyKey} to {@code newKey}, unless {@code newKey} is set explicitly. */
		private void translate(String legacyKey, String newKey, String value) {
			if (isSet(newKey)) {
				log.warn("EntryStore property '" + legacyKey + "' is deprecated and ignored because '" + newKey
						+ "' is set.");
				return;
			}
			translated.put(newKey, value);
			log.warn("EntryStore property '" + legacyKey + "' is deprecated since 6.0 and was applied as '" + newKey
					+ "=" + value + "'; set that key instead.");
		}

		/** Sets a key that 5.x behaviour implies but no single legacy key names, unless it is set explicitly. */
		private void imply(String key, String value) {
			if (!isSet(key)) {
				translated.put(key, value);
			}
		}

		/** The value of a key as 5.x read it (only this exact spelling), or {@code null} when blank or unset. */
		private String legacy(String key) {
			return StringUtils.stripToNull(environment.getProperty(key));
		}

		/** Whether {@code key} is set in any spelling the binder accepts. */
		private boolean isSet(String key) {
			ConfigurationPropertyName name = ConfigurationPropertyName.adapt(key, '.');
			return anySource(source -> source.getConfigurationProperty(name) != null);
		}

		private boolean anySource(Predicate<ConfigurationPropertySource> test) {
			for (ConfigurationPropertySource source : ConfigurationPropertySources.get(environment)) {
				if (test.test(source)) {
					return true;
				}
			}
			return false;
		}

		private static boolean isValidMaxAge(String seconds) {
			try {
				return Long.parseLong(seconds) >= SamlCustomConfiguration.Idp.Metadata.MIN_MAX_AGE_SECONDS;
			} catch (NumberFormatException e) {
				return false;
			}
		}

		private static List<String> propertyNames(ConfigurableEnvironment environment) {
			List<String> names = new ArrayList<>();
			for (PropertySource<?> source : environment.getPropertySources()) {
				if (source instanceof EnumerablePropertySource<?> enumerable) {
					names.addAll(List.of(enumerable.getPropertyNames()));
				}
			}
			return names;
		}

		private static Map<String, Set<String>> idpKeySuffixes(List<String> propertyNames) {
			Map<String, Set<String>> suffixesById = new TreeMap<>();
			for (String name : propertyNames) {
				Matcher matcher = IDP_KEY.matcher(name);
				if (matcher.matches()) {
					suffixesById.computeIfAbsent(matcher.group(1), _ -> new TreeSet<>()).add(matcher.group(2));
				}
			}
			return suffixesById;
		}
	}
}
