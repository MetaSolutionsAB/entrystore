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

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.eclipse.jetty.http.BadMessageException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mock.web.MockAsyncContext;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
	void completedRequest_isLoggedInOneInfoLine() throws Exception {
		var request = new MockHttpServletRequest("GET", "/store/1/entry/2");
		request.setQueryString("includeAll&limit=50");
		request.setRemoteAddr("192.0.2.1");
		FilterChain chain = (req, res) -> ((HttpServletResponse) res).setStatus(404);

		filter.doFilter(request, new MockHttpServletResponse(), chain);

		List<String> lines = loggedLines(Level.INFO);
		assertEquals(1, lines.size(), lines.toString());
		assertTrue(lines.getFirst().matches("GET /store/1/entry/2\\?includeAll&limit=50 404 \\d+ms client=192\\.0\\.2\\.1"),
				lines.getFirst());
	}

	@Test
	void completedRequest_withoutQueryString_logsThePathOnly() throws Exception {
		var request = new MockHttpServletRequest("POST", "/store/auth/cookie");
		request.setRemoteAddr("192.0.2.1");

		filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

		assertTrue(loggedLines(Level.INFO).getFirst().matches("POST /store/auth/cookie 200 \\d+ms client=192\\.0\\.2\\.1"),
				loggedLines(Level.INFO).toString());
	}

	@Test
	void completedRequest_redactsSensitiveQueryParameters() throws Exception {
		var request = new MockHttpServletRequest("GET", "/auth/pwreset");
		request.setQueryString("confirm=secret-token-abc&q=foo");

		filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

		String line = loggedLines(Level.INFO).getFirst();
		assertFalse(line.contains("secret-token-abc"), line);
		assertTrue(line.contains("?confirm=***&q=foo "), line);
	}

	@Test
	void handlerThatThrows_isLoggedWithStatus500AndTheExceptionPropagates() {
		var request = new MockHttpServletRequest("GET", "/store/1/entry/2");
		FilterChain chain = (req, res) -> {
			throw new ServletException("handler failed");
		};

		assertThrows(ServletException.class, () -> filter.doFilter(request, new MockHttpServletResponse(), chain));

		List<String> lines = loggedLines(Level.INFO);
		assertEquals(1, lines.size(), lines.toString());
		assertTrue(lines.getFirst().startsWith("GET /store/1/entry/2 500 "), lines.getFirst());
	}

	@Test
	void filterThrowingABadMessage_isLoggedWithTheStatusJettySends() {
		var request = new MockHttpServletRequest("POST", "/store/auth/cookie");
		FilterChain chain = (req, res) -> {
			throw new ServletException(new BadMessageException("Unable to parse form content"));
		};

		assertThrows(ServletException.class, () -> filter.doFilter(request, new MockHttpServletResponse(), chain));

		assertTrue(loggedLines(Level.INFO).getFirst().startsWith("POST /store/auth/cookie 400 "),
				loggedLines(Level.INFO).toString());
	}

	@Test
	void exceptionAfterTheResponseIsCommitted_isLoggedWithTheStatusAlreadySent() {
		var request = new MockHttpServletRequest("GET", "/store/1/resource/2");
		var response = new MockHttpServletResponse();
		FilterChain chain = (req, res) -> {
			response.setStatus(206);
			response.setCommitted(true);
			throw new IOException("client went away");
		};

		assertThrows(IOException.class, () -> filter.doFilter(request, response, chain));

		assertTrue(loggedLines(Level.INFO).getFirst().startsWith("GET /store/1/resource/2 206 "),
				loggedLines(Level.INFO).toString());
	}

	@Test
	void asyncRequest_isLoggedWhenItCompletesWithItsFinalStatus() throws Exception {
		var request = new MockHttpServletRequest("GET", "/store/sparql");
		request.setAsyncSupported(true);
		var response = new MockHttpServletResponse();
		FilterChain chain = (req, res) -> req.startAsync();

		filter.doFilter(request, response, chain);
		assertTrue(loggedLines(Level.INFO).isEmpty(), "logged before the async response completed");

		response.setStatus(503);
		((MockAsyncContext) request.getAsyncContext()).complete();

		List<String> lines = loggedLines(Level.INFO);
		assertEquals(1, lines.size(), lines.toString());
		assertTrue(lines.getFirst().startsWith("GET /store/sparql 503 "), lines.getFirst());
	}

	@Test
	void requestStart_isLoggedAtDebugOnlyAndRedacted() throws Exception {
		dedicatedLoggerConfig.setLevel(Level.DEBUG);
		loggerContext.updateLoggers();
		var request = new MockHttpServletRequest("GET", "/auth/pwreset");
		request.setQueryString("confirm=secret-token-abc");
		request.setRemoteAddr("192.0.2.1");

		filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

		assertEquals(List.of("GET /auth/pwreset?confirm=*** started client=192.0.2.1"), loggedLines(Level.DEBUG));
		assertEquals(1, loggedLines(Level.INFO).size());
	}

	private List<String> loggedLines(Level level) {
		return capturingAppender.events.stream()
				.filter(e -> e.getLevel() == level)
				.map(e -> e.getMessage().getFormattedMessage())
				.toList();
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
