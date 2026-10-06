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

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.DefaultCsrfToken;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class HeaderOnlyForMultipartCsrfTokenRequestHandlerTest {

	private static final CsrfToken TOKEN = new DefaultCsrfToken("X-XSRF-TOKEN", "_csrf", "token");

	private final HeaderOnlyForMultipartCsrfTokenRequestHandler handler = new HeaderOnlyForMultipartCsrfTokenRequestHandler();

	@Test
	void multipartRequestWithoutHeader_resolvesNoTokenAndLeavesTheBodyUnparsed() {
		assertNull(handler.resolveCsrfTokenValue(multipartFailingOnParameterRead(), TOKEN));
	}

	@Test
	void multipartRequestWithHeader_resolvesTheHeader() {
		MockHttpServletRequest request = multipartFailingOnParameterRead();
		request.addHeader("X-XSRF-TOKEN", "from-header");

		assertEquals("from-header", handler.resolveCsrfTokenValue(request, TOKEN));
	}

	@Test
	void formRequestWithoutHeader_stillFallsBackToTheParameter() {
		MockHttpServletRequest request = new MockHttpServletRequest("POST", "/x");
		request.setContentType("application/x-www-form-urlencoded");
		request.addParameter("_csrf", "from-form");

		assertEquals("from-form", handler.resolveCsrfTokenValue(request, TOKEN));
	}

	/** Reading a parameter of a multipart request would make Jetty parse the whole upload. */
	private static MockHttpServletRequest multipartFailingOnParameterRead() {
		MockHttpServletRequest request = new MockHttpServletRequest("PUT", "/1/resource/2") {
			@Override
			public String getParameter(String name) {
				throw new AssertionError("parameter '" + name + "' read from a multipart request");
			}
		};
		request.setContentType("multipart/form-data; boundary=x");
		return request;
	}
}
