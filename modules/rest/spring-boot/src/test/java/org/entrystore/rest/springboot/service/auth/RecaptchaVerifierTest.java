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

import org.entrystore.config.Config;
import org.entrystore.repository.config.Settings;
import org.entrystore.rest.springboot.model.exception.CustomResponseException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.client.UnknownHttpStatusCodeException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RecaptchaVerifierTest {

	private static final String VERIFIER_URL = "https://verifier.test/recaptcha/api/siteverify";
	private static final String SECRET = "test-secret";

	@Mock
	private Config esConfig;

	@Mock
	private RestTemplate recaptchaRestTemplate;

	@InjectMocks
	private RecaptchaVerifier verifier;

	@BeforeEach
	void primeConfig() {
		when(esConfig.getString(eq(Settings.AUTH_RECAPTCHA_URL), anyString())).thenReturn(VERIFIER_URL);
		when(esConfig.getString(Settings.AUTH_RECAPTCHA_PRIVATE_KEY)).thenReturn(SECRET);
		verifier.init();
	}

	@Test
	void verify_transportFailure_throws503() {
		ResourceAccessException cause = new ResourceAccessException("connect refused");
		when(recaptchaRestTemplate.postForEntity(anyString(), any(HttpEntity.class), eq(String.class)))
			.thenThrow(cause);

		CustomResponseException ex = assertThrows(CustomResponseException.class,
			() -> verifier.verify("token", "203.0.113.1"));

		assertEquals(HttpStatus.SERVICE_UNAVAILABLE, ex.getStatus());
		assertEquals(cause, ex.getCause());
	}

	@Test
	void verify_upstream5xx_throws503() {
		HttpServerErrorException cause = HttpServerErrorException.create(
			HttpStatus.BAD_GATEWAY, "Bad Gateway", new HttpHeaders(), new byte[0], null);
		when(recaptchaRestTemplate.postForEntity(anyString(), any(HttpEntity.class), eq(String.class)))
			.thenThrow(cause);

		CustomResponseException ex = assertThrows(CustomResponseException.class,
			() -> verifier.verify("token", null));

		assertEquals(HttpStatus.SERVICE_UNAVAILABLE, ex.getStatus());
		assertEquals(cause, ex.getCause());
	}

	@Test
	void verify_unknownHttpStatus_throws503() {
		UnknownHttpStatusCodeException cause = new UnknownHttpStatusCodeException(
			599, "Network Connect Timeout", new HttpHeaders(), new byte[0], null);
		when(recaptchaRestTemplate.postForEntity(anyString(), any(HttpEntity.class), eq(String.class)))
			.thenThrow(cause);

		CustomResponseException ex = assertThrows(CustomResponseException.class,
			() -> verifier.verify("token", null));

		assertEquals(HttpStatus.SERVICE_UNAVAILABLE, ex.getStatus());
		assertInstanceOf(UnknownHttpStatusCodeException.class, ex.getCause());
	}

	@Test
	void verify_upstream4xx_propagates() {
		HttpClientErrorException cause = HttpClientErrorException.create(
			HttpStatus.BAD_REQUEST, "Bad Request", new HttpHeaders(), new byte[0], null);
		when(recaptchaRestTemplate.postForEntity(anyString(), any(HttpEntity.class), eq(String.class)))
			.thenThrow(cause);

		HttpClientErrorException thrown = assertThrows(HttpClientErrorException.class,
			() -> verifier.verify("token", null));

		assertEquals(cause, thrown);
	}

	@Test
	void verify_validResponse_returnsTrue() {
		when(recaptchaRestTemplate.postForEntity(anyString(), any(HttpEntity.class), eq(String.class)))
			.thenReturn(ResponseEntity.ok("{\"success\": true}"));

		assertTrue(verifier.verify("token", null));
	}

	@Test
	void verify_invalidResponse_returnsFalse() {
		when(recaptchaRestTemplate.postForEntity(anyString(), any(HttpEntity.class), eq(String.class)))
			.thenReturn(ResponseEntity.ok("{\"success\": false}"));

		assertFalse(verifier.verify("token", null));
	}
}
