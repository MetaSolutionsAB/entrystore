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

import org.entrystore.rest.springboot.controller.AcceptsMultipart;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.method.HandlerMethod;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MultipartHandlerInterceptorTest {

	private final MultipartHandlerInterceptor interceptor = new MultipartHandlerInterceptor();

	@Test
	void multipartToAHandlerMarkedAsAcceptingIt_passes() throws Exception {
		assertTrue(interceptor.preHandle(request("multipart/form-data; boundary=x"), new MockHttpServletResponse(),
				handler("upload")));
	}

	@Test
	void multipartToAnyOtherHandler_isRejectedBeforeArgumentResolution() throws Exception {
		// E.g. POST /echo with Accept: application/json, which Spring routes to POST /{context-id}.
		assertThrows(HttpMediaTypeNotSupportedException.class, () -> interceptor.preHandle(
				request("multipart/form-data; boundary=x"), new MockHttpServletResponse(), handler("createEntry")));
	}

	@Test
	void multipartToANonMethodHandler_isRejected() {
		assertThrows(HttpMediaTypeNotSupportedException.class, () -> interceptor.preHandle(
				request("multipart/form-data; boundary=x"), new MockHttpServletResponse(), new Object()));
	}

	@Test
	void nonMultipartRequest_passesAnyHandler() throws Exception {
		assertTrue(interceptor.preHandle(request("application/json"), new MockHttpServletResponse(),
				handler("createEntry")));
	}

	private static MockHttpServletRequest request(String contentType) {
		MockHttpServletRequest request = new MockHttpServletRequest("POST", "/echo");
		request.setContentType(contentType);
		return request;
	}

	private static HandlerMethod handler(String name) throws NoSuchMethodException {
		return new HandlerMethod(new Handlers(), Handlers.class.getMethod(name));
	}

	public static class Handlers {

		@AcceptsMultipart
		public void upload() {
		}

		public void createEntry() {
		}
	}
}
