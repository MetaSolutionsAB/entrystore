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

import org.apache.logging.log4j.Level;
import org.entrystore.rest.springboot.util.CapturingAppender;
import org.entrystore.rest.springboot.util.ErrorResponseWriter;
import org.entrystore.rest.springboot.util.JettySessionExceptions;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.HandlerExceptionResolver;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EndedSessionResponderTest {

	private final HandlerExceptionResolver resolver =
			new EndedSessionResponder(new ErrorResponseWriter(JsonMapper.builder().build())).exceptionResolver();

	private final MockHttpServletRequest request = new MockHttpServletRequest("POST", "/store/1/entry/2");

	private final MockHttpServletResponse response = new MockHttpServletResponse();

	@Test
	void resolver_answersASessionEndedDuringTheRequestWith500AndAWarningWithoutTheException() throws Exception {
		try (var appender = CapturingAppender.attachTo(EndedSessionResponder.class)) {
			var modelAndView = resolver.resolveException(request, response, null,
					new RuntimeException("wrapped", JettySessionExceptions.readOfInvalidatedSession()));

			assertNotNull(modelAndView);
			assertTrue(modelAndView.isEmpty());
			assertEquals(500, response.getStatus());
			assertTrue(response.getContentAsString().contains("Internal Server Error"), response.getContentAsString());
			assertEquals(1, appender.countAt(Level.WARN), appender.toString());
			assertTrue(appender.allMessages().noneMatch(message -> message.contains(JettySessionExceptions.SESSION_ID)),
					appender.toString());
			assertEquals(0, appender.thrownAt(Level.WARN).count());
		}
	}

	@Test
	void resolver_leavesAnotherExceptionToTheNextResolver() throws Exception {
		var modelAndView = resolver.resolveException(request, response, null, new IllegalStateException("other"));

		assertNull(modelAndView);
		assertEquals(200, response.getStatus());
		assertEquals("", response.getContentAsString());
	}
}
