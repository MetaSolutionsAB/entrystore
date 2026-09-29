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
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.logging.DeferredLogFactory;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;

import java.util.Map;

/**
 * Warns about {@code entrystore.*} property keys that were removed without a replacement. Without this
 * detector Spring Boot would silently ignore them, although the operator relied on a setting that no longer
 * exists; the WARN names what applies instead. Keys that were renamed are translated by
 * {@link LegacyPropertyTranslator} instead.
 *
 * <p>Adding a future removal: append one entry to {@link #REMOVED_KEYS}.
 */
public final class LegacyPropertyKeyDetector implements EnvironmentPostProcessor, Ordered {

	// Removed key -> what applies instead.
	private static final Map<String, String> REMOVED_KEYS = Map.of(
			"entrystore.trust.x-forwarded-for",
			"The client IP used for rate limiting and logging is always resolved from the Forwarded "
					+ "(for=) header, else the leftmost X-Forwarded-For entry "
					+ "(server.forward-headers-strategy=framework). The reverse proxy must drop "
					+ "client-supplied Forwarded and X-Forwarded-* headers and set X-Forwarded-For to the "
					+ "connecting client's address; without a reverse proxy, set "
					+ "server.forward-headers-strategy=none.");

	private final Log log;

	public LegacyPropertyKeyDetector(DeferredLogFactory logFactory) {
		this.log = logFactory.getLog(LegacyPropertyKeyDetector.class);
	}

	@Override
	public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
		for (var entry : REMOVED_KEYS.entrySet()) {
			if (environment.getProperty(entry.getKey()) != null) {
				log.warn("EntryStore property '" + entry.getKey() + "' was removed in 6.1 and is ignored. "
						+ entry.getValue());
			}
		}
	}

	// Must run after ConfigDataEnvironmentPostProcessor so entrystore.properties
	// (imported via spring.config.import) is part of the Environment when we scan.
	@Override
	public int getOrder() {
		return Ordered.LOWEST_PRECEDENCE;
	}
}
