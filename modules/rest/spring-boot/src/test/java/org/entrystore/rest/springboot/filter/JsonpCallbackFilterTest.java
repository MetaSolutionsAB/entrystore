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

package org.entrystore.rest.springboot.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.entrystore.rest.springboot.util.ErrorResponseWriter;
import tools.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.context.request.async.StandardServletAsyncWebRequest;
import org.springframework.web.context.request.async.WebAsyncUtils;

import java.io.ByteArrayOutputStream;
import java.net.URLEncoder;
import java.util.Arrays;
import java.util.Collections;
import java.util.stream.Stream;

import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonpCallbackFilterTest {

	private final JsonpCallbackFilter filter = new JsonpCallbackFilter(true, new ErrorResponseWriter(JsonMapper.builder().build()));

	@Test
	void getWithCallback_wrapsJsonAsJavascript() throws Exception {
		var request = getRequestWithCallback("/90/entry/1", "foo");
		var response = new MockHttpServletResponse();

		filter.doFilter(request, response, chainWriting(200, "application/json", "{\"a\":1}".getBytes(UTF_8)));

		assertEquals("foo({\"a\":1})", response.getContentAsString());
		assertTrue(response.getContentType().startsWith("application/javascript"));
		assertEquals("nosniff", response.getHeader("X-Content-Type-Options"));
		// The wrapped body no longer matches the controller's ETag/Last-Modified, so it is non-cacheable.
		assertEquals("no-store", response.getHeader("Cache-Control"));
	}

	@Test
	void declaredContentLength_growsByTheCallback() throws Exception {
		byte[] json = "{\"a\":1}".getBytes(UTF_8);
		var request = getRequestWithCallback("/90/entry/1", "foo");
		var response = new MockHttpServletResponse();

		filter.doFilter(request, response, chainWriting(200, "application/json", json, json.length));

		assertEquals("foo({\"a\":1})", response.getContentAsString());
		assertEquals("foo({\"a\":1})".getBytes(UTF_8).length, response.getContentLengthLong());
	}

	@Test
	void undeclaredContentLength_staysUndeclared() throws Exception {
		// A serializer that streams the JSON declares no length, and the body is not buffered to measure it.
		var request = getRequestWithCallback("/90/entry/1", "foo");
		var response = new MockHttpServletResponse();

		filter.doFilter(request, response, chainWriting(200, "application/json", "{\"a\":1}".getBytes(UTF_8)));

		assertEquals("foo({\"a\":1})", response.getContentAsString());
		assertNull(response.getHeader("Content-Length"));
	}

	@Test
	void jsonBody_reachesTheResponseWhileTheHandlerWrites() throws Exception {
		var request = getRequestWithCallback("/90/entry/1", "cb");
		var response = new MockHttpServletResponse();

		filter.doFilter(request, response, (req, res) -> {
			((HttpServletResponse) res).setStatus(200);
			res.setContentType("application/json");
			res.getOutputStream().write("{\"a\":1}".getBytes(UTF_8));
			// The callback goes before the body, not around a buffered copy of it once the handler returned.
			assertEquals("cb({\"a\":1}", response.getContentAsString());
		});

		assertEquals("cb({\"a\":1})", response.getContentAsString());
	}

	@Test
	void nonJsonBody_reachesTheResponseWhileTheHandlerWrites() throws Exception {
		byte[] file = new byte[64 * 1024];
		Arrays.fill(file, (byte) 7);
		var request = getRequestWithCallback("/90/resource/1", "cb");
		var response = new MockHttpServletResponse();

		filter.doFilter(request, response, (req, res) -> {
			var httpResponse = (HttpServletResponse) res;
			httpResponse.setStatus(200);
			httpResponse.setContentType("application/octet-stream");
			httpResponse.setContentLengthLong(file.length);
			httpResponse.setHeader("Digest", "sha-256=abc");
			res.getOutputStream().write(file);
			assertArrayEquals(file, response.getContentAsByteArray());
			assertEquals(file.length, response.getContentLengthLong());
			assertEquals("sha-256=abc", response.getHeader("Digest"));
		});

		assertArrayEquals(file, response.getContentAsByteArray());
		assertTrue(response.getContentType().startsWith("application/octet-stream"));
		assertEquals("no-store", response.getHeader("Cache-Control"));
		assertNull(response.getHeader("X-Content-Type-Options"));
	}

	@Test
	void wrappedBody_losesTheDigestOfTheUnwrappedOne() throws Exception {
		byte[] json = "{\"a\":1}".getBytes(UTF_8);
		var request = getRequestWithCallback("/90/resource/1", "cb");
		var response = new MockHttpServletResponse();

		filter.doFilter(request, response, (req, res) -> {
			((HttpServletResponse) res).setStatus(200);
			res.setContentType("application/json");
			((HttpServletResponse) res).setHeader("Digest", "sha-256=abc");
			res.getOutputStream().write(json);
		});

		assertEquals("cb({\"a\":1})", response.getContentAsString());
		assertNull(response.getHeader("Digest"));
	}

	@Test
	void jsonWrittenThroughTheWriter_isWrappedInTheWritersCharset() throws Exception {
		byte[] json = "{\"t\":\"café\"}".getBytes(ISO_8859_1);
		var request = getRequestWithCallback("/90/entry/1", "cb");
		var response = new MockHttpServletResponse();

		filter.doFilter(request, response, (req, res) -> {
			((HttpServletResponse) res).setStatus(200);
			res.setContentType("application/json;charset=ISO-8859-1");
			res.getWriter().write("{\"t\":\"café\"}");
		});

		assertArrayEquals(concat("cb(".getBytes(ISO_8859_1), json, ")".getBytes(ISO_8859_1)),
				response.getContentAsByteArray());
		assertTrue(response.getContentType().startsWith("application/javascript"));
		assertEquals("ISO-8859-1", response.getCharacterEncoding());
	}

	@Test
	void emptyJsonBody_isWrappedAsAnEmptyCall() throws Exception {
		var request = getRequestWithCallback("/90/entry/1", "cb");
		var response = new MockHttpServletResponse();

		filter.doFilter(request, response, chainWriting(200, "application/json", null));

		assertEquals("cb()", response.getContentAsString());
		assertEquals(4, response.getContentLengthLong());
	}

	@Test
	void responseWithoutContentType_isNotWrapped() throws Exception {
		var request = getRequestWithCallback("/90/entry/1", "cb");
		var response = new MockHttpServletResponse();

		filter.doFilter(request, response, (req, res) -> ((HttpServletResponse) res).setStatus(200));

		assertEquals(200, response.getStatus());
		assertEquals("", response.getContentAsString());
		assertNull(response.getContentType());
		assertEquals("no-store", response.getHeader("Cache-Control"));
	}

	@Test
	void flushOfAResponseWithoutContentType_isNotWrapped() throws Exception {
		var request = getRequestWithCallback("/90/entry/1", "cb");
		var response = new MockHttpServletResponse();

		filter.doFilter(request, response, (req, res) -> {
			((HttpServletResponse) res).setStatus(200);
			res.flushBuffer();
		});

		assertEquals("", response.getContentAsString());
		assertNull(response.getContentType());
		assertTrue(response.isCommitted());
	}

	@Test
	void flushBeforeTheBody_stillWrapsTheBody() throws Exception {
		var request = getRequestWithCallback("/90/entry/1", "cb");
		var response = new MockHttpServletResponse();

		filter.doFilter(request, response, (req, res) -> {
			((HttpServletResponse) res).setStatus(200);
			res.setContentType("application/json");
			res.flushBuffer();
			res.getOutputStream().write("{}".getBytes(UTF_8));
		});

		assertEquals("cb({})", response.getContentAsString());
	}

	@Test
	void springErrorHandlingAfterPartOfAJsonBody_wrapsOnlyTheError() throws Exception {
		byte[] error = "{\"error\":\"x\"}".getBytes(UTF_8);
		var request = getRequestWithCallback("/90/entry/1", "cb");
		var response = new MockHttpServletResponse();

		filter.doFilter(request, response, (req, res) -> {
			var httpResponse = (HttpServletResponse) res;
			httpResponse.setStatus(200);
			httpResponse.setContentType("application/json");
			httpResponse.setContentLengthLong(100);
			res.getOutputStream().write("{\"partial\":".getBytes(UTF_8));
			// What DispatcherServlet.processHandlerException does before the exception handler writes
			httpResponse.setHeader("Content-Type", null);
			httpResponse.setHeader("Content-Disposition", null);
			httpResponse.resetBuffer();
			httpResponse.setStatus(500);
			httpResponse.setContentType("application/json");
			httpResponse.setContentLengthLong(error.length);
			res.getOutputStream().write(error);
		});

		assertEquals(500, response.getStatus());
		assertEquals("cb({\"error\":\"x\"})", response.getContentAsString());
		assertEquals(error.length + 4, response.getContentLengthLong());
		assertTrue(response.getContentType().startsWith("application/javascript"));
	}

	@Test
	void proxyErrorAfterPartOfAJsonBody_wrapsOnlyTheError() throws Exception {
		var request = getRequestWithCallback("/proxy", "cb");
		var response = new MockHttpServletResponse();

		filter.doFilter(request, response, (req, res) -> {
			var httpResponse = (HttpServletResponse) res;
			httpResponse.setStatus(200);
			httpResponse.setContentType("application/json");
			httpResponse.setContentLengthLong(100);
			res.getOutputStream().write("{\"partial\":".getBytes(UTF_8));
			// What ProxyService does when the upstream fails, then what DispatcherServlet does
			httpResponse.resetBuffer();
			httpResponse.setContentLengthLong(-1);
			httpResponse.setHeader("ETag", null);
			httpResponse.setHeader("Content-Type", null);
			httpResponse.resetBuffer();
			httpResponse.setStatus(502);
			httpResponse.setContentType("application/json");
			res.getOutputStream().write("{\"error\":\"x\"}".getBytes(UTF_8));
		});

		assertEquals(502, response.getStatus());
		assertEquals("cb({\"error\":\"x\"})", response.getContentAsString());
		// The length of the discarded body is not kept; MockHttpServletResponse reports a cleared one as -1.
		assertEquals(-1, response.getContentLengthLong());
	}

	@Test
	void resetToANonJsonBody_isNotWrapped() throws Exception {
		var request = getRequestWithCallback("/90/entry/1", "cb");
		var response = new MockHttpServletResponse();

		filter.doFilter(request, response, (req, res) -> {
			var httpResponse = (HttpServletResponse) res;
			httpResponse.setStatus(200);
			httpResponse.setContentType("application/json");
			res.getOutputStream().write("{\"partial\":".getBytes(UTF_8));
			httpResponse.resetBuffer();
			httpResponse.setContentType("text/plain");
			httpResponse.setContentLengthLong(5);
			res.getOutputStream().write("plain".getBytes(UTF_8));
		});

		assertEquals("plain", response.getContentAsString());
		assertTrue(response.getContentType().startsWith("text/plain"));
		assertEquals(5, response.getContentLengthLong());
		assertNull(response.getHeader("X-Content-Type-Options"));
	}

	@Test
	void resetOfANonJsonBodyToAJsonError_wrapsTheErrorWithoutTheDiscardedLength() throws Exception {
		var request = getRequestWithCallback("/90/resource/1", "cb");
		var response = new MockHttpServletResponse();

		filter.doFilter(request, response, (req, res) -> {
			var httpResponse = (HttpServletResponse) res;
			httpResponse.setStatus(200);
			httpResponse.setContentType("application/octet-stream");
			httpResponse.setContentLengthLong(1000);
			res.getOutputStream().write(new byte[10]);
			// What DispatcherServlet.processHandlerException does before the exception handler writes
			httpResponse.setHeader("Content-Type", null);
			httpResponse.setHeader("Content-Disposition", null);
			httpResponse.resetBuffer();
			httpResponse.setStatus(500);
			httpResponse.setContentType("application/json");
			res.getOutputStream().write("{\"error\":\"x\"}".getBytes(UTF_8));
		});

		assertEquals("cb({\"error\":\"x\"})", response.getContentAsString());
		assertTrue(response.getContentType().startsWith("application/javascript"));
		// MockHttpServletResponse reports a cleared length as -1.
		assertEquals(-1, response.getContentLengthLong());
	}

	@Test
	void resetBuffer_restoresTheHandlersContentType() throws Exception {
		var request = getRequestWithCallback("/90/entry/1", "cb");
		var response = new MockHttpServletResponse();

		filter.doFilter(request, response, (req, res) -> {
			((HttpServletResponse) res).setStatus(200);
			res.setContentType("application/json");
			res.getOutputStream().write("{\"partial\":".getBytes(UTF_8));
			res.resetBuffer();
			assertTrue(response.getContentType().startsWith("application/json"));
			assertNull(response.getHeader("X-Content-Type-Options"));
			res.getOutputStream().write("{\"a\":1}".getBytes(UTF_8));
		});

		assertEquals("cb({\"a\":1})", response.getContentAsString());
		assertTrue(response.getContentType().startsWith("application/javascript"));
	}

	@Test
	void fullReset_reopensTheDecision() throws Exception {
		var request = getRequestWithCallback("/90/entry/1", "cb");
		var response = new MockHttpServletResponse();

		filter.doFilter(request, response, (req, res) -> {
			((HttpServletResponse) res).setStatus(200);
			res.setContentType("application/json");
			res.getOutputStream().write("{\"partial\":".getBytes(UTF_8));
			res.reset();
			res.setContentType("text/plain");
			res.getOutputStream().write("plain".getBytes(UTF_8));
		});

		assertEquals("plain", response.getContentAsString());
		assertTrue(response.getContentType().startsWith("text/plain"));
		assertNull(response.getHeader("X-Content-Type-Options"));
		assertEquals("no-store", response.getHeader("Cache-Control"));
	}

	@Test
	void contentTypeSetWhileWrapping_keepsTheJavascriptType() throws Exception {
		var request = getRequestWithCallback("/90/entry/1", "cb");
		var response = new MockHttpServletResponse();

		filter.doFilter(request, response, (req, res) -> {
			((HttpServletResponse) res).setStatus(200);
			res.setContentType("application/json");
			res.getOutputStream().write("{\"a\":".getBytes(UTF_8));
			res.setContentType("application/json");
			res.getOutputStream().write("1}".getBytes(UTF_8));
		});

		assertEquals("cb({\"a\":1})", response.getContentAsString());
		assertTrue(response.getContentType().startsWith("application/javascript"));
	}

	@Test
	void closingTheStream_closesTheCallbackOnce() throws Exception {
		var request = getRequestWithCallback("/90/entry/1", "cb");
		var response = new MockHttpServletResponse();

		filter.doFilter(request, response, (req, res) -> {
			((HttpServletResponse) res).setStatus(200);
			res.setContentType("application/json");
			var out = res.getOutputStream();
			out.write("{\"a\":1}".getBytes(UTF_8));
			out.close();
		});

		assertEquals("cb({\"a\":1})", response.getContentAsString());
	}

	@Test
	void encodedJsonBody_isNotWrapped() throws Exception {
		// The parentheses would be plain text around the encoded bytes.
		byte[] gzipped = {0x1f, (byte) 0x8b, 8, 0};
		var request = getRequestWithCallback("/proxy", "cb");
		var response = new MockHttpServletResponse();

		filter.doFilter(request, response, (req, res) -> {
			var httpResponse = (HttpServletResponse) res;
			httpResponse.setStatus(200);
			httpResponse.setContentType("application/json");
			httpResponse.setHeader("Content-Encoding", "gzip");
			res.getOutputStream().write(gzipped);
		});

		assertArrayEquals(gzipped, response.getContentAsByteArray());
		assertTrue(response.getContentType().startsWith("application/json"));
		assertNull(response.getHeader("X-Content-Type-Options"));
	}

	@Test
	void partialContent_isNotWrapped() throws Exception {
		// A callback around a byte range would contradict its Content-Range.
		var request = getRequestWithCallback("/90/resource/1", "cb");
		var response = new MockHttpServletResponse();

		filter.doFilter(request, response, (req, res) -> {
			var httpResponse = (HttpServletResponse) res;
			httpResponse.setStatus(206);
			httpResponse.setContentType("application/json");
			httpResponse.setHeader("Content-Range", "bytes 0-3/7");
			res.getOutputStream().write("{\"a\"".getBytes(UTF_8));
		});

		assertEquals(206, response.getStatus());
		assertEquals("{\"a\"", response.getContentAsString());
		assertTrue(response.getContentType().startsWith("application/json"));
		assertNull(response.getHeader("X-Content-Type-Options"));
	}

	@Test
	void sendError_isNotWrapped() throws Exception {
		var request = getRequestWithCallback("/90/entry/1", "cb");
		var response = new MockHttpServletResponse();

		filter.doFilter(request, response, (req, res) -> {
			res.setContentType("application/json");
			((HttpServletResponse) res).sendError(404);
		});

		assertEquals(404, response.getStatus());
		assertEquals("", response.getContentAsString());
		assertNull(response.getHeader("X-Content-Type-Options"));
	}

	@Test
	void bodyWrittenAfterTheHandlerWentAsync_isNotWrapped() throws Exception {
		var request = getRequestWithCallback("/90/entry/1", "cb");
		request.setAsyncSupported(true);
		var response = new MockHttpServletResponse();
		ServletResponse[] wrapped = new ServletResponse[1];

		filter.doFilter(request, response, (req, res) -> {
			wrapped[0] = res;
			((HttpServletResponse) res).setStatus(200);
			res.setContentType("application/json");
			var asyncWebRequest = new StandardServletAsyncWebRequest((HttpServletRequest) req,
					(HttpServletResponse) res);
			WebAsyncUtils.getAsyncManager(req).setAsyncWebRequest(asyncWebRequest);
			asyncWebRequest.startAsync();
		});
		wrapped[0].getOutputStream().write("{\"a\":1}".getBytes(UTF_8));

		assertEquals("{\"a\":1}", response.getContentAsString());
		assertTrue(response.getContentType().startsWith("application/json"));
	}

	@ParameterizedTest
	@ValueSource(strings = {"application/ld+json", "application/rdf+json"})
	void jsonFamilyContentType_isWrapped(String contentType) throws Exception {
		var request = getRequestWithCallback("/90/entry/1", "foo");
		var response = new MockHttpServletResponse();

		filter.doFilter(request, response, chainWriting(200, contentType, "{\"a\":1}".getBytes(UTF_8)));

		assertEquals("foo({\"a\":1})", response.getContentAsString());
		assertTrue(response.getContentType().startsWith("application/javascript"));
	}

	@Test
	void callbackAtMaxLength_isWrapped() throws Exception {
		// 128 chars is the inclusive upper bound; guards against a >/>= off-by-one rejecting a legal name.
		String callback = "a".repeat(128);
		var request = getRequestWithCallback("/90/entry/1", callback);
		var response = new MockHttpServletResponse();

		filter.doFilter(request, response, chainWriting(200, "application/json", "{\"a\":1}".getBytes(UTF_8)));

		assertEquals(callback + "({\"a\":1})", response.getContentAsString());
		assertTrue(response.getContentType().startsWith("application/javascript"));
	}

	@Test
	void nonUtf8Charset_isPreservedByteForByte() throws Exception {
		// é is one byte (0xE9) in ISO-8859-1 vs two in UTF-8; proves the affixes and the response
		// charset follow the body's declared charset rather than a hardcoded UTF-8.
		byte[] json = "{\"t\":\"café\"}".getBytes(ISO_8859_1);
		var request = getRequestWithCallback("/90/entry/1", "cb");
		var response = new MockHttpServletResponse();

		filter.doFilter(request, response, chainWriting(200, "application/json;charset=ISO-8859-1", json));

		assertArrayEquals(concat("cb(".getBytes(ISO_8859_1), json, ")".getBytes(ISO_8859_1)),
				response.getContentAsByteArray());
		assertEquals("ISO-8859-1", response.getCharacterEncoding());
	}

	@Test
	void getWithoutCallback_passesJsonThrough() throws Exception {
		var request = new MockHttpServletRequest("GET", "/90/entry/1");
		var response = new MockHttpServletResponse();

		filter.doFilter(request, response, chainWriting(200, "application/json", "{\"a\":1}".getBytes(UTF_8)));

		assertEquals("{\"a\":1}", response.getContentAsString());
		assertTrue(response.getContentType().startsWith("application/json"));
		assertNull(response.getHeader("X-Content-Type-Options"));
	}

	@Test
	void callbackWithAValueThatCannotBeDecoded_passesJsonThrough() throws Exception {
		// IgnoreAuthFilter serves this request as guest by the parameter name; there is no name to wrap with
		var request = new MockHttpServletRequest("GET", "/90/entry/1");
		request.setQueryString("callback=%zz");
		var response = new MockHttpServletResponse();

		filter.doFilter(request, response, chainWriting(200, "application/json", "{\"a\":1}".getBytes(UTF_8)));

		assertEquals("{\"a\":1}", response.getContentAsString());
		assertTrue(response.getContentType().startsWith("application/json"));
		assertNull(response.getHeader("X-Content-Type-Options"));
	}

	@Test
	void blankCallback_defaultsToCallbackName() throws Exception {
		var request = getRequestWithCallback("/90/entry/1", "");
		var response = new MockHttpServletResponse();

		filter.doFilter(request, response, chainWriting(200, "application/json", "{\"a\":1}".getBytes(UTF_8)));

		assertEquals("callback({\"a\":1})", response.getContentAsString());
	}

	@Test
	void dottedCallback_isAllowed() throws Exception {
		var request = getRequestWithCallback("/90/entry/1", "angular.callbacks._0");
		var response = new MockHttpServletResponse();

		filter.doFilter(request, response, chainWriting(200, "application/json", "{\"a\":1}".getBytes(UTF_8)));

		assertEquals("angular.callbacks._0({\"a\":1})", response.getContentAsString());
	}

	@Test
	void jsonErrorResponse_isStillWrapped() throws Exception {
		// Strict Restlet parity: every JSON-family response is wrapped regardless of status.
		var request = getRequestWithCallback("/90/entry/1", "foo");
		var response = new MockHttpServletResponse();

		filter.doFilter(request, response, chainWriting(404, "application/json", "{\"error\":\"x\"}".getBytes(UTF_8)));

		assertEquals(404, response.getStatus());
		assertEquals("foo({\"error\":\"x\"})", response.getContentAsString());
		assertTrue(response.getContentType().startsWith("application/javascript"));
	}

	@ParameterizedTest
	@ValueSource(ints = {204, 304})
	void noBodyStatus_isNotWrapped(int status) throws Exception {
		var request = getRequestWithCallback("/90/entry/1", "foo");
		var response = new MockHttpServletResponse();

		filter.doFilter(request, response, chainWriting(status, "application/json", null));

		assertEquals(status, response.getStatus());
		assertTrue(response.getContentType().startsWith("application/json"));
		assertNull(response.getHeader("X-Content-Type-Options"));
	}

	@Test
	void notModifiedResponse_isNotStoredEither() throws Exception {
		// A conditional JSONP GET answered 304 keeps the controller's revalidating Cache-Control otherwise.
		var request = getRequestWithCallback("/90/entry/1", "foo");
		var response = new MockHttpServletResponse();
		response.setHeader("Cache-Control", CacheControlFilter.CACHE_CONTROL_AUTHENTICATED);

		filter.doFilter(request, response, chainWriting(304, "application/json", null));

		assertEquals(304, response.getStatus());
		assertEquals("private, no-store", response.getHeader("Cache-Control"));
	}

	@Test
	void nonJsonResponse_isNotWrapped() throws Exception {
		var request = getRequestWithCallback("/90/entry/1", "foo");
		var response = new MockHttpServletResponse();

		filter.doFilter(request, response, chainWriting(200, "application/rdf+xml", "<rdf/>".getBytes(UTF_8)));

		assertEquals("<rdf/>", response.getContentAsString());
		assertTrue(response.getContentType().startsWith("application/rdf+xml"));
	}

	@Test
	void postWithCallback_isNotWrapped() throws Exception {
		var request = new MockHttpServletRequest("POST", "/90/entry/1");
		request.setQueryString("callback=foo");
		var response = new MockHttpServletResponse();

		filter.doFilter(request, response, chainWriting(200, "application/json", "{\"a\":1}".getBytes(UTF_8)));

		assertEquals("{\"a\":1}", response.getContentAsString());
		assertTrue(response.getContentType().startsWith("application/json"));
	}

	@Test
	void disabledFilter_doesNotWrap() throws Exception {
		var request = getRequestWithCallback("/90/entry/1", "foo");
		var response = new MockHttpServletResponse();

		new JsonpCallbackFilter(false, new ErrorResponseWriter(JsonMapper.builder().build()))
				.doFilter(request, response, chainWriting(200, "application/json", "{\"a\":1}".getBytes(UTF_8)));

		assertEquals("{\"a\":1}", response.getContentAsString());
		assertTrue(response.getContentType().startsWith("application/json"));
	}

	@ParameterizedTest
	@MethodSource("invalidCallbacks")
	void invalidCallback_returns400AndDoesNotInvokeChain(String invalidCallback) throws Exception {
		var request = getRequestWithCallback("/90/entry/1", invalidCallback);
		var response = new MockHttpServletResponse();
		boolean[] chainInvoked = {false};

		filter.doFilter(request, response, (req, res) -> chainInvoked[0] = true);

		assertEquals(400, response.getStatus());
		assertFalse(chainInvoked[0], "chain must not run for an invalid callback name");
		assertTrue(response.getContentType().startsWith("application/json"));
		assertTrue(response.getContentAsString().contains("Invalid JSONP callback name"));
		// The 400 error envelope must not leak the JSONP response's nosniff header.
		assertNull(response.getHeader("X-Content-Type-Options"));
	}

	static Stream<String> invalidCallbacks() {
		return Stream.of(
				"alert(1)", "1foo", ".foo", "foo.", "foo..bar", "</script>",
				"a".repeat(129),                              // over the 128-char cap
				String.join(".", Collections.nCopies(33, "a"))); // 33 dotted segments, over the {0,31} cap
	}

	@Test
	void nonAsciiJsonBody_isPreservedByteForByte() throws Exception {
		// Proves byte-level wrapping: the JSON bytes pass through untouched between the ASCII affixes.
		byte[] json = "{\"t\":\"café\"}".getBytes(UTF_8);
		var request = getRequestWithCallback("/90/entry/1", "cb");
		var response = new MockHttpServletResponse();

		filter.doFilter(request, response, chainWriting(200, "application/json;charset=UTF-8", json));

		assertArrayEquals(concat("cb(".getBytes(UTF_8), json, ")".getBytes(UTF_8)), response.getContentAsByteArray());
	}

	@Test
	void sparqlPath_isSkippedEvenForWrappableJson() throws Exception {
		// /sparql streams via async dispatch and must never be wrapped, even when the
		// response carries an otherwise-wrappable application/json content type.
		var request = getRequestWithCallback("/sparql", "cb");
		request.setServletPath("/sparql");
		var response = new MockHttpServletResponse();

		filter.doFilter(request, response, chainWriting(200, "application/json", "{\"head\":{}}".getBytes(UTF_8)));

		assertEquals("{\"head\":{}}", response.getContentAsString());
		assertTrue(response.getContentType().startsWith("application/json"));
		assertNull(response.getHeader("X-Content-Type-Options"));
	}

	@Test
	void contextScopedSparqlPath_isSkipped() throws Exception {
		var request = getRequestWithCallback("/mycontext/sparql", "cb");
		request.setServletPath("/mycontext/sparql");
		var response = new MockHttpServletResponse();

		filter.doFilter(request, response, chainWriting(200, "application/json", "{\"head\":{}}".getBytes(UTF_8)));

		assertEquals("{\"head\":{}}", response.getContentAsString());
		assertNull(response.getHeader("X-Content-Type-Options"));
	}

	@Test
	void entryWithIdSparql_isNotMistakenForStreamingEndpoint() throws Exception {
		// Guards the async-path regex against false positives: an entry whose id is "sparql"
		// (/{ctx}/entry/sparql) is a normal JSON endpoint and must still be JSONP-wrapped.
		var request = getRequestWithCallback("/mycontext/entry/sparql", "cb");
		request.setServletPath("/mycontext/entry/sparql");
		var response = new MockHttpServletResponse();

		filter.doFilter(request, response, chainWriting(200, "application/json", "{\"a\":1}".getBytes(UTF_8)));

		assertEquals("cb({\"a\":1})", response.getContentAsString());
		assertTrue(response.getContentType().startsWith("application/javascript"));
	}

	@Test
	void existingCacheControl_isPreservedNotOverwritten() throws Exception {
		// A Cache-Control a controller chose must survive; overwriting it with a bare `no-store` could drop
		// `private` and allow shared-cache storage of a per-user body. The absent-header path is asserted in
		// getWithCallback_wrapsJsonAsJavascript.
		var request = getRequestWithCallback("/90/entry/1", "cb");
		var response = new MockHttpServletResponse();
		response.setHeader("Cache-Control", "private, no-store");

		filter.doFilter(request, response, chainWriting(200, "application/json", "{\"a\":1}".getBytes(UTF_8)));

		assertEquals("cb({\"a\":1})", response.getContentAsString());
		assertEquals("private, no-store", response.getHeader("Cache-Control"));
	}

	@Test
	void anonymousRevalidationCacheControl_becomesNoStore() throws Exception {
		// The entry and resource controllers ask anonymous clients to revalidate; a JSONP body must not be.
		var request = getRequestWithCallback("/90/entry/1", "cb");
		var response = new MockHttpServletResponse();
		response.setHeader("Cache-Control", CacheControlFilter.CACHE_CONTROL_ANONYMOUS);

		filter.doFilter(request, response, chainWriting(200, "application/json", "{\"a\":1}".getBytes(UTF_8)));

		assertEquals("cb({\"a\":1})", response.getContentAsString());
		assertEquals("no-store", response.getHeader("Cache-Control"));
	}

	@Test
	void authenticatedCacheControl_becomesPrivateNoStore() throws Exception {
		// CacheControlFilter lets the browser revalidate authenticated responses, but a JSONP body must not be
		// revalidated against the controller's validators, so it is not stored at all, still privately.
		var request = getRequestWithCallback("/90/entry/1", "cb");
		var response = new MockHttpServletResponse();
		response.setHeader("Cache-Control", CacheControlFilter.CACHE_CONTROL_AUTHENTICATED);

		filter.doFilter(request, response, chainWriting(200, "application/json", "{\"a\":1}".getBytes(UTF_8)));

		assertEquals("cb({\"a\":1})", response.getContentAsString());
		assertEquals("private, no-store", response.getHeader("Cache-Control"));
	}

	@Test
	void responseCommittedAroundTheWrapper_isNotWrapped() throws Exception {
		// A body written to the response itself, as an error page is, commits it before anything was decided.
		var request = getRequestWithCallback("/90/entry/1", "cb");
		var response = new MockHttpServletResponse();

		filter.doFilter(request, response, (req, res) -> {
			((HttpServletResponse) res).setStatus(200);
			res.setContentType("application/json");
			response.getOutputStream().write("{\"a\":1}".getBytes(UTF_8));
			response.setCommitted(true);
		});

		assertEquals("{\"a\":1}", response.getContentAsString());
		assertTrue(response.getContentType().startsWith("application/json"));
		assertNull(response.getHeader("X-Content-Type-Options"));
	}

	private static MockHttpServletRequest getRequestWithCallback(String uri, String callback) {
		// The filter reads the query string only, as 5.x did.
		var request = new MockHttpServletRequest("GET", uri);
		request.setQueryString("callback=" + URLEncoder.encode(callback, UTF_8));
		return request;
	}

	private static FilterChain chainWriting(int status, String contentType, byte[] body) {
		return chainWriting(status, contentType, body, -1);
	}

	/**
	 * @param contentLength the Content-Length the handler declares, or -1 for none
	 */
	private static FilterChain chainWriting(int status, String contentType, byte[] body, long contentLength) {
		return (req, res) -> {
			((HttpServletResponse) res).setStatus(status);
			res.setContentType(contentType);
			if (contentLength >= 0) {
				res.setContentLengthLong(contentLength);
			}
			// body is null for the no-body status cases (204/304).
			if (body != null) {
				res.getOutputStream().write(body);
			}
		};
	}

	private static byte[] concat(byte[]... parts) {
		var out = new ByteArrayOutputStream();
		for (byte[] part : parts) {
			out.writeBytes(part);
		}
		return out.toByteArray();
	}
}
