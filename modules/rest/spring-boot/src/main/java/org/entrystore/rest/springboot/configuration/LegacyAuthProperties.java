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

import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.PropertySourcesPlaceholdersResolver;
import org.springframework.boot.context.properties.source.ConfigurationPropertyName;
import org.springframework.boot.context.properties.source.ConfigurationPropertySource;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.context.properties.source.IterableConfigurationPropertySource;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;

/**
 * One startup translation pass; generated settings are never persisted to the operator's configuration.
 * Legacy registrations retain unsigned AuthnRequests because 5.x configured no SP signing credentials.
 */
final class LegacyAuthProperties {

	private static final String SAML = "entrystore.auth.saml.";
	private static final String REGISTRATION = "spring.security.saml2.relyingparty.registration.";
	private static final String SOURCE = "entrystoreLegacyAuth";
	private static final Map<String, String> SAML_RENAMES = Map.of(
			"relying-party-id", "entity-id",
			"metadata.url", "assertingparty.metadata-uri",
			"redirect-method", "assertingparty.singlesignon.binding");

	private final ConfigurableEnvironment environment;
	private final Consumer<String> warn;
	private final Binder binder;
	private final List<ConfigurationPropertySource> sources = new ArrayList<>();
	private final List<ConfigurationPropertyName> names = new ArrayList<>();
	private final Map<String, Object> translated = new LinkedHashMap<>();

	LegacyAuthProperties(ConfigurableEnvironment environment, Consumer<String> warn) {
		this.environment = environment;
		this.warn = warn;
		environment.getPropertySources().remove(SOURCE);
		ConfigurationPropertySources.get(environment).forEach(source -> {
			sources.add(source);
			if (source instanceof IterableConfigurationPropertySource iterable) {
				iterable.stream().forEach(names::add);
			}
		});
		binder = Binder.get(environment);
	}

	void translate() {
		for (String method : List.of("saml", "cas", "http-basic")) {
			rename("entrystore.auth." + method, "entrystore.auth." + method + ".enabled");
		}
		rename("entrystore.auth.cas.server.url.login", "entrystore.auth.cas.server.url-login");

		Set<String> ids = new TreeSet<>();
		List<String> enumerated = list(SAML + "idps");
		if (enumerated != null) {
			ids.addAll(enumerated);
			deprecated(SAML + "idps", REGISTRATION + "<id>.*", false);
		}
		var idpPrefix = ConfigurationPropertyName.of(SAML + "idp");
		var registrationPrefix = ConfigurationPropertyName.of("spring.security.saml2.relyingparty.registration");
		for (ConfigurationPropertyName name : names) {
			if (idpPrefix.isAncestorOf(name) && name.getNumberOfElements() > 5) {
				ids.add(name.getElement(4, ConfigurationPropertyName.Form.ORIGINAL));
			} else if (registrationPrefix.isAncestorOf(name) && name.getNumberOfElements() > 6) {
				ids.add(name.getElement(5, ConfigurationPropertyName.Form.ORIGINAL));
			}
		}

		boolean single = List.of("relying-party-id", "idp-metadata.url", "idp-metadata.max-age",
				"user-auto-provisioning", "redirect-method").stream().anyMatch(key -> value(SAML + key) != null);
		String singleId = value(SAML + "default-idp");
		if (single) {
			if (singleId == null || singleId.isBlank()) {
				singleId = "legacy";
				translated.put(SAML + "default-idp", singleId);
			}
			ids.add(singleId);
		}
		for (String id : ids) {
			if (!id.matches("[A-Za-z0-9_-]+")) {
				throw new IllegalStateException("Cannot translate SAML IdP ID from '" + SAML
						+ "idps': use letters, digits, underscores or hyphens in registration IDs.");
			}
			String custom = SAML + "idp." + id + ".";
			String registration = REGISTRATION + id + ".";
			boolean legacy = enumerated != null && enumerated.contains(id);
			for (var rename : SAML_RENAMES.entrySet()) {
				legacy |= value(custom + rename.getKey()) != null;
				rename(custom + rename.getKey(), registration + rename.getValue());
			}
			if (single && id.equals(singleId)) {
				legacy = true;
				rename(SAML + "relying-party-id", registration + "entity-id");
				rename(SAML + "idp-metadata.url", registration + "assertingparty.metadata-uri");
				rename(SAML + "redirect-method", registration + "assertingparty.singlesignon.binding");
				rename(SAML + "idp-metadata.max-age", custom + "metadata.max-age");
				rename(SAML + "user-auto-provisioning", custom + "user-auto-provisioning");
			}
			String acs = value(SAML + "assertion-consumer-service.url");
			if (acs != null) {
				String target = registration + "acs.location";
				boolean overridden = hasValue(target);
				if (!overridden) {
					validateAcs(acs);
					translated.put(target, acs + (acs.contains("?") ? "&" : "?") + "idp=" + id);
				}
				deprecated(SAML + "assertion-consumer-service.url", target, overridden);
				legacy = true;
			}
			if (legacy) {
				defaultValue(registration + "assertingparty.singlesignon.binding", "redirect");
				defaultValue(registration + "assertingparty.singlesignon.sign-request", "false");
				// Ensure legacy IdPs also participate in domain routing and provisioning policy.
				defaultValue(custom + "user-auto-provisioning", "false");
			}
			normalizeList(custom + "domains");
		}
		if (ids.isEmpty() && value(SAML + "assertion-consumer-service.url") != null) {
			deprecated(SAML + "assertion-consumer-service.url", REGISTRATION + "<id>.acs.location", false);
		}
		normalizeList(SAML + "redirect-domain-whitelist");
		if (!translated.isEmpty()) {
			environment.getPropertySources().addFirst(new MapPropertySource(SOURCE, translated));
		}
	}

