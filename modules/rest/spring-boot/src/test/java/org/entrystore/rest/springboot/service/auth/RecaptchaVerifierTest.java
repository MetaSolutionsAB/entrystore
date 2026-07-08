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

package org.entrystore.rest.springboot.service.auth;

import org.entrystore.rest.springboot.model.exception.CustomResponseException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.ResponseActions;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class RecaptchaVerifierTest {

	private static final String VERIFIER_URL = "https://verifier.test/recaptcha/api/siteverify";
	private static final String SECRET = "test-secret";

	private MockRestServiceServer server;
	private RecaptchaVerifier verifier;

	@BeforeEach
	void primeVerifier() {
		// MockRestServiceServer must be bound to the same builder the RestClient is built from,
		// so the stubbed transport intercepts the verifier's siteverify POST.
		RestClient.Builder builder = RestClient.builder();
		server = MockRestServiceServer.bindTo(builder).build();
		verifier = new RecaptchaVerifier(builder.build());
		// url/secret are @Value-injected in production; seed them directly for this unit test.
		ReflectionTestUtils.setField(verifier, "url", VERIFIER_URL);
		ReflectionTestUtils.setField(verifier, "secret", SECRET);
	}

	private ResponseActions expectSiteverifyPost() {
		return server.expect(requestTo(VERIFIER_URL)).andExpect(method(HttpMethod.POST));
	}

	@Test
	void verify_transportFailure_throws503() {
		expectSiteverifyPost().andRespond(withException(new IOException("connect refused")));

		CustomResponseException ex = assertThrows(CustomResponseException.class,
			() -> verifier.verify("token", "203.0.113.1"));

		assertEquals(HttpStatus.SERVICE_UNAVAILABLE, ex.getStatus());
		assertInstanceOf(ResourceAccessException.class, ex.getCause());
	}

	@Test
	void verify_upstream5xx_throws503() {
		expectSiteverifyPost().andRespond(withStatus(HttpStatus.BAD_GATEWAY));

		CustomResponseException ex = assertThrows(CustomResponseException.class,
			() -> verifier.verify("token", null));

		assertEquals(HttpStatus.SERVICE_UNAVAILABLE, ex.getStatus());
		assertInstanceOf(HttpServerErrorException.class, ex.getCause());
	}

	// No dedicated UnknownHttpStatusCodeException case: verify() still catches it defensively, but
	// RestClient's default error handler classifies every error status by series (4xx -> client,
	// 5xx -> server), so that exception is unreachable through a real response and can only be
	// produced by force-mocking — which would be a tautological test.

	@Test
	void verify_upstream4xx_propagates() {
		// 4xx is deliberately not remapped to 503: a misconfigured secret must surface.
		expectSiteverifyPost().andRespond(withStatus(HttpStatus.BAD_REQUEST));

		HttpClientErrorException thrown = assertThrows(HttpClientErrorException.class,
			() -> verifier.verify("token", null));

		assertEquals(HttpStatus.BAD_REQUEST, thrown.getStatusCode());
	}

	@Test
	void verify_validResponse_returnsTrue() {
		MultiValueMap<String, String> expectedForm = new LinkedMultiValueMap<>();
		expectedForm.add("secret", SECRET);
		expectedForm.add("response", "token");

		expectSiteverifyPost()
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_FORM_URLENCODED))
			.andExpect(content().formData(expectedForm))
			.andRespond(withSuccess("{\"success\": true}", MediaType.APPLICATION_JSON));

		assertTrue(verifier.verify("token", null));
	}

	@Test
	void verify_invalidResponse_returnsFalse() {
		expectSiteverifyPost().andRespond(withSuccess("{\"success\": false}", MediaType.APPLICATION_JSON));

		assertFalse(verifier.verify("token", null));
	}
}
