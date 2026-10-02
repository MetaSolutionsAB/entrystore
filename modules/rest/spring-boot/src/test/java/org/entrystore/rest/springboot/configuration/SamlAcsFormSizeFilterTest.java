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

import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import org.eclipse.jetty.ee11.servlet.ServletContextHandler;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.util.unit.DataSize;

import java.io.BufferedReader;
import java.util.Enumeration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class SamlAcsFormSizeFilterTest {

	private static final int HALF_A_MEGABYTE = 512 * 1024;

	@Test
	void acsPost_getsTheSamlResponseLimit() throws Exception {
		var request = storeRequest("POST", "/auth/saml");

		var chain = runFilter(DataSize.ofKilobytes(32), request);

		assertEquals(HALF_A_MEGABYTE, request.getAttribute(ServletContextHandler.MAX_FORM_CONTENT_SIZE_KEY));
		assertNotNull(chain.getRequest(), "the request must continue down the chain");
	}

	@Test
	void otherFormPost_keepsTheGlobalLimit() throws Exception {
		var request = storeRequest("POST", "/auth/cookie");

		runFilter(DataSize.ofKilobytes(32), request);

		assertNull(request.getAttribute(ServletContextHandler.MAX_FORM_CONTENT_SIZE_KEY));
	}

	@Test
	void acsPost_keepsALargerGlobalLimit() throws Exception {
		var request = storeRequest("POST", "/login/saml2/sso/keycloak");

		runFilter(DataSize.ofMegabytes(2), request);

		assertEquals(2 * 1024 * 1024, request.getAttribute(ServletContextHandler.MAX_FORM_CONTENT_SIZE_KEY));
	}

	// Jetty fixes the limit when the form is first parsed, so reading a parameter here would apply the 32 KB limit
	// before the raised one is set. On the 5.x path SamlAcsRequestMatcher itself reads SAMLResponse.
	@Test
	void legacyAcsPost_neverReadsTheRequestBody() throws Exception {
		var request = storeRequest("POST", "/auth/saml");
		var bodyGuard = new BodyReadingForbidden(request);

		new SamlAcsFormSizeFilter(DataSize.ofKilobytes(32))
				.doFilter(bodyGuard, new MockHttpServletResponse(), new MockFilterChain());

		assertEquals(HALF_A_MEGABYTE, request.getAttribute(ServletContextHandler.MAX_FORM_CONTENT_SIZE_KEY));
	}

	private static MockHttpServletRequest storeRequest(String method, String path) {
		var request = new MockHttpServletRequest(method, "/store" + path);
		request.setContextPath("/store");
		request.setContentType("application/x-www-form-urlencoded");
		return request;
	}

	private static MockFilterChain runFilter(DataSize globalLimit, MockHttpServletRequest request) throws Exception {
		var chain = new MockFilterChain();
		new SamlAcsFormSizeFilter(globalLimit).doFilter(request, new MockHttpServletResponse(), chain);
		return chain;
	}

	private static final class BodyReadingForbidden extends HttpServletRequestWrapper {

		BodyReadingForbidden(HttpServletRequest request) {
			super(request);
		}

		@Override
		public String getParameter(String name) {
			throw new AssertionError("getParameter parses the form");
		}

		@Override
		public Map<String, String[]> getParameterMap() {
			throw new AssertionError("getParameterMap parses the form");
		}

		@Override
		public Enumeration<String> getParameterNames() {
			throw new AssertionError("getParameterNames parses the form");
		}

		@Override
		public String[] getParameterValues(String name) {
			throw new AssertionError("getParameterValues parses the form");
		}

		@Override
		public ServletInputStream getInputStream() {
			throw new AssertionError("getInputStream reads the body");
		}

		@Override
		public BufferedReader getReader() {
			throw new AssertionError("getReader reads the body");
		}
	}
}
