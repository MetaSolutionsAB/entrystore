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

import org.springframework.util.unit.DataSize;

import java.time.Duration;

/**
 * Shared {@link ProxyProperties} instances for tests of the outbound-fetch collaborators
 * ({@code SsrfValidator}, {@code SsrfSafeHttpClient}, {@code ProxyService}).
 *
 * <p>Kept in one place so that a change to the shipped defaults does not have to be mirrored across
 * three test classes, and so a test that cares about a specific limit says so explicitly.
 */
public final class ProxyPropertiesFixture {

	private ProxyPropertiesFixture() {
	}

	/** The shipped defaults, matching the {@code @DefaultValue}s on {@link ProxyProperties}. */
	public static ProxyProperties defaults() {
		return new ProxyProperties(DataSize.ofMegabytes(10), 15, Duration.ofSeconds(30), Duration.ofSeconds(60),
				null, null);
	}

	/** Defaults with the redirect cap overridden, for tests that assert the cap is honoured. */
	public static ProxyProperties withMaxRedirects(int maxRedirects) {
		ProxyProperties defaults = defaults();
		return new ProxyProperties(defaults.maxResponseSize(), maxRedirects,
				defaults.connectTimeout(), defaults.readTimeout(), defaults.whitelist(), defaults.remoteResource());
	}

	/** Defaults with the response-size cap overridden, for tests that assert the cap is honoured. */
	public static ProxyProperties withMaxResponseSize(DataSize maxResponseSize) {
		ProxyProperties defaults = defaults();
		return new ProxyProperties(maxResponseSize, defaults.maxRedirects(),
				defaults.connectTimeout(), defaults.readTimeout(), defaults.whitelist(), defaults.remoteResource());
	}

	/** Defaults with the whitelists overridden, for tests that assert whitelist behaviour. */
	public static ProxyProperties withWhitelists(ProxyProperties.Whitelist whitelist,
			ProxyProperties.RemoteResource remoteResource) {
		ProxyProperties defaults = defaults();
		return new ProxyProperties(defaults.maxResponseSize(), defaults.maxRedirects(),
				defaults.connectTimeout(), defaults.readTimeout(), whitelist, remoteResource);
	}
}
