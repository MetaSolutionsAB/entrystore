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

package org.entrystore.rest.springboot.security;

import org.entrystore.rest.springboot.configuration.ProxyProperties;
import org.entrystore.rest.springboot.configuration.ProxyPropertiesFixture;
import org.entrystore.rest.springboot.model.exception.BadRequestException;
import org.entrystore.rest.springboot.model.exception.ForbiddenException;
import org.entrystore.rest.springboot.security.SsrfValidator.Origin;
import org.entrystore.rest.springboot.security.SsrfValidator.ValidatedTarget;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.util.unit.DataSize;

import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SsrfValidatorTest {

	private SsrfValidator validator;

	@BeforeEach
	void setUp() {
		validator = new SsrfValidator(ProxyPropertiesFixture.defaults(), null);
		validator.setProxyHostWhitelist(Set.of());
		validator.setDeleteOriginWhitelist(Set.of());
		validator.setRowstoreOrigin(null);
	}

	@Test
	void init_wiresEachTrustSetToItsOwnSetting() {
		// Every other test here bypasses init() via the package-private setters, so this is the one place
		// pinning that init() reads each trust set from ITS setting: if deleteOriginWhitelist were wired
		// to localWhitelist(), every proxy-GET-whitelisted host would silently become a trusted
		// remote-resource DELETE origin — and the ITs configure neither the delete whitelist nor a
		// rowstore URL, so nothing else would fail.
		var properties = ProxyPropertiesFixture.withWhitelists(
				new ProxyProperties.Whitelist(Map.of("1", "cache.internal"), Map.of("1", "guest.example")),
				new ProxyProperties.RemoteResource(new ProxyProperties.RemoteResource.Delete(
						Map.of("1", "http://rowstore.internal:8282"))));
		var wired = new SsrfValidator(properties, "https://rowstore.example:9000/");

		wired.init();

		assertEquals(Set.of("cache.internal"), ReflectionTestUtils.getField(wired, "proxyHostWhitelist"));
		assertEquals(Set.of(new Origin("http", "rowstore.internal", 8282)),
				ReflectionTestUtils.getField(wired, "deleteOriginWhitelist"));
		assertEquals(new Origin("https", "rowstore.example", 9000),
				ReflectionTestUtils.getField(wired, "rowstoreOrigin"));
	}

	@Test
	void validateForProxy_httpScheme_noException() {
		assertDoesNotThrow(() -> validator.validateForProxy("http://example.com"));
	}

	@Test
	void validateForProxy_httpsScheme_noException() {
		assertDoesNotThrow(() -> validator.validateForProxy("https://example.com"));
	}

	@Test
	void validateForProxy_ftpScheme_throwsBadRequest() {
		assertThrows(BadRequestException.class, () -> validator.validateForProxy("ftp://example.com"));
	}

	@Test
	void validateForProxy_fileScheme_throwsBadRequest() {
		assertThrows(BadRequestException.class, () -> validator.validateForProxy("file:///etc/passwd"));
	}

	@Test
	void validateForProxy_noScheme_throwsBadRequest() {
		assertThrows(BadRequestException.class, () -> validator.validateForProxy("example.com/path"));
	}

	@Test
	void validateForProxy_malformedUrl_throwsBadRequest() {
		assertThrows(BadRequestException.class, () -> validator.validateForProxy("://bad"));
	}

	@Test
	void validateForProxy_userinfo_throwsBadRequest() {
		assertThrows(BadRequestException.class,
				() -> validator.validateForProxy("http://user:pass@example.com/path"));
	}

	@Test
	void validateForProxy_userinfoUsernameOnly_throwsBadRequest() {
		assertThrows(BadRequestException.class,
				() -> validator.validateForProxy("http://user@example.com/path"));
	}

	@Test
	void validateForProxy_publicHostname_returnsTarget() {
		ValidatedTarget target = validator.validateForProxy("http://example.com");
		assertNotNull(target.resolved());
		assertEquals("example.com", target.host());
	}

	@Test
	void validateForProxy_localhost_notWhitelisted_throwsForbidden() {
		assertThrows(ForbiddenException.class, () -> validator.validateForProxy("http://localhost"));
	}

	@Test
	void validateForProxy_localhost_whitelisted_returnsTarget() {
		validator.setProxyHostWhitelist(Set.of("localhost"));
		ValidatedTarget target = validator.validateForProxy("http://localhost");
		assertTrue(target.resolved().isLoopbackAddress());
	}

	@Test
	void validateForProxy_ipv4Literal_throwsForbidden() {
		assertThrows(ForbiddenException.class, () -> validator.validateForProxy("http://192.168.1.1"));
	}

	@Test
	void validateForProxy_numericIpv4_throwsForbidden() {
		assertThrows(ForbiddenException.class, () -> validator.validateForProxy("http://2130706433"));
	}

	@Test
	void validateForProxy_ipv6_throwsForbidden() {
		assertThrows(ForbiddenException.class, () -> validator.validateForProxy("http://[::1]/"));
	}

	@Test
	void validateForProxy_localDomain_throwsForbidden() {
		assertThrows(ForbiddenException.class, () -> validator.validateForProxy("http://myhost.local"));
	}

	@Test
	void validateForProxy_unresolvableHost_throwsForbidden() {
		assertThrows(ForbiddenException.class,
				() -> validator.validateForProxy("http://definitely-not-a-real-host-xyz123.invalid"));
	}

	@Test
	void validateForDelete_publicHostname_returnsTarget() {
		ValidatedTarget target = validator.validateForDelete("http://example.com/x");
		assertNotNull(target.resolved());
		assertEquals("example.com", target.host());
	}

	@Test
	void validateForDelete_localhost_explicitOriginWhitelist_returnsTarget() {
		validator.setDeleteOriginWhitelist(Set.of(new Origin("http", "localhost", 8282)));
		ValidatedTarget target = validator.validateForDelete("http://localhost:8282/datasets/foo");
		assertTrue(target.resolved().isLoopbackAddress());
	}

	@Test
	void validateForDelete_defaultPort_implicitInUrl_matchesExplicit80Whitelist() {
		// Whitelist holds explicit :80; request URL omits the port. Origin canonicalization
		// must collapse them so the trust check matches.
		validator.setDeleteOriginWhitelist(Set.of(new Origin("http", "localhost", 80)));
		ValidatedTarget target = validator.validateForDelete("http://localhost/x");
		assertTrue(target.resolved().isLoopbackAddress());
	}

	@Test
	void validateForDelete_defaultPort_explicitInUrl_matchesImplicitWhitelist() {
		// Symmetric: whitelist holds implicit port (parsed via parseOrigin canonicalizes to :80);
		// request URL has explicit :80.
		Origin canonical = SsrfValidator.parseOrigin("http://localhost");
		validator.setDeleteOriginWhitelist(Set.of(canonical));
		ValidatedTarget target = validator.validateForDelete("http://localhost:80/x");
		assertTrue(target.resolved().isLoopbackAddress());
	}

	@Test
	void validateForDelete_localhost_portMismatch_throwsForbidden() {
		validator.setDeleteOriginWhitelist(Set.of(new Origin("http", "localhost", 8282)));
		assertThrows(ForbiddenException.class,
				() -> validator.validateForDelete("http://localhost:22/x"));
	}

	@Test
	void validateForDelete_localhost_rowstoreAutoTrust_returnsTarget() {
		validator.setRowstoreOrigin(new Origin("http", "localhost", 8282));
		ValidatedTarget target = validator.validateForDelete("http://localhost:8282/datasets/foo");
		assertTrue(target.resolved().isLoopbackAddress());
	}

	@Test
	void validateForDelete_localhost_rowstoreOriginMismatch_throwsForbidden() {
		validator.setRowstoreOrigin(new Origin("http", "localhost", 8282));
		assertThrows(ForbiddenException.class,
				() -> validator.validateForDelete("http://localhost:22/x"));
	}

	@Test
	void validateForDelete_httpsWhitelist_skipsBlacklistButFailsAtDns() {
		// rowstore.internal does not resolve; the DNS message proves the trust check skipped the
		// blacklist (a blacklist hit would yield a different message).
		validator.setDeleteOriginWhitelist(Set.of(new Origin("https", "rowstore.internal", 8443)));
		ForbiddenException thrown = assertThrows(ForbiddenException.class,
				() -> validator.validateForDelete("https://rowstore.internal:8443/x"));
		assertTrue(thrown.getMessage().contains("cannot be resolved"));
	}

	@Test
	void validateForDelete_ipv4Literal_noTrust_throwsForbidden() {
		assertThrows(ForbiddenException.class,
				() -> validator.validateForDelete("http://127.0.0.1/x"));
	}

	@Test
	void validateForDelete_malformedUrl_throwsBadRequest() {
		assertThrows(BadRequestException.class, () -> validator.validateForDelete("://bad"));
	}

	@Test
	void parseOrigin_defaultPortNormalization_httpAndExplicit80Equal() {
		Origin a = SsrfValidator.parseOrigin("http://example.com");
		Origin b = SsrfValidator.parseOrigin("http://example.com:80");
		assertEquals(a, b);
		assertEquals(80, a.port());
	}

	@Test
	void parseOrigin_defaultPortNormalization_httpsAndExplicit443Equal() {
		Origin a = SsrfValidator.parseOrigin("https://example.com");
		Origin b = SsrfValidator.parseOrigin("https://example.com:443");
		assertEquals(a, b);
		assertEquals(443, a.port());
	}

	@Test
	void parseOrigin_schemeAndHostLowercased() {
		Origin o = SsrfValidator.parseOrigin("HTTP://Example.COM:8282/path");
		assertEquals("http", o.scheme());
		assertEquals("example.com", o.host());
		assertEquals(8282, o.port());
	}

	@Test
	void parseOrigin_explicitNonDefaultPortPreserved() {
		Origin o = SsrfValidator.parseOrigin("http://example.com:8080");
		assertEquals(8080, o.port());
	}

	@Test
	void parseOrigin_malformedInput_returnsNull() {
		assertNull(SsrfValidator.parseOrigin("not a url"));
		assertNull(SsrfValidator.parseOrigin(""));
		assertNull(SsrfValidator.parseOrigin(null));
		assertNull(SsrfValidator.parseOrigin("ftp:no-host-here"));
	}

	@Test
	void openPinnedConnection_ipv4_buildsLiteralHostUri() throws Exception {
		InetAddress ipv4 = Inet4Address.getByAddress("example.com",
				new byte[]{(byte) 93, (byte) 184, (byte) 216, (byte) 34});
		HttpURLConnection conn = validator.openPinnedConnection(
				new URI("http://example.com/path?q=1"), ipv4);
		try {
			assertEquals("93.184.216.34", conn.getURL().getHost());
			assertEquals("/path", conn.getURL().getPath());
		} finally {
			conn.disconnect();
		}
	}

	@Test
	void openPinnedConnection_ipv6_bracketedInUri() throws Exception {
		// InetAddress.getByAddress(byte[]) gives a scope-less IPv6 address;
		// using Inet6Address.getByAddress(host, bytes, scopeId) would add a `%scopeId` suffix.
		InetAddress ipv6 = InetAddress.getByAddress(
				new byte[]{0x20, 0x01, 0x0d, (byte) 0xb8, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0x01});
		HttpURLConnection conn = validator.openPinnedConnection(
				new URI("http://example.com/path"), ipv6);
		try {
			assertEquals("[2001:db8:0:0:0:0:0:1]", conn.getURL().getHost());
		} finally {
			conn.disconnect();
		}
	}

	@Test
	void openPinnedConnection_appliesEachConfiguredTimeoutToItsOwnSetting() throws Exception {
		// Distinct values, so swapping the two setters at the call site fails here rather than passing
		// on symmetry — and so a seconds/milliseconds slip in the *Millis() accessors is visible.
		SsrfValidator configured = new SsrfValidator(
				new ProxyProperties(DataSize.ofMegabytes(10), 15, Duration.ofSeconds(7), Duration.ofSeconds(11),
						null, null),
				null);
		InetAddress ipv4 = Inet4Address.getByAddress("example.com",
				new byte[]{(byte) 93, (byte) 184, (byte) 216, (byte) 34});

		HttpURLConnection conn = configured.openPinnedConnection(new URI("http://example.com/path"), ipv4);

		try {
			assertEquals(7_000, conn.getConnectTimeout());
			assertEquals(11_000, conn.getReadTimeout());
		} finally {
			conn.disconnect();
		}
	}
}
