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
import org.entrystore.rest.springboot.model.dto.ProxyResponse;
import org.entrystore.rest.springboot.model.exception.CustomResponseException;
import org.entrystore.rest.springboot.model.exception.ForbiddenException;
import org.entrystore.rest.springboot.security.SsrfValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
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
		service = new ProxyService(principalManager, contextService, ssrfValidator);
		service.setWhitelistAnon(Set.of());
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
		URI base = URI.create("http://whitelisted-upstream.example.com/start");
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
				() -> service.validateRedirectTarget(base, location, true));
	}

	@Test
	void validateRedirectTarget_guest_globalProxy_redirectHostWhitelisted_returnsTarget() throws Exception {
		URI base = URI.create("http://whitelisted-upstream.example.com/start");
		String location = "http://cdn.example.com/asset";
		SsrfValidator.ValidatedTarget redirectTarget = new SsrfValidator.ValidatedTarget(
				URI.create(location), "cdn.example.com", InetAddress.getByName("192.0.2.2"));
		when(ssrfValidator.validateForProxy("http://cdn.example.com/asset")).thenReturn(redirectTarget);
		URI guestUri = URI.create("http://example.com/_principals/resource/_guest");
		when(principalManager.getGuestUser()).thenReturn(guestUser);
		when(guestUser.getURI()).thenReturn(guestUri);
		when(principalManager.getAuthenticatedUserURI()).thenReturn(guestUri);
		service.setWhitelistAnon(Set.of("whitelisted-upstream.example.com", "cdn.example.com"));

		SsrfValidator.ValidatedTarget result = service.validateRedirectTarget(base, location, true);

		assertEquals("cdn.example.com", result.host());
	}

	@Test
	void validateRedirectTarget_guest_contextProxy_redirectHostNotWhitelisted_noException() throws Exception {
		URI base = URI.create("http://some-context-upstream.example.com/start");
		String location = "http://another-public.example.com/landing";
		SsrfValidator.ValidatedTarget redirectTarget = new SsrfValidator.ValidatedTarget(
				URI.create(location), "another-public.example.com", InetAddress.getByName("192.0.2.3"));
		when(ssrfValidator.validateForProxy("http://another-public.example.com/landing")).thenReturn(redirectTarget);
		// Empty anon whitelist would block a guest if it were enforced; the context path must not enforce it,
		// so validateGlobalAccess (and thus principalManager) is never consulted on this path.
		service.setWhitelistAnon(Set.of());

		SsrfValidator.ValidatedTarget result = service.validateRedirectTarget(base, location, false);

		assertEquals("another-public.example.com", result.host());
	}

	@Test
	void validateRedirectTarget_authenticatedUser_globalProxy_redirectHostNotWhitelisted_noException() throws Exception {
		URI base = URI.create("http://whitelisted-upstream.example.com/start");
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

		SsrfValidator.ValidatedTarget result = service.validateRedirectTarget(base, location, true);

		assertEquals("public.example.com", result.host());
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

	@Test
	void fetchUrl_redirectWithoutLocation_throwsBadGateway() throws Exception {
		SsrfValidator.ValidatedTarget target = validatedTarget("http://upstream.example.com/a", "192.0.2.10");
		HttpURLConnection conn = mock(HttpURLConnection.class);
		when(ssrfValidator.openPinnedConnection(target.uri(), target.resolved())).thenReturn(conn);
		when(conn.getResponseCode()).thenReturn(302);
		when(conn.getHeaderField("Location")).thenReturn(null);

		CustomResponseException ex = assertThrows(CustomResponseException.class,
				() -> service.fetchUrl(target, null, false));

		assertEquals(HttpStatus.BAD_GATEWAY, ex.getStatus());
		verify(conn).disconnect();
	}

	@Test
	void fetchUrl_tooManyRedirects_throwsBadGatewayAfter16Hops() throws Exception {
		// MAX_REDIRECTS is 15: hops 0-15 all open a connection, the 16th redirect is rejected.
		SsrfValidator.ValidatedTarget target = validatedTarget("http://upstream.example.com/a", "192.0.2.10");
		HttpURLConnection conn = mock(HttpURLConnection.class);
		when(ssrfValidator.openPinnedConnection(target.uri(), target.resolved())).thenReturn(conn);
		when(conn.getResponseCode()).thenReturn(302);
		when(conn.getHeaderField("Location")).thenReturn("http://upstream.example.com/a");
		when(ssrfValidator.validateForProxy("http://upstream.example.com/a")).thenReturn(target);

		CustomResponseException ex = assertThrows(CustomResponseException.class,
				() -> service.fetchUrl(target, null, false));

		assertEquals(HttpStatus.BAD_GATEWAY, ex.getStatus());
		assertEquals("Too many redirects", ex.getMessage());
		verify(ssrfValidator, times(16)).openPinnedConnection(target.uri(), target.resolved());
	}

	@Test
	void fetchUrl_socketTimeout_throwsGatewayTimeout() throws Exception {
		SsrfValidator.ValidatedTarget target = validatedTarget("http://upstream.example.com/a", "192.0.2.10");
		HttpURLConnection conn = mock(HttpURLConnection.class);
		when(ssrfValidator.openPinnedConnection(target.uri(), target.resolved())).thenReturn(conn);
		when(conn.getResponseCode()).thenThrow(new SocketTimeoutException("read timed out"));

		CustomResponseException ex = assertThrows(CustomResponseException.class,
				() -> service.fetchUrl(target, null, false));

		assertEquals(HttpStatus.GATEWAY_TIMEOUT, ex.getStatus());
	}

	@Test
	void fetchUrl_connectException_throwsGatewayTimeout() throws Exception {
		SsrfValidator.ValidatedTarget target = validatedTarget("http://upstream.example.com/a", "192.0.2.10");
		HttpURLConnection conn = mock(HttpURLConnection.class);
		when(ssrfValidator.openPinnedConnection(target.uri(), target.resolved())).thenReturn(conn);
		when(conn.getResponseCode()).thenThrow(new ConnectException("connection refused"));

		CustomResponseException ex = assertThrows(CustomResponseException.class,
				() -> service.fetchUrl(target, null, false));

		assertEquals(HttpStatus.GATEWAY_TIMEOUT, ex.getStatus());
	}

	@Test
	void fetchUrl_ioException_throwsBadGateway() throws Exception {
		SsrfValidator.ValidatedTarget target = validatedTarget("http://upstream.example.com/a", "192.0.2.10");
		HttpURLConnection conn = mock(HttpURLConnection.class);
		when(ssrfValidator.openPinnedConnection(target.uri(), target.resolved())).thenReturn(conn);
		when(conn.getResponseCode()).thenThrow(new IOException("boom"));

		CustomResponseException ex = assertThrows(CustomResponseException.class,
				() -> service.fetchUrl(target, null, false));

		assertEquals(HttpStatus.BAD_GATEWAY, ex.getStatus());
	}

	private static SsrfValidator.ValidatedTarget validatedTarget(String url, String ip) throws Exception {
		URI uri = URI.create(url);
		return new SsrfValidator.ValidatedTarget(uri, uri.getHost(), InetAddress.getByName(ip));
	}
}
