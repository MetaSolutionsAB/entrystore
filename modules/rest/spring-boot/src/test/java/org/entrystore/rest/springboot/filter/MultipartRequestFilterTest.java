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
import org.entrystore.rest.springboot.configuration.CorsProperties;
import org.entrystore.rest.springboot.configuration.EntryStoreCorsConfigurationSource;
import org.entrystore.rest.springboot.util.ErrorResponseWriter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MultipartRequestFilterTest {

	private static final String MULTIPART = "multipart/form-data; boundary=x";

	@ParameterizedTest(name = "{0} {1}")
	@CsvSource({
			"PUT,  /1/resource/2",
			"POST, /1/import",
			"POST, /echo",
			"PUT,  /store/1/resource/2"
	})
	void multipartToARouteThatTakesIt_passes(String method, String path) throws Exception {
		MockHttpServletRequest request = request(method, path, MULTIPART, 10);
		if (path.startsWith("/store/")) {
			request.setContextPath("/store");
		}
		MockFilterChain chain = new MockFilterChain();

		filter(-1).doFilter(request, new MockHttpServletResponse(), chain);

		assertNotNull(chain.getRequest());
	}

	@ParameterizedTest(name = "{0} {1}")
	@CsvSource({
			"POST,   /1/resource/2",
			"PUT,    /1/entry/2",
			"PUT,    /1/metadata/2",
			"POST,   /1",
			"POST,   /1/merge",
			"POST,   /validator",
			"GET,    /1/resource/2",
			"PUT,    /1/resource/2/extra",
			"POST,   /auth/cookie"
	})
	void multipartToARouteThatDoesNotTakeIt_isAnswered415(String method, String path) throws Exception {
		MockHttpServletResponse response = new MockHttpServletResponse();
		MockFilterChain chain = new MockFilterChain();

		filter(-1).doFilter(request(method, path, MULTIPART, 10), response, chain);

		assertEquals(415, response.getStatus());
		assertNull(chain.getRequest(), "the request must not reach the parameter-reading filters");
	}

	@Test
	void nonMultipartRequest_passesWhateverTheRouteAndSize() throws Exception {
		MockFilterChain chain = new MockFilterChain();

		filter(4096).doFilter(request("PUT", "/1/entry/2", "application/json", 8192),
				new MockHttpServletResponse(), chain);

		assertNotNull(chain.getRequest());
	}

	@Test
	void contentLengthAboveMaxRequestSize_isAnswered413AndTheBodyDiscarded() throws Exception {
		MockHttpServletRequest request = request("PUT", "/1/resource/2", MULTIPART, 4097);
		MockHttpServletResponse response = new MockHttpServletResponse();
		MockFilterChain chain = new MockFilterChain();

		filter(4096).doFilter(request, response, chain);

		assertEquals(413, response.getStatus());
		assertEquals("Request size of 4097 bytes exceeds the maximum allowed size of 4096 bytes",
				JsonMapper.builder().build().readTree(response.getContentAsString()).get("error").asString());
		assertNull(chain.getRequest());
		assertTrue(request.getInputStream().isFinished(), "an unread body makes Jetty close the connection");
	}

	@Test
	void contentLengthAboveMaxRequestSizeOnEcho_isAnswered413InATextarea() throws Exception {
		// 5.x answered every /echo failure in a textarea, which EntryScape reads from an iframe.
		MockHttpServletResponse response = new MockHttpServletResponse();

		filter(4096).doFilter(request("POST", "/echo", MULTIPART, 4097), response, new MockFilterChain());

		assertEquals(413, response.getStatus());
		assertTrue(response.getContentType().startsWith("text/html"));
		assertEquals("<textarea>status:413\nRequest size of 4097 bytes exceeds the maximum allowed size of 4096 bytes"
				+ "</textarea>", response.getContentAsString());
	}

	@Test
	void discardingAnOversizedBody_stopsAfterTheDrainLimit() throws Exception {
		MockHttpServletRequest request = request("PUT", "/1/resource/2", MULTIPART,
				(int) MultipartRequestFilter.MAX_DRAIN_BYTES + 100_000);

		filter(4096).doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

		assertEquals(100_000, request.getInputStream().available());
	}

	@Test
	void expectContinue_isAnswered413WithoutReadingTheBody() throws Exception {
		// Reading would make Jetty send 100 Continue, and the client would upload the body for nothing.
		MockHttpServletRequest request = request("PUT", "/1/resource/2", MULTIPART, 4097);
		request.addHeader("Expect", "100-continue");
		MockHttpServletResponse response = new MockHttpServletResponse();

		filter(4096).doFilter(request, response, new MockFilterChain());

		assertEquals(413, response.getStatus());
		assertFalse(request.getInputStream().isFinished());
	}

	@Test
	void contentLengthAtMaxRequestSize_passes() throws Exception {
		MockFilterChain chain = new MockFilterChain();

		filter(4096).doFilter(request("PUT", "/1/resource/2", MULTIPART, 4096), new MockHttpServletResponse(), chain);

		assertNotNull(chain.getRequest());
	}

	private static MultipartRequestFilter filter(long maxRequestSize) {
		MultipartConfigElement config = new MultipartConfigElement(null, -1, maxRequestSize, 0);
		CorsProperties cors = mock(CorsProperties.class);
		when(cors.enabled()).thenReturn(false);
		return new MultipartRequestFilter(
				new StaticListableBeanFactory(Map.of("multipartConfigElement", config))
						.getBeanProvider(MultipartConfigElement.class),
				new ErrorResponseWriter(JsonMapper.builder().build()), cors,
				mock(EntryStoreCorsConfigurationSource.class));
	}

	private static MockHttpServletRequest request(String method, String path, String contentType, int size) {
		MockHttpServletRequest request = new MockHttpServletRequest(method, path);
		request.setContentType(contentType);
		request.setContent(new byte[size]);
		return request;
	}
}
