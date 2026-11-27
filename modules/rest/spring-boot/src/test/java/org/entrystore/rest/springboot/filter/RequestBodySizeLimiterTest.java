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

import jakarta.servlet.http.HttpServletRequest;
import org.entrystore.rest.springboot.configuration.MaxResourceSize;
import org.entrystore.rest.springboot.controller.MaxRequestBodySize;
import org.entrystore.rest.springboot.model.exception.EntityTooLargeException;
import org.entrystore.rest.springboot.util.EndlessStream;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.mock.http.MockHttpInputMessage;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.method.HandlerMethod;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RequestBodySizeLimiterTest {

	private final RequestBodySizeLimiter limiter = new RequestBodySizeLimiter(new MaxResourceSize("1k"));

	private final RequestBodySizeLimiter unlimited = new RequestBodySizeLimiter(new MaxResourceSize("-1"));

	@Test
	void preHandle_contentLengthAboveTheLimit_isRejectedBeforeTheBodyIsOpened() throws Exception {
		HttpServletRequest request = requestWithContentLength(1025);

		EntityTooLargeException e = assertThrows(EntityTooLargeException.class,
				() -> limiter.preHandle(request, new MockHttpServletResponse(), handler("text")));

		assertEquals("Request body size (of 1025b) exceeds maximum allowed size of: 1024b", e.getMessage());
		// Opening the body makes Jetty answer Expect: 100-continue.
		verify(request, never()).getInputStream();
		verify(request, never()).getReader();
	}

	@Test
	void preHandle_contentLengthAtTheLimit_passes() throws Exception {
		assertTrue(limiter.preHandle(requestWithContentLength(1024), new MockHttpServletResponse(),
				handler("text")));
	}

	@Test
	void preHandle_unknownContentLength_passes() throws Exception {
		// A chunked body: the limit is then applied while it is read.
		assertTrue(limiter.preHandle(requestWithContentLength(-1), new MockHttpServletResponse(), handler("text")));
	}

	@Test
	void preHandle_httpEntityParameter_isLimited() {
		assertThrows(EntityTooLargeException.class, () -> limiter.preHandle(requestWithContentLength(1025),
				new MockHttpServletResponse(), handler("entity")));
	}

	@Test
	void preHandle_handlerWithoutABodyParameter_passesAnyContentLength() throws Exception {
		// E.g. a form or a raw upload, which are limited elsewhere.
		assertTrue(limiter.preHandle(requestWithContentLength(Long.MAX_VALUE), new MockHttpServletResponse(),
				handler("noBody")));
	}

	@Test
	void preHandle_nonMethodHandler_passes() throws Exception {
		assertTrue(limiter.preHandle(requestWithContentLength(Long.MAX_VALUE), new MockHttpServletResponse(),
				new Object()));
	}

	@Test
	void preHandle_annotatedHandler_isLimitedByItsAnnotation() {
		EntityTooLargeException e = assertThrows(EntityTooLargeException.class, () -> limiter.preHandle(
				requestWithContentLength(17), new MockHttpServletResponse(), handler("small")));

		assertEquals("Request body size (of 17b) exceeds maximum allowed size of: 16b", e.getMessage());
	}

	@Test
	void preHandle_annotatedHandler_isLimitedWhenTheSettingIsMinusOne() {
		assertThrows(EntityTooLargeException.class, () -> unlimited.preHandle(requestWithContentLength(17),
				new MockHttpServletResponse(), handler("small")));
	}

	@Test
	void preHandle_settingMinusOne_passesAnyContentLength() throws Exception {
		assertTrue(unlimited.preHandle(requestWithContentLength(Long.MAX_VALUE), new MockHttpServletResponse(),
				handler("text")));
	}

	@Test
	void beforeBodyRead_bodyAboveTheLimit_isNotReadPastTheLimit() throws Exception {
		EndlessStream endlessBody = new EndlessStream();

		EntityTooLargeException e = assertThrows(EntityTooLargeException.class, () -> limiter.beforeBodyRead(
				new MockHttpInputMessage(endlessBody), bodyOf("text"), String.class, StringHttpMessageConverter.class));

		assertEquals("Request body exceeds maximum allowed size of: 1024b", e.getMessage());
		// One byte past the limit is what tells a body at the limit from one above it.
		assertEquals(1025, endlessBody.bytesRead);
	}

	@Test
	void beforeBodyRead_bodyAtTheLimit_isPassedOnIntact() throws Exception {
		byte[] body = "a".repeat(1024).getBytes(StandardCharsets.UTF_8);
		MockHttpInputMessage message = new MockHttpInputMessage(body);
		message.getHeaders().set("Content-Type", "text/plain");

		HttpInputMessage read = limiter.beforeBodyRead(message, bodyOf("text"), String.class,
				StringHttpMessageConverter.class);

		assertArrayEquals(body, read.getBody().readAllBytes());
		assertEquals("text/plain", read.getHeaders().getFirst("Content-Type"));
	}

	@Test
	void beforeBodyRead_annotatedHandler_isLimitedByItsAnnotation() {
		EndlessStream endlessBody = new EndlessStream();

		assertThrows(EntityTooLargeException.class, () -> unlimited.beforeBodyRead(
				new MockHttpInputMessage(endlessBody), bodyOf("small"), String.class, StringHttpMessageConverter.class));

		assertEquals(17, endlessBody.bytesRead);
	}

	@Test
	void beforeBodyRead_settingMinusOne_leavesTheBodyToTheConverter() throws Exception {
		EndlessStream endlessBody = new EndlessStream();
		MockHttpInputMessage message = new MockHttpInputMessage(endlessBody);

		assertSame(message, unlimited.beforeBodyRead(message, bodyOf("text"), String.class,
				StringHttpMessageConverter.class));
		assertEquals(0, endlessBody.bytesRead);
	}

	private static HttpServletRequest requestWithContentLength(long contentLength) {
		HttpServletRequest request = mock(HttpServletRequest.class);
		when(request.getContentLengthLong()).thenReturn(contentLength);
		return request;
	}

	private static HandlerMethod handler(String name) {
		Method method = Arrays.stream(Handlers.class.getDeclaredMethods())
				.filter(candidate -> candidate.getName().equals(name))
				.findFirst()
				.orElseThrow();
		return new HandlerMethod(new Handlers(), method);
	}

	private static MethodParameter bodyOf(String name) {
		return handler(name).getMethodParameters()[0];
	}

	public static class Handlers {

		public void text(@RequestBody String body) {
		}

		@MaxRequestBodySize(16)
		public void small(@RequestBody String body) {
		}

		public void entity(HttpEntity<String> body) {
		}

		public void noBody(@RequestParam String name) {
		}
	}
}
