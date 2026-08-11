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

package org.entrystore.rest.springboot.service;

import org.entrystore.PrincipalManager;
import org.entrystore.User;
import org.entrystore.rest.springboot.configuration.ProxyProperties;
import org.entrystore.rest.springboot.configuration.ProxyPropertiesFixture;
import org.entrystore.rest.springboot.model.dto.ProxyResponse;
import org.entrystore.rest.springboot.model.exception.CustomResponseException;
import org.entrystore.rest.springboot.model.exception.ForbiddenException;
import org.entrystore.rest.springboot.security.SsrfSafeHttpClient;
import org.entrystore.rest.springboot.security.SsrfValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.util.unit.DataSize;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProxyServiceTest {

	@Mock
	private PrincipalManager principalManager;

	@Mock
	private ContextService contextService;

	@Mock
	private SsrfValidator ssrfValidator;

	@Mock
	private User guestUser;

	private ProxyService service;

	@BeforeEach
	void setUp() {
		// A real SsrfSafeHttpClient over the mocked validator keeps the fetchUrl tests exercising
		// the actual redirect-following and error-mapping logic.
		service = new ProxyService(principalManager, contextService, ssrfValidator,
				new SsrfSafeHttpClient(ssrfValidator, ProxyPropertiesFixture.defaults()),
				ProxyPropertiesFixture.defaults());
		service.setWhitelistAnon(Set.of());
	}

	@Test
	void init_readsTheAnonymousWhitelistNotTheLocalOne() {
		// The only place anonymousWhitelist() is consumed, and every other test here bypasses init() via
		// setWhitelistAnon. Reading localWhitelist() instead would silently let guests proxy to every host
		// exempted from the SSRF blacklist — localhost in the IT deployment — with nothing else failing.
		var properties = ProxyPropertiesFixture.withWhitelists(
				new ProxyProperties.Whitelist(Map.of("1", "local.example"), Map.of("1", "guest.example")),
				null);
		var withRealProperties = new ProxyService(principalManager, contextService, ssrfValidator,
				new SsrfSafeHttpClient(ssrfValidator, ProxyPropertiesFixture.defaults()), properties);
		withRealProperties.init();

		URI guestUri = URI.create("http://example.com/_principals/resource/_guest");
		when(principalManager.getGuestUser()).thenReturn(guestUser);
		when(guestUser.getURI()).thenReturn(guestUri);
		when(principalManager.getAuthenticatedUserURI()).thenReturn(guestUri);

		assertDoesNotThrow(() -> withRealProperties.validateGlobalAccess("guest.example"));
		assertThrows(ForbiddenException.class,
				() -> withRealProperties.validateGlobalAccess("local.example"),
				"a host on the local whitelist must not become guest-reachable");
	}

	@Test
	void validateGlobalAccess_guest_hostNotWhitelisted_throwsForbidden() {
		URI guestUri = URI.create("http://example.com/_principals/resource/_guest");
		when(principalManager.getGuestUser()).thenReturn(guestUser);
		when(guestUser.getURI()).thenReturn(guestUri);
		when(principalManager.getAuthenticatedUserURI()).thenReturn(guestUri);

		assertThrows(ForbiddenException.class,
				() -> service.validateGlobalAccess("example.com"));
	}

	@Test
	void validateGlobalAccess_guest_hostWhitelisted_noException() {
		URI guestUri = URI.create("http://example.com/_principals/resource/_guest");
		when(principalManager.getGuestUser()).thenReturn(guestUser);
		when(guestUser.getURI()).thenReturn(guestUri);
		when(principalManager.getAuthenticatedUserURI()).thenReturn(guestUri);
		service.setWhitelistAnon(Set.of("wikidata.org"));

		assertDoesNotThrow(() -> service.validateGlobalAccess("wikidata.org"));
	}

	@Test
	void validateGlobalAccess_authenticatedUser_noException() {
		URI guestUri = URI.create("http://example.com/_principals/resource/_guest");
		URI userUri = URI.create("http://example.com/_principals/resource/alice");
		when(principalManager.getGuestUser()).thenReturn(guestUser);
		when(guestUser.getURI()).thenReturn(guestUri);
		when(principalManager.getAuthenticatedUserURI()).thenReturn(userUri);

		assertDoesNotThrow(() -> service.validateGlobalAccess("any-host.example.com"));
	}

	@Test
	void validateRedirectTarget_guest_globalProxy_redirectHostNotWhitelisted_throwsForbidden() throws Exception {
		String location = "http://evil-public.example.com/landing";
		SsrfValidator.ValidatedTarget redirectTarget = new SsrfValidator.ValidatedTarget(
				URI.create(location), "evil-public.example.com", InetAddress.getByName("192.0.2.1"));
		when(ssrfValidator.validateForProxy("http://evil-public.example.com/landing")).thenReturn(redirectTarget);
		URI guestUri = URI.create("http://example.com/_principals/resource/_guest");
		when(principalManager.getGuestUser()).thenReturn(guestUser);
		when(guestUser.getURI()).thenReturn(guestUri);
		when(principalManager.getAuthenticatedUserURI()).thenReturn(guestUri);
		// Only the initial upstream is whitelisted; the redirect target is not.
		service.setWhitelistAnon(Set.of("whitelisted-upstream.example.com"));

		assertThrows(ForbiddenException.class,
				() -> service.validateRedirectTarget(location, true));
	}

	@Test
	void validateRedirectTarget_guest_globalProxy_redirectHostWhitelisted_returnsTarget() throws Exception {
		String location = "http://cdn.example.com/asset";
		SsrfValidator.ValidatedTarget redirectTarget = new SsrfValidator.ValidatedTarget(
				URI.create(location), "cdn.example.com", InetAddress.getByName("192.0.2.2"));
		when(ssrfValidator.validateForProxy("http://cdn.example.com/asset")).thenReturn(redirectTarget);
		URI guestUri = URI.create("http://example.com/_principals/resource/_guest");
		when(principalManager.getGuestUser()).thenReturn(guestUser);
		when(guestUser.getURI()).thenReturn(guestUri);
		when(principalManager.getAuthenticatedUserURI()).thenReturn(guestUri);
		service.setWhitelistAnon(Set.of("whitelisted-upstream.example.com", "cdn.example.com"));

		SsrfValidator.ValidatedTarget result = service.validateRedirectTarget(location, true);

		assertEquals("cdn.example.com", result.host());
	}

	@Test
	void validateRedirectTarget_guest_contextProxy_redirectHostNotWhitelisted_noException() throws Exception {
		String location = "http://another-public.example.com/landing";
		SsrfValidator.ValidatedTarget redirectTarget = new SsrfValidator.ValidatedTarget(
				URI.create(location), "another-public.example.com", InetAddress.getByName("192.0.2.3"));
		when(ssrfValidator.validateForProxy("http://another-public.example.com/landing")).thenReturn(redirectTarget);
		// Empty anon whitelist would block a guest if it were enforced; the context path must not enforce it,
		// so validateGlobalAccess (and thus principalManager) is never consulted on this path.
		service.setWhitelistAnon(Set.of());

		SsrfValidator.ValidatedTarget result = service.validateRedirectTarget(location, false);

		assertEquals("another-public.example.com", result.host());
	}

	@Test
	void validateRedirectTarget_authenticatedUser_globalProxy_redirectHostNotWhitelisted_noException() throws Exception {
		String location = "http://public.example.com/landing";
		SsrfValidator.ValidatedTarget redirectTarget = new SsrfValidator.ValidatedTarget(
				URI.create(location), "public.example.com", InetAddress.getByName("192.0.2.4"));
		when(ssrfValidator.validateForProxy("http://public.example.com/landing")).thenReturn(redirectTarget);
		URI guestUri = URI.create("http://example.com/_principals/resource/_guest");
		URI userUri = URI.create("http://example.com/_principals/resource/alice");
		when(principalManager.getGuestUser()).thenReturn(guestUser);
		when(guestUser.getURI()).thenReturn(guestUri);
		when(principalManager.getAuthenticatedUserURI()).thenReturn(userUri);
		// Empty anon whitelist would block a guest, but an authenticated user is exempt from the anon-whitelist check.
		service.setWhitelistAnon(Set.of());

		SsrfValidator.ValidatedTarget result = service.validateRedirectTarget(location, true);

		assertEquals("public.example.com", result.host());
	}

	@Test
	void fetchUrl_upstreamBodyOverTheConfiguredCap_throws502() throws Exception {
		// No coverage existed for the response-size cap at all. The cap is enforced while streaming, so
		// the body must be rejected mid-read rather than buffered in full first — hence a body larger
		// than readWithLimit's 8192-byte buffer, and a stream that fails if read past the cap. A body
		// that fits in one buffer-fill would pass even against a readAllBytes() implementation.
		ProxyService capped = new ProxyService(principalManager, contextService, ssrfValidator,
				new SsrfSafeHttpClient(ssrfValidator, ProxyPropertiesFixture.defaults()),
				ProxyPropertiesFixture.withMaxResponseSize(DataSize.ofBytes(8192)));
		capped.setWhitelistAnon(Set.of());
		SsrfValidator.ValidatedTarget target = validatedTarget("http://upstream.example.com/big", "192.0.2.10");
		HttpURLConnection conn = mock(HttpURLConnection.class);
		when(ssrfValidator.openPinnedConnection(target.uri(), target.resolved())).thenReturn(conn);
		when(conn.getResponseCode()).thenReturn(200);
		// 64 KiB of body against an 8 KiB cap: the correct reader stops after two buffer-fills, well
		// before the 32 KiB tripwire below.
		when(conn.getInputStream()).thenReturn(failAfter(new byte[65536], 32768));

		CustomResponseException e = assertThrows(CustomResponseException.class,
				() -> capped.fetchUrl(target, null, false));

		assertEquals(HttpStatus.BAD_GATEWAY, e.getStatus());
		assertEquals("Upstream response exceeds maximum allowed size of 8192 bytes", e.getMessage());
	}

	/**
	 * A stream over {@code body} that throws once {@code limit} bytes have been handed out, so a reader
	 * that buffers the whole response before checking the cap fails rather than quietly passing.
	 */
	private static InputStream failAfter(byte[] body, int limit) {
		return new ByteArrayInputStream(body) {
			private int delivered;

			@Override
			public synchronized int read(byte[] buf, int off, int len) {
				if (delivered >= limit) {
					throw new AssertionError("read past the cap: the body was buffered in full "
							+ "instead of being rejected mid-read");
				}
				int read = super.read(buf, off, len);
				if (read > 0) {
					delivered += read;
				}
				return read;
			}
		};
	}

	@Test
	void fetchUrl_upstreamBodyExactlyAtTheCap_isReturned() throws Exception {
		// The check is strictly greater-than, so the boundary must pass.
		ProxyService capped = new ProxyService(principalManager, contextService, ssrfValidator,
				new SsrfSafeHttpClient(ssrfValidator, ProxyPropertiesFixture.defaults()),
				ProxyPropertiesFixture.withMaxResponseSize(DataSize.ofBytes(5)));
		capped.setWhitelistAnon(Set.of());
		SsrfValidator.ValidatedTarget target = validatedTarget("http://upstream.example.com/ok", "192.0.2.10");
		HttpURLConnection conn = mock(HttpURLConnection.class);
		when(ssrfValidator.openPinnedConnection(target.uri(), target.resolved())).thenReturn(conn);
		when(conn.getResponseCode()).thenReturn(200);
		when(conn.getContentType()).thenReturn("text/plain");
		when(conn.getInputStream())
				.thenReturn(new ByteArrayInputStream("hello".getBytes(StandardCharsets.UTF_8)));

		assertEquals("hello", new String(capped.fetchUrl(target, null, false).body(), StandardCharsets.UTF_8));
	}

	@Test
	void fetchUrl_redirect_followsAndRevalidatesEveryHop() throws Exception {
		SsrfValidator.ValidatedTarget first = validatedTarget("http://upstream.example.com/a", "192.0.2.10");
		SsrfValidator.ValidatedTarget second = validatedTarget("http://next.example.com/b", "192.0.2.11");
		HttpURLConnection firstConn = mock(HttpURLConnection.class);
		HttpURLConnection secondConn = mock(HttpURLConnection.class);
		when(ssrfValidator.openPinnedConnection(first.uri(), first.resolved())).thenReturn(firstConn);
		when(ssrfValidator.openPinnedConnection(second.uri(), second.resolved())).thenReturn(secondConn);
		when(firstConn.getResponseCode()).thenReturn(302);
		when(firstConn.getHeaderField("Location")).thenReturn("http://next.example.com/b");
		when(ssrfValidator.validateForProxy("http://next.example.com/b")).thenReturn(second);
		when(secondConn.getResponseCode()).thenReturn(200);
		when(secondConn.getContentType()).thenReturn("text/plain");
		when(secondConn.getInputStream())
				.thenReturn(new ByteArrayInputStream("hello".getBytes(StandardCharsets.UTF_8)));

		ProxyResponse response = service.fetchUrl(first, null, false);

		assertEquals(200, response.statusCode());
		assertEquals("hello", new String(response.body(), StandardCharsets.UTF_8));
		verify(firstConn).disconnect();
		verify(secondConn).disconnect();
	}

	@Test
	void fetchUrl_relativeLocation_resolvedAgainstCurrentUri() throws Exception {
		SsrfValidator.ValidatedTarget first = validatedTarget("http://upstream.example.com/a/b", "192.0.2.10");
		SsrfValidator.ValidatedTarget second = validatedTarget("http://upstream.example.com/moved", "192.0.2.10");
		HttpURLConnection firstConn = mock(HttpURLConnection.class);
		HttpURLConnection secondConn = mock(HttpURLConnection.class);
		when(ssrfValidator.openPinnedConnection(first.uri(), first.resolved())).thenReturn(firstConn);
		when(ssrfValidator.openPinnedConnection(second.uri(), second.resolved())).thenReturn(secondConn);
		when(firstConn.getResponseCode()).thenReturn(301);
		when(firstConn.getHeaderField("Location")).thenReturn("/moved");
		when(ssrfValidator.validateForProxy("http://upstream.example.com/moved")).thenReturn(second);
		when(secondConn.getResponseCode()).thenReturn(200);
		when(secondConn.getInputStream()).thenReturn(new ByteArrayInputStream(new byte[0]));

		ProxyResponse response = service.fetchUrl(first, null, false);

		assertEquals(200, response.statusCode());
	}

	// The redirect-loop cap, missing-Location, timeout, and IO error mappings are pinned in
	// SsrfSafeHttpClientTest; the fetchUrl tests here cover only ProxyService's own wiring
	// (per-hop validateForProxy revalidation and response-body handling).

	private static SsrfValidator.ValidatedTarget validatedTarget(String url, String ip) throws Exception {
		URI uri = URI.create(url);
		return new SsrfValidator.ValidatedTarget(uri, uri.getHost(), InetAddress.getByName(ip));
	}
}
