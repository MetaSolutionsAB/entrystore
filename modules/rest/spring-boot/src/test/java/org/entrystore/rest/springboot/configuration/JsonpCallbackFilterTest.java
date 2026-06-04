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

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.ByteArrayOutputStream;
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

	private final JsonpCallbackFilter filter = new JsonpCallbackFilter(true);

	@Test
	void getWithCallback_wrapsJsonAsJavascript() throws Exception {
		var request = getRequestWithCallback("/90/entry/1", "foo");
		var response = new MockHttpServletResponse();

		filter.doFilter(request, response, chainWriting(200, "application/json", "{\"a\":1}".getBytes(UTF_8)));

		assertEquals("foo({\"a\":1})", response.getContentAsString());
		assertTrue(response.getContentType().startsWith("application/javascript"));
		assertEquals("nosniff", response.getHeader("X-Content-Type-Options"));
		assertEquals("foo({\"a\":1})".getBytes(UTF_8).length, response.getContentLength());
		// The wrapped body no longer matches the controller's ETag/Last-Modified, so it is non-cacheable.
		assertEquals("no-store", response.getHeader("Cache-Control"));
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
		request.setParameter("callback", "foo");
		var response = new MockHttpServletResponse();

		filter.doFilter(request, response, chainWriting(200, "application/json", "{\"a\":1}".getBytes(UTF_8)));

		assertEquals("{\"a\":1}", response.getContentAsString());
		assertTrue(response.getContentType().startsWith("application/json"));
	}

	@Test
	void disabledFilter_doesNotWrap() throws Exception {
		var request = getRequestWithCallback("/90/entry/1", "foo");
		var response = new MockHttpServletResponse();

		new JsonpCallbackFilter(false)
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
		// /sparql streams via async dispatch and must never be buffered/wrapped, even when the
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
		// Guards the streaming-path regex against false positives: an entry whose id is "sparql"
		// (/{ctx}/entry/sparql) is a normal JSON endpoint and must still be JSONP-wrapped.
		var request = getRequestWithCallback("/mycontext/entry/sparql", "cb");
		request.setServletPath("/mycontext/entry/sparql");
		var response = new MockHttpServletResponse();

		filter.doFilter(request, response, chainWriting(200, "application/json", "{\"a\":1}".getBytes(UTF_8)));

		assertEquals("cb({\"a\":1})", response.getContentAsString());
		assertTrue(response.getContentType().startsWith("application/javascript"));
	}

	private static MockHttpServletRequest getRequestWithCallback(String uri, String callback) {
		var request = new MockHttpServletRequest("GET", uri);
		request.setParameter("callback", callback);
		return request;
	}

	private static FilterChain chainWriting(int status, String contentType, byte[] body) {
		return (req, res) -> {
			((HttpServletResponse) res).setStatus(status);
			res.setContentType(contentType);
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
