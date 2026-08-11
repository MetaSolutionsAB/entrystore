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

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.IOUtils;
import org.entrystore.repository.config.Settings;
import org.jetbrains.annotations.Nullable;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Year;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Loads, caches and renders the transactional email templates. Owns everything content-related —
 * subjects, template sources and the {@code __YEAR__} / {@code __DOMAIN__} substitutions — leaving
 * {@code EmailSender} to own transport.
 *
 * <p>The cache is per-bean, not static, so two Spring contexts in one JVM (as in the test suite) do
 * not share or overwrite each other's templates.
 *
 * <p>Population is a deliberate get / load / {@code putIfAbsent} rather than {@code computeIfAbsent}:
 * {@link #loadTemplate} can fetch over HTTP, and {@code computeIfAbsent} would hold the map's bin lock
 * for the duration of that I/O. A concurrent double-load is harmless because the result is
 * idempotent. A failed load is deliberately <em>not</em> cached, so an operator who fixes a bad
 * template path does not need a restart.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MailTemplateRenderer {

	private static final int TEMPLATE_FETCH_TIMEOUT_MS = 5_000;

	// The Spring Environment rather than @Value fields: the subject and template-path keys are
	// carried by the EmailTemplate constants, keeping the enum the single source of key names,
	// defaults and bundled resources.
	private final Environment environment;

	private final Map<EmailTemplate, String> templateCache = new ConcurrentHashMap<>();

	/** The configured subject for {@code template}, or its documented default. */
	public String subject(EmailTemplate template) {
		return environment.getProperty(template.getSubjectKey(), template.getDefaultSubject());
	}

	/**
	 * Renders {@code template} with the shared {@code __YEAR__} / {@code __DOMAIN__} substitutions plus
	 * the supplied ones, or returns null when the template cannot be loaded — the same failure signal
	 * the callers turned into a {@code false} return before.
	 *
	 * <p>Substitution values are inserted literally. Entries with a null value are skipped, leaving the
	 * placeholder in place, matching the per-placeholder null guards of the previous implementation.
	 */
	public @Nullable String render(EmailTemplate template, Map<String, String> substitutions) {
		String body = resolveCached(template);
		if (body == null) {
			log.error("Unable to load email template for {}", template.getDescription());
			return null;
		}

		String rendered = body
				// Year.now(), not Calendar.getInstance(): the latter honours the default locale's calendar
				// system, so __YEAR__ renders as 2569 under a th-TH default rather than the Gregorian year.
				.replace("__YEAR__", Integer.toString(Year.now().getValue()))
				.replace("__DOMAIN__", resolveBaseUrlHost(environment));
		for (Map.Entry<String, String> substitution : substitutions.entrySet()) {
			if (substitution.getValue() != null) {
				// Literal replace, never replaceAll: the regex form interprets $ and \ in the
				// *replacement* string as group references and escapes, so a display name or
				// confirmation link containing either would corrupt the mail or throw.
				rendered = rendered.replace(substitution.getKey(), substitution.getValue());
			}
		}
		return rendered;
	}

	private @Nullable String resolveCached(EmailTemplate template) {
		String cached = templateCache.get(template);
		if (cached != null) {
			return cached;
		}
		String loaded = resolveTemplate(template);
		if (loaded == null) {
			return null;
		}
		templateCache.putIfAbsent(template, loaded);
		return loaded;
	}

	/**
	 * Loads the template from the configured path, or the bundled classpath template when no path is
	 * configured. Returns null when the chosen source cannot be loaded — a configured path that fails
	 * to load does not fall back to the classpath template.
	 */
	private @Nullable String resolveTemplate(EmailTemplate template) {
		String templatePath = environment.getProperty(template.getTemplatePathKey());
		if (templatePath != null) {
			return loadTemplate(templatePath);
		}
		return loadClasspathTemplate(template.getClasspathResource());
	}

	/**
	 * Resolves the host of the configured base URL for {@code __DOMAIN__} substitution, returning an
	 * empty string when the base URL is unset, blank, has no host (e.g., a schemeless value such as
	 * {@code store}), or is not a valid URI. Avoids the {@code URI.create(null)} / {@code getHost()}-is-null
	 * NPEs that previously escaped the email helpers; a misconfigured (but non-blank) base URL is logged.
	 */
	static String resolveBaseUrlHost(Environment environment) {
		String baseUrl = environment.getProperty(Settings.BASE_URL);
		if (baseUrl == null || baseUrl.isBlank()) {
			return "";
		}
		try {
			String host = URI.create(baseUrl).getHost();
			if (host == null) {
				log.warn("Configured base URL '{}' has no host; '__DOMAIN__' in emails will be empty", baseUrl);
				return "";
			}
			return host;
		} catch (IllegalArgumentException e) {
			log.warn("Configured base URL '{}' is not a valid URI; '__DOMAIN__' in emails will be empty", baseUrl);
			return "";
		}
	}

	private static @Nullable String loadClasspathTemplate(String resourceName) {
		try (InputStream is = Thread.currentThread().getContextClassLoader().getResourceAsStream(resourceName)) {
			if (is == null) {
				// Previously silent, which made a bundled template missing from the jar indistinguishable
				// from a rendering problem. This is the default-configuration path, so it breaks mail for
				// every deployment that does not set an explicit template path.
				log.error("Bundled email template {} is missing from the classpath", resourceName);
				return null;
			}
			return IOUtils.toString(is, StandardCharsets.UTF_8);
		} catch (IOException e) {
			// Stack trace kept, matching loadTemplate: the two failure modes are diagnosed the same way.
			log.error("Failed to load classpath template {}", resourceName, e);
			return null;
		}
	}

	private static @Nullable String loadTemplate(String url) {
		log.debug("Loading template from {}", url);
		try (InputStream is = openTemplate(url)) {
			return IOUtils.toString(is, StandardCharsets.UTF_8);
		} catch (IOException | URISyntaxException e) {
			log.error("Failed to load email template from {}", url, e);
			return null;
		}
	}

	private static InputStream openTemplate(String url) throws IOException, URISyntaxException {
		if (!url.startsWith("http://") && !url.startsWith("https://")) {
			return Files.newInputStream(new File(url).toPath());
		}
		// URL.openStream() inherits the JVM default of no timeout at all, so an unreachable template host
		// would pin one thread per send until the pool is exhausted — and a failed load is deliberately
		// not cached, so every send retries it. These bound the connect and each individual read; note
		// that setReadTimeout is a per-read inactivity timeout, not a deadline for the whole fetch, so a
		// server that drips a byte at a time still holds the thread.
		URLConnection connection = new URI(url).toURL().openConnection();
		connection.setConnectTimeout(TEMPLATE_FETCH_TIMEOUT_MS);
		connection.setReadTimeout(TEMPLATE_FETCH_TIMEOUT_MS);
		return connection.getInputStream();
	}
}
