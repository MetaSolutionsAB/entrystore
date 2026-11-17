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

import jakarta.servlet.DispatcherType;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import org.apache.logging.log4j.Level;
import org.entrystore.rest.springboot.util.CapturingAppender;
import org.entrystore.rest.springboot.util.ErrorResponseWriter;
import org.entrystore.rest.springboot.util.JettySessionExceptions;
import org.junit.jupiter.api.Test;
import org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterProperties;
import org.springframework.core.annotation.Order;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

class EndedSessionFilterTest {

	private final EndedSessionFilter filter = new EndedSessionFilter(
			new EndedSessionResponder(new ErrorResponseWriter(JsonMapper.builder().build())));

	private final MockHttpServletRequest request = new MockHttpServletRequest("GET", "/store/auth/user");

	private final MockHttpServletResponse response = new MockHttpServletResponse();

	@Test
	void sessionEndedDuringTheRequest_isAnsweredWith500AndAWarningWithoutTheException() throws Exception {
		try (var appender = CapturingAppender.attachTo(EndedSessionResponder.class)) {
			filter.doFilter(request, response, failingWith(JettySessionExceptions.readOfInvalidatedSession()));

			assertEquals(500, response.getStatus());
			assertTrue(response.getContentType().startsWith("application/json"), response.getContentType());
			assertTrue(response.getContentAsString().contains("Internal Server Error"), response.getContentAsString());
			assertEquals(1, appender.countAt(Level.WARN), appender.toString());
			assertTrue(appender.messagesAt(Level.WARN).allMatch(message -> message.contains("GET /store/auth/user")),
					appender.toString());
			assertTrue(appender.allMessages().noneMatch(message -> message.contains(JettySessionExceptions.SESSION_ID)),
					appender.toString());
			assertEquals(0, appender.thrownAt(Level.WARN).count());
		}
	}

	@Test
	void sessionEndedDuringTheRequest_isAnsweredWith500WhenAServletExceptionWrapsIt() throws Exception {
		var wrapped = new ServletException("Request processing failed",
				JettySessionExceptions.writeOfInvalidatedSession());

		filter.doFilter(request, response, failingWith(wrapped));

		assertEquals(500, response.getStatus());
		assertFalse(response.getContentAsString().contains(JettySessionExceptions.SESSION_ID));
	}

	@Test
	void sessionEndedDuringAnAsynchronousDispatch_isAnsweredWith500() throws Exception {
		request.setDispatcherType(DispatcherType.ASYNC);

		filter.doFilter(request, response, failingWith(JettySessionExceptions.readOfInvalidatedSession()));

		assertEquals(500, response.getStatus());
	}

	@Test
	void sessionEndedAfterTheResponseWasCommitted_writesNothingMore() throws Exception {
		response.setCommitted(true);

		filter.doFilter(request, response, failingWith(JettySessionExceptions.readOfInvalidatedSession()));

		assertEquals(200, response.getStatus());
		assertEquals("", response.getContentAsString());
	}

	@Test
	void filter_runsInsideTheBodyDrainAndAheadOfSpringSecurity() {
		int order = EndedSessionFilter.class.getAnnotation(Order.class).value();

		assertTrue(order > RejectedBodyDrainFilter.class.getAnnotation(Order.class).value());
		assertTrue(order < SecurityFilterProperties.DEFAULT_FILTER_ORDER);
	}

	@Test
	void otherException_passesUnchanged() throws Exception {
		var failure = new IllegalStateException("other");
		var chain = failingWith(failure);

		var thrown = assertThrows(IllegalStateException.class, () -> filter.doFilter(request, response, chain));

		assertSame(failure, thrown);
	}

	private static FilterChain failingWith(Exception exception) throws Exception {
		var chain = mock(FilterChain.class);
		doAnswer(JettySessionExceptions.throwing(exception)).when(chain).doFilter(any(), any());
		return chain;
	}
}
