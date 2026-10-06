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
import org.entrystore.rest.springboot.model.exception.CustomResponseException;
import org.entrystore.rest.springboot.model.exception.ForbiddenException;
import org.entrystore.rest.springboot.security.SsrfSafeHttpClient;
import org.entrystore.rest.springboot.security.SsrfValidator;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.util.unit.DataSize;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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

	private SsrfValidator.ValidatedTarget target;

	@BeforeEach
	void setUp() throws Exception {
		// A real SsrfSafeHttpClient over the mocked validator keeps the proxy tests exercising
		// the actual redirect-following and error-mapping logic.
		service = new ProxyService(principalManager, contextService, ssrfValidator,
				new SsrfSafeHttpClient(ssrfValidator, ProxyPropertiesFixture.defaults()),
				ProxyPropertiesFixture.defaults());
		service.setWhitelistAnon(Set.of());
		target = validatedTarget("http://upstream.example.com/doc", "192.0.2.10");
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
	void deleteUrl_validatorRejects_propagatesWithoutOpeningConnection() throws Exception {
		// The individual rejection reasons (blacklisted host, scheme, userinfo) are pinned in SsrfValidatorTest;
		// here only the ordering matters: validation happens before any outbound connection.
		doThrow(new ForbiddenException("Access denied: host is blacklisted"))
				.when(ssrfValidator).validateForDelete(anyString());

		assertThrows(ForbiddenException.class, () -> service.deleteUrl("http://127.0.0.1:1/x"));

		verify(ssrfValidator, never()).openPinnedConnection(any(), any());
	}

	@Test
	void deleteUrl_upstreamError_throws502WithoutUpstreamBody() throws Exception {
		SsrfValidator.ValidatedTarget target = validatedTarget("http://upstream.example.com/doc", "192.0.2.10");
		when(ssrfValidator.validateForDelete("http://upstream.example.com/doc")).thenReturn(target);
		HttpURLConnection conn = mock(HttpURLConnection.class);
		when(ssrfValidator.openPinnedConnection(target.uri(), target.resolved())).thenReturn(conn);
		when(conn.getResponseCode()).thenReturn(500);

		CustomResponseException e = assertThrows(CustomResponseException.class,
				() -> service.deleteUrl("http://upstream.example.com/doc"));

		assertEquals(HttpStatus.BAD_GATEWAY, e.getStatus());
		// The upstream body is never read, so nothing from it can leak into the message.
		assertEquals("Delete request received an error response (status 500)", e.getMessage());
		verify(conn, never()).getInputStream();
		verify(conn, never()).getErrorStream();
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
				() -> service.validateRedirectTarget(location));
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

		SsrfValidator.ValidatedTarget result = service.validateRedirectTarget(location);

		assertEquals("cdn.example.com", result.host());
	}

	@Test
	void proxy_guestRedirectedFromAWhitelistedToAnotherHost_isDeniedBeforeTheSecondHop() throws Exception {
		// Both /proxy and /{context-id}/proxy go through here, so a guest's redirect is checked on either route.
		SsrfValidator.ValidatedTarget first = validatedTarget("http://whitelisted.example.com/a", "192.0.2.10");
		SsrfValidator.ValidatedTarget second = validatedTarget("http://elsewhere.example.com/b", "192.0.2.11");
		HttpURLConnection firstConn = mock(HttpURLConnection.class);
		when(ssrfValidator.openPinnedConnection(first.uri(), first.resolved())).thenReturn(firstConn);
		when(firstConn.getResponseCode()).thenReturn(302);
		when(firstConn.getHeaderField("Location")).thenReturn("http://elsewhere.example.com/b");
		when(ssrfValidator.validateForProxy("http://elsewhere.example.com/b")).thenReturn(second);
		URI guestUri = URI.create("http://example.com/_principals/resource/_guest");
		when(principalManager.getGuestUser()).thenReturn(guestUser);
		when(guestUser.getURI()).thenReturn(guestUri);
		when(principalManager.getAuthenticatedUserURI()).thenReturn(guestUri);
		service.setWhitelistAnon(Set.of("whitelisted.example.com"));

		assertThrows(ForbiddenException.class, () -> service.proxy(first, null, false, new MockHttpServletResponse()));

		verify(ssrfValidator, never()).openPinnedConnection(second.uri(), second.resolved());
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

		SsrfValidator.ValidatedTarget result = service.validateRedirectTarget(location);

		assertEquals("public.example.com", result.host());
	}

	@Test
	void proxy_streamsTheBodyToTheClientWithoutBufferingIt() throws Exception {
		// 64 MiB through the default (unlimited) proxy. The upstream stream fails as soon as it is asked for
		// more while the client is more than 64 KiB behind, so a service that collects the body before
		// writing it fails here whatever the body size.
		long bodySize = 64L * 1024 * 1024;
		CountingResponse response = new CountingResponse();
		HttpURLConnection conn = upstream(200, bodySize);
		when(conn.getInputStream()).thenReturn(lockstepBody(bodySize, response));

		service.proxy(target, null, false, response);

		assertEquals(200, response.getStatus());
		assertEquals(bodySize, response.written);
		assertEquals(String.valueOf(bodySize), response.getHeader(HttpHeaders.CONTENT_LENGTH));
	}

	@Test
	void proxy_passesThroughStatusContentTypeAndEntityHeaders() throws Exception {
		MockHttpServletResponse response = new MockHttpServletResponse();
		HttpURLConnection conn = upstream(200, 5);
		when(conn.getContentType()).thenReturn("text/turtle; charset=UTF-8");
		// Names spelt as HttpURLConnection reports them from the JDK's own HTTP server.
		withHeaders(conn,
				"Content-encoding: gzip",
				"Content-language: sv",
				"Content-disposition: attachment; filename=\"a.ttl\"",
				"Last-modified: Tue, 06 Oct 2026 10:00:00 GMT",
				"Etag: \"v1\"",
				"Expires: Wed, 07 Oct 2026 10:00:00 GMT",
				"X-upstream-internal: secret");
		when(conn.getInputStream()).thenReturn(new ByteArrayInputStream("hello".getBytes(StandardCharsets.UTF_8)));

		service.proxy(target, null, false, response);

		assertEquals(200, response.getStatus());
		assertNull(response.getHeader("X-Upstream-Internal"));
		assertEquals("text/turtle;charset=UTF-8", response.getContentType().replace(" ", ""));
		assertEquals("5", response.getHeader(HttpHeaders.CONTENT_LENGTH));
		assertEquals("gzip", response.getHeader(HttpHeaders.CONTENT_ENCODING));
		assertEquals("sv", response.getHeader(HttpHeaders.CONTENT_LANGUAGE));
		assertEquals("attachment; filename=\"a.ttl\"", response.getHeader(HttpHeaders.CONTENT_DISPOSITION));
		assertEquals("Tue, 06 Oct 2026 10:00:00 GMT", response.getHeader(HttpHeaders.LAST_MODIFIED));
		assertEquals("\"v1\"", response.getHeader(HttpHeaders.ETAG));
		assertEquals("Wed, 07 Oct 2026 10:00:00 GMT", response.getHeader(HttpHeaders.EXPIRES));
		assertEquals("script-src 'none'; form-action 'none';", response.getHeader("Content-Security-Policy"));
		assertEquals("hello", response.getContentAsString());
	}

	@Test
	void proxy_head_forwardsTheUpstreamHeadersWithoutReadingItsBody() throws Exception {
		MockHttpServletResponse response = new MockHttpServletResponse();
		HttpURLConnection conn = upstream(200, 100L * 1024 * 1024);
		when(conn.getContentType()).thenReturn("application/rdf+xml");

		service.proxy(target, null, true, response);

		assertEquals(200, response.getStatus());
		assertEquals("application/rdf+xml", response.getContentType());
		assertEquals(String.valueOf(100L * 1024 * 1024), response.getHeader(HttpHeaders.CONTENT_LENGTH));
		assertEquals(0, response.getContentAsByteArray().length);
		verify(conn, never()).getInputStream();
		verify(conn, never()).getErrorStream();
		verify(conn).disconnect();
	}

	@Test
	void proxy_nonHttpUpstreamResponse_throws502BeforeAnythingIsSent() throws Exception {
		MockHttpServletResponse response = new MockHttpServletResponse();
		HttpURLConnection conn = mock(HttpURLConnection.class);
		when(ssrfValidator.openPinnedConnection(target.uri(), target.resolved())).thenReturn(conn);
		when(conn.getResponseCode()).thenReturn(-1);

		CustomResponseException e = assertThrows(CustomResponseException.class,
				() -> service.proxy(target, null, false, response));

		assertEquals(HttpStatus.BAD_GATEWAY, e.getStatus());
		assertEquals("Proxy request failed", e.getMessage());
		assertEquals(200, response.getStatus());
		verify(conn, never()).getInputStream();
	}

	@Test
	void proxy_upstreamErrorStatus_streamsTheErrorBodyWithItsStatus() throws Exception {
		MockHttpServletResponse response = new MockHttpServletResponse();
		HttpURLConnection conn = upstream(404, -1);
		when(conn.getErrorStream()).thenReturn(new ByteArrayInputStream("gone".getBytes(StandardCharsets.UTF_8)));

		service.proxy(target, null, false, response);

		assertEquals(404, response.getStatus());
		assertEquals("gone", response.getContentAsString());
		verify(conn, never()).getInputStream();
	}

	@Test
	void proxy_contentLengthOverTheConfiguredCap_throws502BeforeReadingOrSendingTheBody() throws Exception {
		ProxyService capped = serviceWithMaxResponseSize(DataSize.ofBytes(8192));
		MockHttpServletResponse response = new MockHttpServletResponse();
		HttpURLConnection conn = upstream(200, 8193);

		CustomResponseException e = assertThrows(CustomResponseException.class,
				() -> capped.proxy(target, null, false, response));

		assertEquals(HttpStatus.BAD_GATEWAY, e.getStatus());
		assertEquals("Upstream response exceeds maximum allowed size of 8192 bytes", e.getMessage());
		assertFalse(response.isCommitted());
		assertNull(response.getHeader(HttpHeaders.CONTENT_LENGTH));
		verify(conn, never()).getInputStream();
	}

	@Test
	void proxy_bodyWithoutContentLengthOverTheCap_isCutOffAfterTheResponseIsCommitted() throws Exception {
		// MockHttpServletResponse commits once its 4 KiB buffer overflows, as Jetty does with its own
		// buffer. The tripwire fails if the service reads on past the cap.
		ProxyService capped = serviceWithMaxResponseSize(DataSize.ofBytes(8192));
		MockHttpServletResponse response = new MockHttpServletResponse();
		HttpURLConnection conn = upstream(200, -1);
		when(conn.getInputStream()).thenReturn(failAfter(new byte[65536], 32768));

		CustomResponseException e = assertThrows(CustomResponseException.class,
				() -> capped.proxy(target, null, false, response));

		assertEquals(HttpStatus.BAD_GATEWAY, e.getStatus());
		assertTrue(response.isCommitted(), "AppExceptionHandler aborts the connection for a committed response");
		assertEquals(8192, response.getContentAsByteArray().length);
	}

	@Test
	void proxy_bodyWithoutContentLengthOverTheCap_beforeCommit_dropsTheUpstreamHeadersFromThe502() throws Exception {
		ProxyService capped = serviceWithMaxResponseSize(DataSize.ofBytes(10));
		MockHttpServletResponse response = new MockHttpServletResponse();
		HttpURLConnection conn = upstream(200, -1);
		withHeaders(conn, "Content-Encoding: gzip");
		when(conn.getInputStream()).thenReturn(new ByteArrayInputStream(new byte[11]));

		assertThrows(CustomResponseException.class, () -> capped.proxy(target, null, false, response));

		assertFalse(response.isCommitted());
		assertNull(response.getHeader(HttpHeaders.CONTENT_ENCODING));
	}

	@Test
	void proxy_repeatedEntityHeader_isForwardedWithEveryValueInWireOrder() throws Exception {
		// Repeated Content-Encoding fields are encoding layers in order; getHeaderFields() would group them by
		// spelling and reorder them, so the client would decode in the wrong order.
		MockHttpServletResponse response = new MockHttpServletResponse();
		HttpURLConnection conn = upstream(200, 5);
		withHeaders(conn, "Content-Encoding: gzip", "content-encoding: deflate", "Content-Encoding: br");
		when(conn.getInputStream()).thenReturn(new ByteArrayInputStream("hello".getBytes(StandardCharsets.UTF_8)));

		service.proxy(target, null, false, response);

		assertEquals(List.of("gzip", "deflate", "br"), response.getHeaders(HttpHeaders.CONTENT_ENCODING));
	}

	@Test
	void proxy_contentLengthAlongsideTransferEncoding_isNotForwarded() throws Exception {
		// RFC 9112, section 6.3: Transfer-Encoding overrides Content-Length.
		MockHttpServletResponse response = new MockHttpServletResponse();
		HttpURLConnection conn = mock(HttpURLConnection.class);
		when(ssrfValidator.openPinnedConnection(target.uri(), target.resolved())).thenReturn(conn);
		when(conn.getResponseCode()).thenReturn(200);
		lenient().when(conn.getContentLengthLong()).thenReturn(5L);
		withHeaders(conn, "Transfer-Encoding: chunked", "Content-Length: 5");
		when(conn.getInputStream()).thenReturn(new ByteArrayInputStream("hello".getBytes(StandardCharsets.UTF_8)));

		service.proxy(target, null, false, response);

		assertNull(response.getHeader(HttpHeaders.CONTENT_LENGTH));
		assertEquals("hello", response.getContentAsString());
	}

	@Test
	void proxy_headersNominatedByConnection_areNotForwarded() throws Exception {
		MockHttpServletResponse response = new MockHttpServletResponse();
		HttpURLConnection conn = upstream(200, 5);
		withHeaders(conn,
				"connection: keep-alive, ETag",
				"ETag: \"v1\"",
				"Connection:  content-language ",
				"Content-Language: sv",
				"CONNECTION: Content-Type,CONTENT-LENGTH",
				"Expires: Wed, 07 Oct 2026 10:00:00 GMT");
		lenient().when(conn.getContentType()).thenReturn("text/plain");
		when(conn.getInputStream()).thenReturn(new ByteArrayInputStream("hello".getBytes(StandardCharsets.UTF_8)));

		service.proxy(target, null, false, response);

		assertNull(response.getHeader(HttpHeaders.ETAG));
		assertNull(response.getHeader(HttpHeaders.CONTENT_LANGUAGE));
		assertNull(response.getContentType());
		assertNull(response.getHeader(HttpHeaders.CONTENT_LENGTH));
		assertEquals("Wed, 07 Oct 2026 10:00:00 GMT", response.getHeader(HttpHeaders.EXPIRES));
	}

	@Test
	void proxy_capExceededBeforeCommit_discardsTheBufferedUpstreamBytes() throws Exception {
		// The 502 that follows would otherwise be appended to the first upstream bytes.
		ProxyService capped = serviceWithMaxResponseSize(DataSize.ofBytes(10));
		MockHttpServletResponse response = new MockHttpServletResponse();
		HttpURLConnection conn = upstream(200, -1);
		when(conn.getInputStream()).thenReturn(inChunksOf(5, new byte[11]));

		assertThrows(CustomResponseException.class, () -> capped.proxy(target, null, false, response));

		assertFalse(response.isCommitted());
		assertEquals(0, response.getContentAsByteArray().length);
	}

	/** A stream over {@code body} that hands out at most {@code chunk} bytes per read. */
	private static InputStream inChunksOf(int chunk, byte[] body) {
		return new ByteArrayInputStream(body) {
			@Override
			public synchronized int read(byte[] buf, int off, int len) {
				return super.read(buf, off, Math.min(len, chunk));
			}
		};
	}

	@Test
	void proxy_upstreamBodyExactlyAtTheCap_isStreamed() throws Exception {
		// The check is strictly greater-than, so the boundary must pass.
		ProxyService capped = serviceWithMaxResponseSize(DataSize.ofBytes(5));
		MockHttpServletResponse response = new MockHttpServletResponse();
		HttpURLConnection conn = upstream(200, 5);
		when(conn.getInputStream()).thenReturn(new ByteArrayInputStream("hello".getBytes(StandardCharsets.UTF_8)));

		capped.proxy(target, null, false, response);

		assertEquals("hello", response.getContentAsString());
	}

	/**
	 * A stream over {@code body} that throws once {@code limit} bytes have been handed out, so a reader
	 * that reads on past the cap fails rather than quietly passing.
	 */
	private static InputStream failAfter(byte[] body, int limit) {
		return new ByteArrayInputStream(body) {
			private int delivered;

			@Override
			public synchronized int read(byte[] buf, int off, int len) {
				if (delivered >= limit) {
					throw new AssertionError("read past the cap");
				}
				int read = super.read(buf, off, len);
				if (read > 0) {
					delivered += read;
				}
				return read;
			}
		};
	}

	/** {@code size} zero bytes, refusing to deliver more while {@code client} lags more than 64 KiB behind. */
	private static InputStream lockstepBody(long size, CountingResponse client) {
		return new InputStream() {
			private long delivered;

			@Override
			public int read() {
				throw new UnsupportedOperationException();
			}

			@Override
			public int read(byte[] buf, int off, int len) {
				if (delivered - client.written > 64 * 1024) {
					throw new AssertionError("the body is being buffered: " + delivered + " bytes read, "
							+ client.written + " written to the client");
				}
				if (delivered == size) {
					return -1;
				}
				int read = (int) Math.min(len, size - delivered);
				delivered += read;
				return read;
			}
		};
	}

	/** Counts the bytes written to the client and discards them. */
	private static final class CountingResponse extends MockHttpServletResponse {

		private long written;

		@Override
		public ServletOutputStream getOutputStream() {
			return new ServletOutputStream() {
				@Override
				public void write(int b) {
					written++;
				}

				@Override
				public void write(byte[] b, int off, int len) {
					written += len;
				}

				@Override
				public boolean isReady() {
					return true;
				}

				@Override
				public void setWriteListener(WriteListener listener) {
				}
			};
		}
	}

	private void asAuthenticatedUser() {
		when(principalManager.getGuestUser()).thenReturn(guestUser);
		when(guestUser.getURI()).thenReturn(URI.create("http://example.com/_principals/resource/_guest"));
		when(principalManager.getAuthenticatedUserURI()).thenReturn(URI.create("http://example.com/_principals/resource/alice"));
	}

	/**
	 * Stubs the indexed header accessors with {@code fields} ("Name: value") in that order, after the status line
	 * at index 0, as HttpURLConnection reports them.
	 */
	private static void withHeaders(HttpURLConnection conn, String... fields) {
		lenient().when(conn.getHeaderFieldKey(anyInt())).thenAnswer(invocation -> {
			int i = invocation.getArgument(0);
			return (i >= 1 && i <= fields.length) ? fields[i - 1].substring(0, fields[i - 1].indexOf(':')) : null;
		});
		lenient().when(conn.getHeaderField(anyInt())).thenAnswer(invocation -> {
			int i = invocation.getArgument(0);
			if (i == 0) {
				return "HTTP/1.1 200 OK";
			}
			return (i <= fields.length) ? fields[i - 1].substring(fields[i - 1].indexOf(':') + 1).trim() : null;
		});
	}

	private HttpURLConnection upstream(int status, long contentLength) throws Exception {
		HttpURLConnection conn = mock(HttpURLConnection.class);
		when(ssrfValidator.openPinnedConnection(target.uri(), target.resolved())).thenReturn(conn);
		when(conn.getResponseCode()).thenReturn(status);
		when(conn.getContentLengthLong()).thenReturn(contentLength);
		return conn;
	}

	private ProxyService serviceWithMaxResponseSize(DataSize maxResponseSize) {
		ProxyService capped = new ProxyService(principalManager, contextService, ssrfValidator,
				new SsrfSafeHttpClient(ssrfValidator, ProxyPropertiesFixture.defaults()),
				ProxyPropertiesFixture.withMaxResponseSize(maxResponseSize));
		capped.setWhitelistAnon(Set.of());
		return capped;
	}

	@Test
	void proxy_redirect_followsAndRevalidatesEveryHop() throws Exception {
		asAuthenticatedUser();
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
		when(secondConn.getContentLengthLong()).thenReturn(-1L);
		when(secondConn.getContentType()).thenReturn("text/plain");
		when(secondConn.getInputStream())
				.thenReturn(new ByteArrayInputStream("hello".getBytes(StandardCharsets.UTF_8)));
		MockHttpServletResponse response = new MockHttpServletResponse();

		service.proxy(first, null, false, response);

		assertEquals(200, response.getStatus());
		assertEquals("hello", response.getContentAsString());
		verify(firstConn).disconnect();
		verify(secondConn).disconnect();
	}

	@Test
	void proxy_relativeLocation_resolvedAgainstCurrentUri() throws Exception {
		asAuthenticatedUser();
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
		when(secondConn.getContentLengthLong()).thenReturn(0L);
		when(secondConn.getInputStream()).thenReturn(new ByteArrayInputStream(new byte[0]));
		MockHttpServletResponse response = new MockHttpServletResponse();

		service.proxy(first, null, false, response);

		assertEquals(200, response.getStatus());
	}

	// The redirect-loop cap, missing-Location, timeout, and IO error mappings are pinned in
	// SsrfSafeHttpClientTest; the proxy tests here cover only ProxyService's own wiring
	// (per-hop validateForProxy revalidation and response streaming).

	private static SsrfValidator.ValidatedTarget validatedTarget(String url, String ip) throws Exception {
		URI uri = URI.create(url);
		return new SsrfValidator.ValidatedTarget(uri, uri.getHost(), InetAddress.getByName(ip));
	}
}
