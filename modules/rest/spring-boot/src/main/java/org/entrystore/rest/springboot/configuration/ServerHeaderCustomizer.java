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

import lombok.extern.slf4j.Slf4j;
import org.entrystore.impl.RepositoryManagerImpl;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.server.ConfigurableWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.function.Supplier;
import java.util.stream.Collectors;

@Slf4j
@Component
public class ServerHeaderCustomizer implements WebServerFactoryCustomizer<ConfigurableWebServerFactory> {

	/**
	 * Controls how much of the EntryStore version appears in the {@code Server}
	 * response header. Naming follows Apache httpd's {@code ServerTokens}
	 * (MAJOR = "1 segment", MINOR = "2 segments") — NOT semver ordering.
	 */
	enum VersionPrecision {
		/** Full version, e.g. {@code 6.0-SNAPSHOT}. */                 FULL(-1),
		/** Major + minor, e.g. {@code 6.0}. */                         MINOR(2),
		/** Major only, e.g. {@code 6}. */                              MAJOR(1),
		/** Suppress version entirely, header is {@code EntryStore}. */ NONE(0);

		final int segments;

		VersionPrecision(int segments) {
			this.segments = segments;
		}
	}

	@Value("${entrystore.http.header.server:}")
	private String configuredServerHeader;

	@Value("${entrystore.http.header.server.version-precision:full}")
	private VersionPrecision versionPrecision;

	@Override
	public void customize(ConfigurableWebServerFactory factory) {
		String value = resolveServerHeader(configuredServerHeader, versionPrecision, RepositoryManagerImpl::getVersion);
		factory.setServerHeader(value);
		log.info("Server response header set to: {}", value);
	}

	static String resolveServerHeader(String override, VersionPrecision precision, Supplier<String> versionSupplier) {
		if (override != null && !override.isBlank()) {
			return override;
		}
		try {
			return composeDefault(versionSupplier.get(), precision);
		} catch (RuntimeException e) {
			// Server header is non-essential to startup; degrade gracefully so a version-loading
			// glitch never prevents Jetty from coming up.
			log.error("Failed to compose default Server header (precision={}), falling back to 'EntryStore'",
					precision, e);
			return "EntryStore";
		}
	}

	static String composeDefault(String rawVersion, VersionPrecision precision) {
		String suffix = truncateVersion(rawVersion, precision);
		return suffix.isEmpty() ? "EntryStore" : "EntryStore/" + suffix;
	}

	static String truncateVersion(String version, VersionPrecision precision) {
		if (version == null || version.isBlank()) {
			return "";
		}
		return switch (precision) {
			case FULL -> version;
			case NONE -> "";
			case MAJOR, MINOR -> keepNumericSegments(version, precision.segments);
		};
	}

	// Strip Maven qualifier (e.g. "-SNAPSHOT", "+build.1") before splitting so segments are digit-only.
	private static String keepNumericSegments(String version, int segments) {
		return Arrays.stream(version.replaceAll("[-+].*$", "").split("\\."))
				.limit(segments)
				.map(s -> s.replaceAll("[^\\d].*", ""))
				.filter(s -> !s.isEmpty())
				.collect(Collectors.joining("."));
	}
}
