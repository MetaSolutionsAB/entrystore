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

import jakarta.servlet.MultipartConfigElement;
import org.entrystore.rest.springboot.util.ErrorResponseWriter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MultipartRequestSizeFilterTest {

	private static final String MULTIPART = "multipart/form-data; boundary=x";

	@Test
	void multipartContentLengthAboveMaxRequestSize_isAnswered413AndTheBodyDiscarded() throws Exception {
		MockHttpServletRequest request = upload(MULTIPART, 4097);
		MockHttpServletResponse response = new MockHttpServletResponse();
		MockFilterChain chain = new MockFilterChain();

		filter(4096).doFilter(request, response, chain);

		assertEquals(413, response.getStatus());
		assertEquals("Request size of 4097 bytes exceeds the maximum allowed size of 4096 bytes",
				JsonMapper.builder().build().readTree(response.getContentAsString()).get("error").asString());
		assertNull(chain.getRequest(), "the request must not reach the parameter-reading filters");
		assertTrue(request.getInputStream().isFinished(), "an unread body makes Jetty close the connection");
	}

	@Test
	void expectContinue_isAnswered413WithoutReadingTheBody() throws Exception {
		// Reading would make Jetty send 100 Continue, and the client would upload the body for nothing.
		MockHttpServletRequest request = upload(MULTIPART, 4097);
		request.addHeader("Expect", "100-continue");
		MockHttpServletResponse response = new MockHttpServletResponse();

		filter(4096).doFilter(request, response, new MockFilterChain());

		assertEquals(413, response.getStatus());
		assertFalse(request.getInputStream().isFinished());
	}

	@Test
	void multipartContentLengthAtMaxRequestSize_passes() throws Exception {
		MockFilterChain chain = new MockFilterChain();

		filter(4096).doFilter(upload(MULTIPART, 4096), new MockHttpServletResponse(), chain);

		assertNotNull(chain.getRequest());
	}

	@Test
	void nonMultipartBodyAboveMaxRequestSize_passes() throws Exception {
		// The multipart caps do not apply to raw bodies, so neither does this filter.
		MockFilterChain chain = new MockFilterChain();

		filter(4096).doFilter(upload("application/octet-stream", 8192), new MockHttpServletResponse(), chain);

		assertNotNull(chain.getRequest());
	}

	@Test
	void unlimitedMaxRequestSize_passesAnySize() throws Exception {
		MockFilterChain chain = new MockFilterChain();

		filter(-1).doFilter(upload(MULTIPART, 8192), new MockHttpServletResponse(), chain);

		assertNotNull(chain.getRequest());
	}

	private static MultipartRequestSizeFilter filter(long maxRequestSize) {
		MultipartConfigElement config = new MultipartConfigElement(null, -1, maxRequestSize, 0);
		return new MultipartRequestSizeFilter(
				new StaticListableBeanFactory(Map.of("multipartConfigElement", config))
						.getBeanProvider(MultipartConfigElement.class),
				new ErrorResponseWriter(JsonMapper.builder().build()));
	}

	private static MockHttpServletRequest upload(String contentType, int size) {
		MockHttpServletRequest request = new MockHttpServletRequest("PUT", "/store/1/resource/2");
		request.setContentType(contentType);
		request.setContent(new byte[size]);
		return request;
	}
}
