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

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.util.unit.DataSize;

import java.time.Duration;
import java.util.Set;

import static org.entrystore.rest.springboot.configuration.ProxyPropertiesFixture.withMaxRedirects;
import static org.entrystore.rest.springboot.configuration.ProxyPropertiesFixture.withMaxResponseSize;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Binds through a real context rather than constructing the record, so the prefix and every component
 * name — including the relaxed {@code remote-resource} to {@code remoteResource} mapping and the two
 * levels of nesting below it — are part of what is asserted. Constructing the record directly, as
 * {@code SsrfValidatorTest} and {@code ProxyServiceTest} do, would keep passing with a mistyped prefix,
 * and the effect of that is an empty SSRF allowlist and the compiled-in outbound-fetch limits: silently
 * no whitelisted proxy hosts, no guest-reachable hosts, and no trusted DELETE origins.
 */
class ProxyPropertiesTest {

	@Test
	void defaults_matchTheConstantsTheseKeysReplaced() {
		// These are the numbers the constants held before they became configurable, so an existing
		// deployment that sets none of these keys must see no behaviour change.
		runner().run(context -> {
			ProxyProperties proxy = context.getBean(ProxyProperties.class);
			assertEquals(10 * 1024 * 1024, proxy.maxResponseSize().toBytes());
			assertEquals(15, proxy.maxRedirects());
			assertEquals(Duration.ofSeconds(30), proxy.connectTimeout());
			assertEquals(Duration.ofSeconds(60), proxy.readTimeout());
		});
	}

	@Test
	void bindsEveryLimitKeyFromTheEnvironment() {
		// Constructing the record directly cannot catch a misspelt component; this is what pins the
		// actual key spellings and the relaxed kebab-case binding.
		runner().withPropertyValues(
				"entrystore.proxy.max-response-size=2MB",
				"entrystore.proxy.max-redirects=4",
				"entrystore.proxy.connect-timeout=7s",
				"entrystore.proxy.read-timeout=11s"
		).run(context -> {
			ProxyProperties proxy = context.getBean(ProxyProperties.class);
			assertEquals(DataSize.ofMegabytes(2), proxy.maxResponseSize());
			assertEquals(4, proxy.maxRedirects());
			assertEquals(7_000, proxy.connectTimeoutMillis());
			assertEquals(11_000, proxy.readTimeoutMillis());
		});
	}

	@Test
	void limitsAndWhitelistsBindUnderTheSamePrefixWithoutInterfering() {
		runner().withPropertyValues(
				"entrystore.proxy.max-redirects=4",
				"entrystore.proxy.whitelist.local.1=cache.internal",
				"entrystore.proxy.remote-resource.delete.whitelist.1=http://rowstore.internal:8282"
		).run(context -> {
			ProxyProperties proxy = context.getBean(ProxyProperties.class);
			assertEquals(4, proxy.maxRedirects());
			assertEquals(Set.of("cache.internal"), proxy.localWhitelist());
			assertEquals(Set.of("http://rowstore.internal:8282"), Set.copyOf(proxy.deleteWhitelist()));
		});
	}

	@Test
	void nonPositiveResponseSize_failsFastNamingTheKey() {
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
				() -> new ProxyProperties(DataSize.ofBytes(0), 15, Duration.ofSeconds(30), Duration.ofSeconds(60),
						null, null));

