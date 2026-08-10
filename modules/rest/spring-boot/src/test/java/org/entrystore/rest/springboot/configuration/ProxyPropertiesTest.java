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

import static org.entrystore.rest.springboot.configuration.ProxyPropertiesFixture.withMaxRedirects;
import static org.entrystore.rest.springboot.configuration.ProxyPropertiesFixture.withMaxResponseSize;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
	void bindsEveryKeyFromTheEnvironment() {
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
	void theExistingWhitelistKeysUnderTheSamePrefixDoNotBreakBinding() {
		// entrystore.proxy.* is shared with the SSRF whitelists, which are still read through the legacy
		// Config wrapper. They must simply not bind here rather than failing startup.
		runner().withPropertyValues(
				"entrystore.proxy.whitelist.anonymous=example.com",
				"entrystore.proxy.whitelist.local=internal.example.com",
				"entrystore.proxy.remote-resource.delete.whitelist.1=http://rowstore.internal:8282"
		).run(context -> assertEquals(15, context.getBean(ProxyProperties.class).maxRedirects(),
				"the whitelist keys must not prevent ProxyProperties from binding"));
	}

	@Test
	void nonPositiveResponseSize_failsFastNamingTheKey() {
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
				() -> new ProxyProperties(DataSize.ofBytes(0), 15, Duration.ofSeconds(30), Duration.ofSeconds(60)));

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
				() -> new ProxyProperties(DataSize.ofMegabytes(10), 15, Duration.ZERO, Duration.ofSeconds(60)));

		assertEquals("entrystore.proxy.connect-timeout must be positive, got PT0S", e.getMessage());
	}

	@Test
	void timeoutBeyondTheCeiling_failsFastRatherThanOverflowingTheIntCast() {
		// connectTimeoutMillis() returns int because URLConnection takes int; the ceiling is what keeps
		// that cast safe.
		assertThrows(IllegalArgumentException.class,
				() -> new ProxyProperties(DataSize.ofMegabytes(10), 15, Duration.ofDays(30), Duration.ofSeconds(60)));
		assertThrows(IllegalArgumentException.class,
				() -> new ProxyProperties(DataSize.ofMegabytes(10), 15, Duration.ofSeconds(30), Duration.ofDays(30)));
	}

	private static ApplicationContextRunner runner() {
		return new ApplicationContextRunner().withUserConfiguration(EnableProxyProperties.class);
	}

	@EnableConfigurationProperties(ProxyProperties.class)
	static class EnableProxyProperties {
	}
}
