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

import org.entrystore.rest.springboot.model.exception.CustomResponseException;
import org.entrystore.rest.springboot.configuration.ProxyPropertiesFixture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SsrfSafeHttpClientTest {

	@Mock
	private SsrfValidator ssrfValidator;

	private SsrfSafeHttpClient client;

	@BeforeEach
	void setUp() {
		client = new SsrfSafeHttpClient(ssrfValidator, ProxyPropertiesFixture.defaults());
	}

	@Test
	void execute_2xx_invokesHandlerAndDisconnects() throws Exception {
		SsrfValidator.ValidatedTarget target = validatedTarget("http://upstream.example.com/a", "192.0.2.10");
		HttpURLConnection conn = mock(HttpURLConnection.class);
		when(ssrfValidator.openPinnedConnection(target.uri(), target.resolved())).thenReturn(conn);
		when(conn.getResponseCode()).thenReturn(204);

		Integer result = client.execute(target, "DELETE", Map.of(), location -> null, (status, c) -> status);

		assertEquals(204, result);
		verify(conn).setRequestMethod("DELETE");
		verify(conn).disconnect();
	}

	@Test
	void execute_appliesRequestHeadersOnEveryHop() throws Exception {
		SsrfValidator.ValidatedTarget first = validatedTarget("http://upstream.example.com/a", "192.0.2.10");
		SsrfValidator.ValidatedTarget second = validatedTarget("http://next.example.com/b", "192.0.2.11");
		HttpURLConnection firstConn = mock(HttpURLConnection.class);
		HttpURLConnection secondConn = mock(HttpURLConnection.class);
		when(ssrfValidator.openPinnedConnection(first.uri(), first.resolved())).thenReturn(firstConn);
		when(ssrfValidator.openPinnedConnection(second.uri(), second.resolved())).thenReturn(secondConn);
		when(firstConn.getResponseCode()).thenReturn(302);
		when(firstConn.getHeaderField("Location")).thenReturn("http://next.example.com/b");
		when(firstConn.getInputStream()).thenReturn(emptyBody());
		when(secondConn.getResponseCode()).thenReturn(200);

		client.execute(first, "GET", Map.of("Accept", "text/plain"), location -> second, (status, c) -> status);

		verify(firstConn).setRequestProperty("Accept", "text/plain");
		verify(secondConn).setRequestProperty("Accept", "text/plain");
	}

	@Test
	void execute_redirect_revalidatesResolvedLocationAndPoolsDrainedHop() throws Exception {
		SsrfValidator.ValidatedTarget first = validatedTarget("http://upstream.example.com/a/b", "192.0.2.10");
		SsrfValidator.ValidatedTarget second = validatedTarget("http://upstream.example.com/moved", "192.0.2.10");
		HttpURLConnection firstConn = mock(HttpURLConnection.class);
		HttpURLConnection secondConn = mock(HttpURLConnection.class);
		when(ssrfValidator.openPinnedConnection(first.uri(), first.resolved())).thenReturn(firstConn);
		when(ssrfValidator.openPinnedConnection(second.uri(), second.resolved())).thenReturn(secondConn);
		when(firstConn.getResponseCode()).thenReturn(301);
		// relative Location must be resolved against the current URI before revalidation
		when(firstConn.getHeaderField("Location")).thenReturn("/moved");
		when(firstConn.getInputStream()).thenReturn(emptyBody());
		when(secondConn.getResponseCode()).thenReturn(200);

		String validatedLocation = client.execute(first, "GET", Map.of(),
				location -> {
					assertEquals("http://upstream.example.com/moved", location);
					return second;
				},
				(status, c) -> "http://upstream.example.com/moved");

		assertEquals("http://upstream.example.com/moved", validatedLocation);
		// The drained redirect hop goes back to the JDK keep-alive pool, so it must not be severed;
		// the final hop's body was left unconsumed by the handler, so it must be.
		verify(firstConn, never()).disconnect();
		verify(secondConn).disconnect();
	}

	@Test
	void execute_redirectHopBodyExceedsDrainLimit_seversConnectionAndStillFollows() throws Exception {
		SsrfValidator.ValidatedTarget first = validatedTarget("http://upstream.example.com/a", "192.0.2.10");
		SsrfValidator.ValidatedTarget second = validatedTarget("http://next.example.com/b", "192.0.2.11");
		HttpURLConnection firstConn = mock(HttpURLConnection.class);
		HttpURLConnection secondConn = mock(HttpURLConnection.class);
		when(ssrfValidator.openPinnedConnection(first.uri(), first.resolved())).thenReturn(firstConn);
		when(ssrfValidator.openPinnedConnection(second.uri(), second.resolved())).thenReturn(secondConn);
		when(firstConn.getResponseCode()).thenReturn(302);
		when(firstConn.getHeaderField("Location")).thenReturn("http://next.example.com/b");
		when(firstConn.getInputStream()).thenReturn(
				new ByteArrayInputStream(new byte[SsrfSafeHttpClient.MAX_REDIRECT_DRAIN_BYTES + 1]));
		when(secondConn.getResponseCode()).thenReturn(200);

		Integer result = client.execute(first, "GET", Map.of(), location -> second, (status, c) -> status);

		assertEquals(200, result);
		// An oversized hop body is not worth salvaging: the hop is severed rather than pooled, but
		// the redirect chain still proceeds.
		verify(firstConn).disconnect();
	}

	@Test
	void execute_redirectHopDrainFails_seversConnectionAndStillFollows() throws Exception {
		SsrfValidator.ValidatedTarget first = validatedTarget("http://upstream.example.com/a", "192.0.2.10");
		SsrfValidator.ValidatedTarget second = validatedTarget("http://next.example.com/b", "192.0.2.11");
		HttpURLConnection firstConn = mock(HttpURLConnection.class);
		HttpURLConnection secondConn = mock(HttpURLConnection.class);
		when(ssrfValidator.openPinnedConnection(first.uri(), first.resolved())).thenReturn(firstConn);
		when(ssrfValidator.openPinnedConnection(second.uri(), second.resolved())).thenReturn(secondConn);
		when(firstConn.getResponseCode()).thenReturn(302);
		when(firstConn.getHeaderField("Location")).thenReturn("http://next.example.com/b");
		when(firstConn.getInputStream()).thenThrow(new IOException("hop body unreadable"));
		when(secondConn.getResponseCode()).thenReturn(200);

		Integer result = client.execute(first, "GET", Map.of(), location -> second, (status, c) -> status);

		// A hop whose body cannot be drained leaves the connection in an unknown state, so it is
		// severed instead of pooled — but the failure must not abort the redirect chain.
		assertEquals(200, result);
		verify(firstConn).disconnect();
	}

	@Test
	void execute_redirectWithoutLocation_throws502() throws Exception {
		SsrfValidator.ValidatedTarget target = validatedTarget("http://upstream.example.com/a", "192.0.2.10");
		HttpURLConnection conn = mock(HttpURLConnection.class);
		when(ssrfValidator.openPinnedConnection(target.uri(), target.resolved())).thenReturn(conn);
		when(conn.getResponseCode()).thenReturn(302);
		when(conn.getHeaderField("Location")).thenReturn(null);

		CustomResponseException ex = assertThrows(CustomResponseException.class,
				() -> client.execute(target, "GET", Map.of(), location -> null, (status, c) -> status));

		assertEquals(HttpStatus.BAD_GATEWAY, ex.getStatus());
		verify(conn).disconnect();
	}

	@Test
	void execute_sixteenRedirects_throws502() throws Exception {
		// The default entrystore.proxy.max-redirects is 15, and the loop bound is inclusive: hops 0-15
		// all open a connection, then the chain is aborted.
		SsrfValidator.ValidatedTarget target = validatedTarget("http://upstream.example.com/a", "192.0.2.10");
		HttpURLConnection conn = mock(HttpURLConnection.class);
		when(ssrfValidator.openPinnedConnection(target.uri(), target.resolved())).thenReturn(conn);
		when(conn.getResponseCode()).thenReturn(302);
		when(conn.getHeaderField("Location")).thenReturn("http://upstream.example.com/a");
		// A fresh stream per hop: drainForReuse closes each one it consumes.
		when(conn.getInputStream()).thenAnswer(invocation -> emptyBody());

		CustomResponseException ex = assertThrows(CustomResponseException.class,
				() -> client.execute(target, "GET", Map.of(), location -> target, (status, c) -> status));

		assertEquals(HttpStatus.BAD_GATEWAY, ex.getStatus());
		assertEquals("Too many redirects", ex.getMessage());
		verify(ssrfValidator, times(16)).openPinnedConnection(target.uri(), target.resolved());
		// Every hop drained successfully, so each one was pooled for reuse rather than severed.
		verify(conn, never()).disconnect();
	}

	@Test
	void execute_honoursAConfiguredRedirectCap() throws Exception {
		// Behavioural coverage for entrystore.proxy.max-redirects: without this, replacing
		// proxyProperties.maxRedirects() with a literal 15 would leave every test green.
		SsrfSafeHttpClient cappedClient =
				new SsrfSafeHttpClient(ssrfValidator, ProxyPropertiesFixture.withMaxRedirects(2));
		SsrfValidator.ValidatedTarget target = validatedTarget("http://upstream.example.com/a", "192.0.2.10");
		HttpURLConnection conn = mock(HttpURLConnection.class);
		when(ssrfValidator.openPinnedConnection(target.uri(), target.resolved())).thenReturn(conn);
		when(conn.getResponseCode()).thenReturn(302);
		when(conn.getHeaderField("Location")).thenReturn("http://upstream.example.com/a");

		CustomResponseException ex = assertThrows(CustomResponseException.class,
				() -> cappedClient.execute(target, "GET", Map.of(), location -> target, (status, c) -> status));

		assertEquals("Too many redirects", ex.getMessage());
		// A cap of 2 means the initial request plus 2 hops, so 3 connections.
		verify(ssrfValidator, times(3)).openPinnedConnection(target.uri(), target.resolved());
	}

	@Test
	void execute_zeroRedirectCap_followsNoneAndAbortsOnTheFirst302() throws Exception {
		SsrfSafeHttpClient noRedirects =
				new SsrfSafeHttpClient(ssrfValidator, ProxyPropertiesFixture.withMaxRedirects(0));
		SsrfValidator.ValidatedTarget target = validatedTarget("http://upstream.example.com/a", "192.0.2.10");
		HttpURLConnection conn = mock(HttpURLConnection.class);
		when(ssrfValidator.openPinnedConnection(target.uri(), target.resolved())).thenReturn(conn);
		when(conn.getResponseCode()).thenReturn(302);
		when(conn.getHeaderField("Location")).thenReturn("http://upstream.example.com/b");

		assertThrows(CustomResponseException.class,
				() -> noRedirects.execute(target, "GET", Map.of(), location -> target, (status, c) -> status));

		verify(ssrfValidator, times(1)).openPinnedConnection(target.uri(), target.resolved());
	}

	@Test
	void execute_socketTimeout_throws504() throws Exception {
		SsrfValidator.ValidatedTarget target = validatedTarget("http://upstream.example.com/a", "192.0.2.10");
		HttpURLConnection conn = mock(HttpURLConnection.class);
		when(ssrfValidator.openPinnedConnection(target.uri(), target.resolved())).thenReturn(conn);
		when(conn.getResponseCode()).thenThrow(new SocketTimeoutException("read timed out"));

		CustomResponseException ex = assertThrows(CustomResponseException.class,
				() -> client.execute(target, "GET", Map.of(), location -> null, (status, c) -> status));

		assertEquals(HttpStatus.GATEWAY_TIMEOUT, ex.getStatus());
	}

	@Test
	void execute_connectException_throws504() throws Exception {
		SsrfValidator.ValidatedTarget target = validatedTarget("http://upstream.example.com/a", "192.0.2.10");
		HttpURLConnection conn = mock(HttpURLConnection.class);
		when(ssrfValidator.openPinnedConnection(target.uri(), target.resolved())).thenReturn(conn);
		when(conn.getResponseCode()).thenThrow(new ConnectException("connection refused"));

		CustomResponseException ex = assertThrows(CustomResponseException.class,
				() -> client.execute(target, "GET", Map.of(), location -> null, (status, c) -> status));

		assertEquals(HttpStatus.GATEWAY_TIMEOUT, ex.getStatus());
	}

	@Test
	void execute_ioException_throws502() throws Exception {
		SsrfValidator.ValidatedTarget target = validatedTarget("http://upstream.example.com/a", "192.0.2.10");
		HttpURLConnection conn = mock(HttpURLConnection.class);
		when(ssrfValidator.openPinnedConnection(target.uri(), target.resolved())).thenReturn(conn);
		when(conn.getResponseCode()).thenThrow(new IOException("boom"));

		CustomResponseException ex = assertThrows(CustomResponseException.class,
				() -> client.execute(target, "GET", Map.of(), location -> null, (status, c) -> status));

		assertEquals(HttpStatus.BAD_GATEWAY, ex.getStatus());
	}

	@Test
	void execute_handlerException_propagatesUnchanged() throws Exception {
		// A CustomResponseException thrown by the handler (e.g. delete's non-2xx mapping) must
		// keep its own status instead of being remapped by the IO catch.
		SsrfValidator.ValidatedTarget target = validatedTarget("http://upstream.example.com/a", "192.0.2.10");
		HttpURLConnection conn = mock(HttpURLConnection.class);
		when(ssrfValidator.openPinnedConnection(target.uri(), target.resolved())).thenReturn(conn);
		when(conn.getResponseCode()).thenReturn(500);

		CustomResponseException ex = assertThrows(CustomResponseException.class,
				() -> client.execute(target, "DELETE", Map.of(), location -> null,
						(status, c) -> {
							throw new CustomResponseException("upstream error " + status, HttpStatus.BAD_GATEWAY);
						}));

		assertEquals("upstream error 500", ex.getMessage());
		verify(conn).disconnect();
	}

	private static SsrfValidator.ValidatedTarget validatedTarget(String url, String ip) throws Exception {
		URI uri = URI.create(url);
		return new SsrfValidator.ValidatedTarget(uri, uri.getHost(), InetAddress.getByName(ip));
	}

	/**
	 * A redirect response's body, which the client drains to return the connection to the keep-alive
	 * pool. Real {@link HttpURLConnection#getInputStream()} never yields null on a 3xx, so every
	 * redirect-hop mock must stub it.
	 */
	private static InputStream emptyBody() {
		return new ByteArrayInputStream(new byte[0]);
	}
}