		assertEquals("entrystore.proxy.max-response-size must be positive, got 0B", e.getMessage());
	}

	@Test
	void responseSizeBeyondTheCeiling_failsFastRatherThanExhaustingTheHeap() {
		// The body is accumulated in an int-indexed ByteArrayOutputStream, so a cap large enough to
		// outrun the heap would throw OutOfMemoryError out of out.write before the size check could
		// fire — an unmapped 500 instead of the 502 the cap exists to produce.
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
				() -> withMaxResponseSize(DataSize.ofGigabytes(3)));

		assertEquals("entrystore.proxy.max-response-size must not exceed 512MB — the response is "
				+ "buffered in memory per request, got 3221225472B", e.getMessage());
		// The ceiling itself is legal.
		assertEquals(DataSize.ofMegabytes(512), withMaxResponseSize(DataSize.ofMegabytes(512)).maxResponseSize());
	}

	@Test
	void unsuffixedTimeout_isReadAsSecondsRatherThanMilliseconds() {
		// Without @DurationUnit, Spring's DurationStyle reads a bare number as milliseconds, so an
		// operator migrating from the deleted CONNECT_TIMEOUT_MS = 30_000 who writes 30 would get a
		// 30 ms connect timeout that passes validation and turns every outbound fetch into a 504.
		runner().withPropertyValues(
				"entrystore.proxy.connect-timeout=30",
				"entrystore.proxy.read-timeout=60"
		).run(context -> {
			ProxyProperties properties = context.getBean(ProxyProperties.class);
			assertEquals(Duration.ofSeconds(30), properties.connectTimeout());
			assertEquals(Duration.ofSeconds(60), properties.readTimeout());
		});
	}

	@Test
	void redirectCapOutsideTheRange_failsFast() {
		assertThrows(IllegalArgumentException.class, () -> withMaxRedirects(-1));
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> withMaxRedirects(100));
		assertEquals("entrystore.proxy.max-redirects must be between 0 and 50, got 100", e.getMessage());
		// Zero is legal and means "do not follow redirects at all".
		assertEquals(0, withMaxRedirects(0).maxRedirects());
	}

	@Test
	void nonPositiveTimeout_failsFastNamingTheKebabCaseKey() {
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
				() -> new ProxyProperties(DataSize.ofMegabytes(10), 15, Duration.ZERO, Duration.ofSeconds(60),
						null, null));

		assertEquals("entrystore.proxy.connect-timeout must be positive, got PT0S", e.getMessage());
	}

	@Test
	void timeoutBeyondTheCeiling_failsFastRatherThanOverflowingTheIntCast() {
		// connectTimeoutMillis() returns int because URLConnection takes int; the ceiling is what keeps
		// that cast safe.
		assertThrows(IllegalArgumentException.class,
				() -> new ProxyProperties(DataSize.ofMegabytes(10), 15, Duration.ofDays(30), Duration.ofSeconds(60),
						null, null));
		assertThrows(IllegalArgumentException.class,
				() -> new ProxyProperties(DataSize.ofMegabytes(10), 15, Duration.ofSeconds(30), Duration.ofDays(30),
						null, null));
	}

	@Test
	void localWhitelist_bindsIndexedHostsLowerCased() {
		runner().withPropertyValues(
						"entrystore.proxy.whitelist.local.1=Cache.Internal",
						"entrystore.proxy.whitelist.local.2=metadata.internal")
				.run(context -> assertEquals(Set.of("cache.internal", "metadata.internal"),
						context.getBean(ProxyProperties.class).localWhitelist()));
	}

	@Test
	void anonymousWhitelist_bindsSeparatelyFromTheLocalWhitelist() {
		runner().withPropertyValues(
						"entrystore.proxy.whitelist.local.1=local.example",
						"entrystore.proxy.whitelist.anonymous.1=guest.example")
				.run(context -> {
					ProxyProperties properties = context.getBean(ProxyProperties.class);

					assertEquals(Set.of("local.example"), properties.localWhitelist());
					assertEquals(Set.of("guest.example"), properties.anonymousWhitelist());
				});
	}

	@Test
	void deleteWhitelist_bindsThroughTheDoublyNestedRemoteResourceKey() {
		runner().withPropertyValues(
						"entrystore.proxy.remote-resource.delete.whitelist.1=http://rowstore.internal:8282",
						"entrystore.proxy.remote-resource.delete.whitelist.2=https://other.example")
				// Compared as a set: Map.copyOf randomises iteration order per JVM, and SsrfValidator
				// only ever tests membership of the parsed origins.
				.run(context -> assertEquals(
						Set.of("http://rowstore.internal:8282", "https://other.example"),
						Set.copyOf(context.getBean(ProxyProperties.class).deleteWhitelist())));
	}

	@Test
	void blankEntry_isSkippedRatherThanWhitelistingTheEmptyHost() {
		runner().withPropertyValues(
						"entrystore.proxy.whitelist.local.1=cache.internal",
						"entrystore.proxy.whitelist.local.2=   ")
				.run(context -> assertEquals(Set.of("cache.internal"),
						context.getBean(ProxyProperties.class).localWhitelist()));
	}

	@Test
	void noProxyKeysAtAll_bindsEmptyWhitelists() {
		// The nested records are absent, not just their maps, so this also covers the compact
		// constructors that instantiate them rather than leaving them null.
		runner().run(context -> {
			ProxyProperties properties = context.getBean(ProxyProperties.class);

			assertTrue(properties.localWhitelist().isEmpty());
			assertTrue(properties.anonymousWhitelist().isEmpty());
			assertTrue(properties.deleteWhitelist().isEmpty());
		});
	}

	private static ApplicationContextRunner runner() {
		return new ApplicationContextRunner().withUserConfiguration(EnableProxyProperties.class);
	}

	@EnableConfigurationProperties(ProxyProperties.class)
	static class EnableProxyProperties {
	}
}
