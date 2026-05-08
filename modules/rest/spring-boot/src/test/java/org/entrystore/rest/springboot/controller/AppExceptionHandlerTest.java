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

package org.entrystore.rest.springboot.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.entrystore.rest.springboot.model.api.ErrorResponse;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.concurrent.RejectedExecutionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class AppExceptionHandlerTest {

	private final AppExceptionHandler handler = new AppExceptionHandler();

	@Test
	void handleRejectedExecution_returns503WithRetryMessage() {
		// Pins the bounded-executor → 503 contract: AbortPolicy on the mvc.async ThreadPoolTaskExecutor
		// throws RejectedExecutionException when the queue is full, and this handler must map it to a
		// 503 with a retry-friendly message. A regression that drops or relabels this @ExceptionHandler
		// would otherwise surface as 500 (caught by the generic Exception handler) and the client would
		// have no signal to back off.
		HttpServletRequest req = Mockito.mock(HttpServletRequest.class);
		Mockito.when(req.getRequestURI()).thenReturn("/sparql");

		ResponseEntity<ErrorResponse> response = handler.handleRejectedExecution(
				new RejectedExecutionException("queue is full"), req);

		assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
		ErrorResponse body = response.getBody();
		assertNotNull(body, "Expected non-null ErrorResponse body");
		assertEquals(503, body.status());
		assertEquals("/sparql", body.path());
		assertEquals("Server temporarily overloaded; retry later", body.error());
	}

	@Test
	void handleGenericException_unrelatedRuntimeException_returns500NotMappedTo503() {
		// Pins the boundary between handleRejectedExecution and handleGenericException: a
		// regression widening the @ExceptionHandler value on handleRejectedExecution from
		// RejectedExecutionException.class to a supertype (e.g. RuntimeException.class) would
		// silently start mapping all runtime errors to 503 with the back-off message. This test
		// fails iff that boundary is breached — a plain RuntimeException must still hit the
		// generic 500 path.
		HttpServletRequest req = Mockito.mock(HttpServletRequest.class);
		Mockito.when(req.getRequestURI()).thenReturn("/sparql");

		ResponseEntity<ErrorResponse> response = handler.handleGenericException(
				new RuntimeException("some unrelated runtime error"), req);

		assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
		ErrorResponse body = response.getBody();
		assertNotNull(body, "Expected non-null ErrorResponse body");
		assertEquals(500, body.status());
	}
}
