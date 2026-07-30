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

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.entrystore.rest.springboot.service.auth.LoginAttemptService;
import org.entrystore.rest.springboot.util.ErrorResponseWriter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.json.JsonMapper;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FormLoginAuthenticationFailureHandlerTest {

	@Mock
	private LoginAttemptService loginAttemptService;

	private FormLoginAuthenticationFailureHandler handler;
	private MockHttpServletRequest request;
	private MockHttpServletResponse response;

	@BeforeEach
	void setUp() {
		// Constructed explicitly rather than via @InjectMocks: every assertion here inspects the real
		// serialized 401 body, so ErrorResponseWriter must be a working instance and not a Mockito stub.
		handler = new FormLoginAuthenticationFailureHandler(loginAttemptService,
				new ErrorResponseWriter(JsonMapper.builder().build()));
		request = new MockHttpServletRequest("POST", "/auth/cookie");
		response = new MockHttpServletResponse();
	}

	@Test
	void badCredentials_recordsFailureAndEmitsUnified401() throws Exception {
		request.setParameter("auth_username", "alice@example.com");

		handler.onAuthenticationFailure(request, response, new BadCredentialsException("bad password"));

		verify(loginAttemptService).recordFailure("alice@example.com");
		assertUnifiedUnauthorizedJson();
	}

	@Test
	void disabledUser_emitsUnified401WithoutLeakingState() throws Exception {
		request.setParameter("auth_username", "alice@example.com");

		handler.onAuthenticationFailure(request, response, new DisabledException("account disabled"));

		// Disabled accounts must produce the same response as bad credentials so the body
		// cannot be used to enumerate account state.
		assertUnifiedUnauthorizedJson();
	}

	@Test
	void unknownUser_emitsUnified401() throws Exception {
		request.setParameter("auth_username", "ghost@example.com");

		handler.onAuthenticationFailure(request, response, new UsernameNotFoundException("not found"));

		verify(loginAttemptService).recordFailure("ghost@example.com");
		assertUnifiedUnauthorizedJson();
	}

	@Test
	void recordFailureThrowing_stillEmits401AndIncrementsCounter() throws Exception {
		// The whole point of the try/catch around recordFailure: a bookkeeping failure
		// must never break the normalized response contract. The Micrometer counter
		// makes sustained degradation alertable beyond log scraping.
		Counter counter = new SimpleMeterRegistry().counter("auth.loginattempt.record_failure_error");
		when(loginAttemptService.getRecordFailureErrorCounter()).thenReturn(counter);
		doThrow(new RuntimeException("caffeine boom")).when(loginAttemptService).recordFailure(anyString());
		request.setParameter("auth_username", "alice@example.com");

		handler.onAuthenticationFailure(request, response, new BadCredentialsException("bad password"));

		assertUnifiedUnauthorizedJson();
		assertEquals(1.0, counter.count(), "Caffeine fault should increment the record_failure_error counter");
	}

	@Test
	void missingUsername_doesNotCallRecordFailure() throws Exception {
		handler.onAuthenticationFailure(request, response, new BadCredentialsException("bad password"));

		verify(loginAttemptService, never()).recordFailure(anyString());
		assertUnifiedUnauthorizedJson();
	}

	private void assertUnifiedUnauthorizedJson() throws java.io.UnsupportedEncodingException {
		assertEquals(401, response.getStatus());
		String contentType = response.getContentType();
		assertNotNull(contentType);
		assertTrue(contentType.contains("application/json"), "Unexpected content type: " + contentType);
		String body = response.getContentAsString();
		assertTrue(body.contains("\"status\":401"), "Body missing status=401: " + body);
		assertTrue(body.contains("\"error\":\"Unauthorized\""), "Body missing error=Unauthorized: " + body);
		assertTrue(body.contains("\"path\":\"/auth/cookie\""), "Body missing path: " + body);
	}
}
