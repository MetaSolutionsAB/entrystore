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

/**
 * Translates deprecated authentication settings before binding, so upgrades preserve working IdPs.
 * Explicit modern settings always win; diagnostics name keys rather than potentially sensitive values.
 */
public final class LegacyPropertyKeyDetector implements EnvironmentPostProcessor, Ordered {

	private final Log log;

	public LegacyPropertyKeyDetector(DeferredLogFactory logFactory) {
		this.log = logFactory.getLog(LegacyPropertyKeyDetector.class);
	}

	@Override
	public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
		if (environment.containsProperty("entrystore.trust.x-forwarded-for")) {
			log.warn("EntryStore property 'entrystore.trust.x-forwarded-for' was removed in 6.1 and is ignored. "
					+ "The client IP is resolved from Forwarded (for=), else the leftmost X-Forwarded-For entry. "
					+ "The reverse proxy must drop client-supplied Forwarded and X-Forwarded-* headers and set "
					+ "X-Forwarded-For to the connecting client's address; without a reverse proxy, set "
					+ "server.forward-headers-strategy=none.");
		}
		new LegacyAuthProperties(environment, log::warn).translate();
	}

	@Override
	public int getOrder() {
		return Ordered.LOWEST_PRECEDENCE;
	}
}
