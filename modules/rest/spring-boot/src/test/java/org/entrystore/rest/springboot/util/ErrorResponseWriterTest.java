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

package org.entrystore.rest.springboot.util;

import org.entrystore.rest.springboot.model.api.ErrorResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ErrorResponseWriterTest {

	private final ErrorResponseWriter writer = new ErrorResponseWriter(JsonMapper.builder().build());

	@Test
	void writeErrorResponseAsJson_writesStatusContentTypeAndBody() throws Exception {
		var response = new MockHttpServletResponse();

		writer.writeErrorResponseAsJson(response, ErrorResponse.builder()
				.status(HttpStatus.PAYLOAD_TOO_LARGE.value())
				.path("/auth/cookie")
				.error("too big")
				.build());

		assertEquals(HttpStatus.PAYLOAD_TOO_LARGE.value(), response.getStatus());
		assertTrue(response.getContentType().startsWith("application/json"));
		assertTrue(response.getContentAsString().contains("\"path\":\"/auth/cookie\""));
		assertTrue(response.getContentAsString().contains("\"error\":\"too big\""));
	}

	@Test
	void writeErrorResponseAsJson_alreadyCommitted_doesNotOverwriteStatus() throws Exception {
		var response = new MockHttpServletResponse();
		response.setStatus(HttpStatus.OK.value());
		response.getWriter().write("partial body");
		response.flushBuffer();

		writer.writeErrorResponseAsJson(response, ErrorResponse.builder()
				.status(HttpStatus.INTERNAL_SERVER_ERROR.value())
				.path("/whatever")
				.error("late failure")
				.build());

		assertEquals(HttpStatus.OK.value(), response.getStatus());
		assertEquals("partial body", response.getContentAsString());
	}

	@Test
	void writeUnauthorizedAsJson_nullsTimestampSoBranchLatencyDoesNotLeak() throws Exception {
		// The 401 envelope must be byte-identical across authentication-failure branches for a given
		// request URI, or body.timestamp minus client.sentAt discriminates the immediate
		// blacklist short-circuit from the post-bcrypt bad-credentials path.
		var response = new MockHttpServletResponse();

		writer.writeUnauthorizedAsJson(response, "/auth/cookie");

		assertEquals(HttpStatus.UNAUTHORIZED.value(), response.getStatus());
		assertTrue(response.getContentAsString().contains("\"timestamp\":null"),
				"timestamp must not carry a wall-clock value: " + response.getContentAsString());
		assertTrue(response.getContentAsString().contains("\"error\":\"Unauthorized\""));
	}

	@Test
	void redirectOrWriteUnauthorized_withRedirectUrl_redirectsWithoutWritingBody() throws Exception {
		var response = new MockHttpServletResponse();

		writer.redirectOrWriteUnauthorized(request("/login/saml2/sso/idp"), response, "https://example.org/failed", "SAML login failed");

		assertEquals("https://example.org/failed", response.getRedirectedUrl());
		assertEquals("", response.getContentAsString());
	}

	@Test
	void redirectOrWriteUnauthorized_committedResponse_skipsTheRedirectInsteadOfThrowing() throws Exception {
		// Reachable from AbstractSsoLoginSuccessHandler's last-resort catch, which runs after a
		// subclass's custom-success redirect may already have committed the response; sendRedirect
		// would then throw IllegalStateException and escape the Security filter chain as a raw
		// container 500 instead of the intended failure handling.
		var response = new MockHttpServletResponse();
		response.flushBuffer();

		assertDoesNotThrow(() -> writer.redirectOrWriteUnauthorized(
				request("/login/cas"), response, "https://example.org/failed", "CAS login failed"));

		assertNull(response.getRedirectedUrl());
	}

	@Test
	void redirectOrWriteUnauthorized_withoutRedirectUrl_writes401WithFailureMessage() throws Exception {
		var response = new MockHttpServletResponse();

		writer.redirectOrWriteUnauthorized(request("/login/cas"), response, null, "CAS login failed");

		assertEquals(HttpStatus.UNAUTHORIZED.value(), response.getStatus());
		assertTrue(response.getContentAsString().contains("\"error\":\"CAS login failed\""));
	}

	@Test
	void redirectOrWriteUnauthorized_nullFailureMessage_fallsBackToGenericText() throws Exception {
		var response = new MockHttpServletResponse();

		writer.redirectOrWriteUnauthorized(request("/login/cas"), response, null, null);

		assertTrue(response.getContentAsString().contains("\"error\":\"SSO login failed\""));
	}

	@Test
	void redirectOrWriteUnauthorized_contextRelativeUrl_isPrefixedWithTheContextPath() throws Exception {
		// The default failure URL (/auth/user) is context-relative; a raw sendRedirect would resolve it
		// against the server root and 404 when the app is served under a servlet context path.
		var response = new MockHttpServletResponse();

		writer.redirectOrWriteUnauthorized(request("/store", "/store/login/cas"), response, "/auth/user", "CAS login failed");

		assertEquals("/store/auth/user", response.getRedirectedUrl());
	}

	@Test
	void redirectOrWriteUnauthorized_absoluteUrl_isNotPrefixedWithTheContextPath() throws Exception {
		var response = new MockHttpServletResponse();

		writer.redirectOrWriteUnauthorized(request("/store", "/store/login/cas"), response, "https://app.example.org/login", "CAS login failed");

		assertEquals("https://app.example.org/login", response.getRedirectedUrl());
	}

	@Test
	void redirectOrWriteUnauthorized_withoutRedirectUrl_reportsTheRequestUriAsPath() throws Exception {
		var response = new MockHttpServletResponse();

		writer.redirectOrWriteUnauthorized(request("/store", "/store/login/cas"), response, null, "CAS login failed");

		assertTrue(response.getContentAsString().contains("\"path\":\"/store/login/cas\""));
	}

	private static MockHttpServletRequest request(String requestUri) {
		return request("", requestUri);
	}

	private static MockHttpServletRequest request(String contextPath, String requestUri) {
		var request = new MockHttpServletRequest("GET", requestUri);
		request.setContextPath(contextPath);
		return request;
	}
}
