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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.accept.HeaderContentNegotiationStrategy;
import org.springframework.web.context.request.ServletWebRequest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EntryEndpointContentNegotiationStrategyTest {

	private static final String BROWSER_ACCEPT =
			"text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8";

	private final EntryEndpointContentNegotiationStrategy strategy =
			new EntryEndpointContentNegotiationStrategy(new HeaderContentNegotiationStrategy());

	@Test
	void entryGet_formatParameterWinsOverAcceptHeader() throws Exception {
		MockHttpServletRequest request = request("GET", "/1/entry/2", "application/json");
		request.setParameter("format", "text/turtle");

		assertEquals(List.of(MediaType.parseMediaType("text/turtle")), strategy.resolveMediaTypes(web(request)));
	}

	@Test
	void entryGet_formatWithUnencodedPlusIsRestored() throws Exception {
		// An unencoded '+' in the query string reaches the servlet as a space.
		MockHttpServletRequest request = request("GET", "/1/entry/2", "*/*");
		request.setParameter("format", "application/ld json");

		assertEquals(List.of(MediaType.parseMediaType("application/ld+json")), strategy.resolveMediaTypes(web(request)));
	}

	@Test
	void entryGet_browserAcceptWithoutFormatSelectsRdfXml() throws Exception {
		MockHttpServletRequest request = request("GET", "/1/entry/2", BROWSER_ACCEPT);

		assertEquals(List.of(MediaType.parseMediaType("application/rdf+xml")), strategy.resolveMediaTypes(web(request)));
	}

	@ParameterizedTest(name = "format={0}, Accept={1}")
	@CsvSource(delimiter = '|', nullValues = "NULL", value = {
			"not a media type | */*",
			"*/*              | */*",
			"application/*    | */*",
			"text/*           | */*",
			"NULL             | application/rdf+soup",
			"NULL             | application/json;q=0"
	})
	void entryGet_unparseableOrNonConcreteFormatOrUnmatchedAcceptIsNotAcceptable(String format, String accept) {
		MockHttpServletRequest request = request("GET", "/1/entry/2", accept);
		if (format != null) {
			request.setParameter("format", format);
		}

		assertThrows(HttpMediaTypeNotAcceptableException.class, () -> strategy.resolveMediaTypes(web(request)));
	}

	@Test
	void entryHead_formatParameterWinsOverAcceptHeaderAsOnGet() throws Exception {
		MockHttpServletRequest request = request("HEAD", "/1/entry/2", "application/json");
		request.setParameter("format", "text/turtle");

		assertEquals(List.of(MediaType.parseMediaType("text/turtle")), strategy.resolveMediaTypes(web(request)));
	}

	@Test
	void entryGet_allAcceptHeadersAreConsidered() throws Exception {
		MockHttpServletRequest request = request("GET", "/1/entry/2", "text/html");
		request.addHeader("Accept", "text/turtle");

		assertEquals(List.of(MediaType.parseMediaType("text/turtle")), strategy.resolveMediaTypes(web(request)));
	}

	@Test
	void otherPath_formatParameterIsIgnored() throws Exception {
		MockHttpServletRequest request = request("GET", "/1/metadata/2", "application/json");
		request.setParameter("format", "text/turtle");

		assertEquals(List.of(MediaType.APPLICATION_JSON), strategy.resolveMediaTypes(web(request)));
	}

	@Test
	void entryPut_formatParameterIsIgnored() throws Exception {
		MockHttpServletRequest request = request("PUT", "/1/entry/2", "application/json");
		request.setParameter("format", "text/turtle");

		assertEquals(List.of(MediaType.APPLICATION_JSON), strategy.resolveMediaTypes(web(request)));
	}

	private static MockHttpServletRequest request(String method, String servletPath, String accept) {
		MockHttpServletRequest request = new MockHttpServletRequest(method, servletPath);
		request.setServletPath(servletPath);
		request.addHeader("Accept", accept);
		return request;
	}

	private static ServletWebRequest web(MockHttpServletRequest request) {
		return new ServletWebRequest(request);
	}
}