	private void rename(String oldKey, String newKey) {
		String value = value(oldKey);
		if (value == null) return;
		boolean overridden = hasValue(newKey);
		if (!overridden) {
			if (oldKey.endsWith("redirect-method")) {
				value = switch (value.trim().toLowerCase(Locale.ROOT)) {
					case "get", "redirect" -> "redirect";
					case "post" -> "post";
					default -> throw new IllegalStateException("Cannot translate '" + oldKey
							+ "': expected get or post; configure '" + newKey + "' explicitly.");
				};
			}
			translated.put(newKey, value);
		}
		deprecated(oldKey, newKey, overridden);
	}

	private void deprecated(String oldKey, String newKey, boolean overridden) {
		warn.accept("Deprecated EntryStore property '" + oldKey + "'; use '" + newKey + "'. "
				+ (overridden ? "The replacement takes precedence." : "Applied for backwards compatibility."));
	}

	private String value(String key) {
		return binder.bind(ConfigurationPropertyName.adapt(key, '.'), Bindable.of(String.class)).orElse(null);
	}

	private boolean hasValue(String key) {
		return translated.containsKey(key) || value(key) != null;
	}

	private void defaultValue(String key, String value) {
		if (!hasValue(key)) translated.put(key, value);
	}

	private void validateAcs(String value) {
		try {
			URI uri = URI.create(value);
			if (!uri.isAbsolute() || uri.getHost() == null || uri.getFragment() != null
					|| !List.of("http", "https").contains(uri.getScheme().toLowerCase(Locale.ROOT))) {
				throw new IllegalArgumentException();
			}
		} catch (IllegalArgumentException ex) {
			throw new IllegalStateException("Cannot translate '" + SAML + "assertion-consumer-service.url"
					+ "': expected an absolute HTTP(S) URL without a fragment.");
		}
	}

	private void normalizeList(String key) {
		List<String> values = list(key);
		if (values != null) translated.put(key, values);
	}

	/**
	 * Modern lists use source precedence. Dotted 1-based lists retain bare-value precedence and
	 * stop at the first gap, so an upgrade cannot activate previously ignored allowlist entries.
	 */
	private List<String> list(String key) {
		ConfigurationPropertyName parent = ConfigurationPropertyName.adapt(key, '.');
		for (ConfigurationPropertySource source : sources) {
			List<ConfigurationPropertyName> children = children(source, parent);
			if (source.getConfigurationProperty(parent) != null) {
				if (!children.isEmpty()) {
					warn.accept("List '" + key + "' uses its bare value; indexed entries remain ignored.");
				}
				return sourceBinder(source).bind(parent, Bindable.listOf(String.class)).orElse(List.of());
			}
			if (children.isEmpty()) continue;
			boolean env = source.getUnderlyingSource() instanceof SystemEnvironmentPropertySource;
			boolean modern = children.stream().anyMatch(name ->
					(!env && name.isLastElementIndexed()) || last(name).equals("0"));
			if (modern) {
				return sourceBinder(source).bind(parent, Bindable.listOf(String.class)).orElseThrow(() ->
						new IllegalStateException("Cannot bind list '" + key + "': use contiguous zero-based indices."));
			}
			for (ConfigurationPropertySource candidate : sources) {
				if (candidate.getConfigurationProperty(parent) != null) {
					warn.accept("List '" + key + "' retains its legacy bare-value precedence; numbered entries "
							+ "remain ignored.");
					return sourceBinder(candidate).bind(parent, Bindable.listOf(String.class)).orElse(List.of());
				}
			}
			List<String> result = new ArrayList<>();
			Set<String> consumed = new TreeSet<>();
			for (int i = 1; ; i++) {
				String suffix = Integer.toString(i);
				String item = legacyListItem(parent, suffix);
				if (item == null) break;
				result.add(item);
				consumed.add(suffix);
			}
			warn.accept("Deprecated 1-based list '" + key + "'; use a comma-separated value or zero-based [0] indices.");
			boolean ignored = sources.stream().flatMap(s -> children(s, parent).stream())
					.anyMatch(name -> !consumed.contains(last(name)));
			if (ignored) {
				warn.accept("List '" + key + "' retains the legacy stop-at-first-gap behavior; later or invalid "
						+ "indices remain ignored. Renumber contiguously to include them.");
			}
			return result.isEmpty() && key.endsWith(".domains") ? List.of("*") : List.copyOf(result);
		}
		return null;
	}

	private String legacyListItem(ConfigurationPropertyName parent, String suffix) {
		for (ConfigurationPropertySource source : sources) {
			for (ConfigurationPropertyName child : children(source, parent)) {
				if (last(child).equals(suffix)) {
					return sourceBinder(source).bind(child, Bindable.of(String.class)).orElse(null);
				}
			}
		}
		return null;
	}

	private Binder sourceBinder(ConfigurationPropertySource source) {
		return new Binder(List.of(source), new PropertySourcesPlaceholdersResolver(environment));
	}

	private static List<ConfigurationPropertyName> children(
			ConfigurationPropertySource source, ConfigurationPropertyName parent) {
		if (!(source instanceof IterableConfigurationPropertySource iterable)) return List.of();
		return iterable.stream().filter(parent::isParentOf).toList();
	}

	private static String last(ConfigurationPropertyName name) {
		return name.getLastElement(ConfigurationPropertyName.Form.ORIGINAL);
	}
}
