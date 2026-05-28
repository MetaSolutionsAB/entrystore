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

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RequestResponseLoggingFilterTest {

	private final RequestResponseLoggingFilter filter = new RequestResponseLoggingFilter();

	private static final String FILTER_LOGGER_NAME = RequestResponseLoggingFilter.class.getName();

	private CapturingAppender capturingAppender;
	private LoggerConfig dedicatedLoggerConfig;
	private LoggerContext loggerContext;

	@BeforeEach
	void attachAppender() {
		loggerContext = (LoggerContext) LogManager.getContext(false);
		Configuration config = loggerContext.getConfiguration();
		// Install a dedicated LoggerConfig for the filter so we can pin level=INFO and attach
		// our capturing appender. The test does not bootstrap Spring Boot, so the starter's
		// default log4j2.xml (root=INFO) is not applied; Log4j2's built-in default is
		// root=ERROR, which would otherwise swallow the filter's log.info(...).
		capturingAppender = new CapturingAppender("CapturingAppender-" + System.nanoTime());
		capturingAppender.start();
		dedicatedLoggerConfig = new LoggerConfig(FILTER_LOGGER_NAME, Level.INFO, false);
		dedicatedLoggerConfig.addAppender(capturingAppender, null, null);
		config.addLogger(FILTER_LOGGER_NAME, dedicatedLoggerConfig);
		loggerContext.updateLoggers();
	}

	@AfterEach
	void detachAppender() {
		loggerContext.getConfiguration().removeLogger(FILTER_LOGGER_NAME);
		loggerContext.updateLoggers();
		capturingAppender.stop();
	}

	@ParameterizedTest(name = "shouldNotFilter({0}) == {1}")
	@CsvSource({
			"/favicon.ico,       true",
			"/management,        false",
			"/management/,       false",
			"/management/metrics,false",
			"/management/shutdown,false",
			"/actuator/health,   false",
			"/actuator,          false",
			"/store/entry/1,     false",
			"/search,            false",
			"/auth/login,        false",
			"/,                  false"
	})
	void shouldNotFilter_matchesManagementAndFaviconOnly(String pathString, boolean expected) {
		var request = new MockHttpServletRequest();
		request.setServletPath(pathString);

		assertEquals(expected, filter.shouldNotFilter(request));
	}

	@Test
	void requestLog_redactsSensitiveQueryParameters() throws Exception {
		// Verifies the call-site wiring: the filter's REQUEST log line goes through
		// HttpQueryRedactor so a 'confirm' token in the query string is replaced with
		// '***' in the emitted LogEvent, while non-sensitive parameters survive verbatim.
		var request = new MockHttpServletRequest("GET", "/auth/pwreset");
		request.setQueryString("confirm=secret-token-abc&q=foo");
		var response = new MockHttpServletResponse();
		var chain = new MockFilterChain();

		filter.doFilter(request, response, chain);

		String requestLine = capturingAppender.events.stream()
				.map(LogEvent::getMessage)
				.map(m -> m.getFormattedMessage())
				.filter(m -> m.startsWith("REQUEST "))
				.findFirst()
				.orElseThrow(() -> new AssertionError("Filter did not emit a REQUEST log line"));

		assertFalse(requestLine.contains("secret-token-abc"),
				"sensitive token must not appear verbatim in the log line: " + requestLine);
		assertTrue(requestLine.contains("confirm=***"),
				"redacted form of confirm must be present in the log line: " + requestLine);
		assertTrue(requestLine.contains("q=foo"),
				"non-sensitive query parameter must survive redaction: " + requestLine);
	}

	@Test
	void requestLog_withNoQueryString_rendersCleanlyWithoutException() throws Exception {
		// Defensive: a request with no query string (the common case for GET requests
		// without parameters) must traverse the filter without throwing and the
		// captured log line must reflect the absence as `query=null`.
		var request = new MockHttpServletRequest("GET", "/auth/user");
		// Intentionally no setQueryString — request.getQueryString() returns null.
		var response = new MockHttpServletResponse();
		var chain = new MockFilterChain();

		filter.doFilter(request, response, chain);

		String requestLine = capturingAppender.events.stream()
				.map(LogEvent::getMessage)
				.map(m -> m.getFormattedMessage())
				.filter(m -> m.startsWith("REQUEST "))
				.findFirst()
				.orElseThrow(() -> new AssertionError("Filter did not emit a REQUEST log line"));

		assertTrue(requestLine.contains("query=null"),
				"absent query string must render as `query=null`: " + requestLine);
	}

	/**
	 * Minimal Log4j2 appender that records every {@link LogEvent} it receives.
	 * Project uses {@code spring-boot-starter-log4j2}; SLF4J calls in the
	 * filter route through the Log4j2 binding, so attaching here observes
	 * the real call-site emission.
	 */
	private static final class CapturingAppender extends AbstractAppender {
		final List<LogEvent> events = new CopyOnWriteArrayList<>();

		CapturingAppender(String name) {
			super(name, null, null, true, org.apache.logging.log4j.core.config.Property.EMPTY_ARRAY);
		}

		@Override
		public void append(LogEvent event) {
			events.add(event.toImmutable());
		}
	}
}
