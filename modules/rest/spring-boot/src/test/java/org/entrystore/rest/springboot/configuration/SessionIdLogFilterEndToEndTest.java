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

import jakarta.servlet.ServletException;
import org.apache.commons.logging.LogFactory;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.Filter;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.WriterAppender;
import org.apache.logging.log4j.core.config.DefaultConfiguration;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.filter.ThresholdFilter;
import org.apache.logging.log4j.core.layout.PatternLayout;
import org.apache.logging.log4j.util.Supplier;
import org.entrystore.rest.springboot.util.JettySessionExceptions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.LoggerFactory;

import java.io.StringWriter;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Logs through the logging APIs that the application and its libraries use, into the default logger context with the
 * filter installed, and reads what the appenders write.
 */
class SessionIdLogFilterEndToEndTest {

	/** Has a logger configuration of its own, which writes to its own appender and accepts every event. */
	private static final String ACCEPTING_LOGGER = "org.entrystore.test.accepting";

	/** Gets a logger configuration only after the filter is installed, as {@code /management/loggers} adds one. */
	private static final String LATER_LOGGER = "org.entrystore.test.later";

	private final StringWriter output = new StringWriter();

	private LoggerContext loggerContext;

	private SessionIdLogFilterInstaller installer;

	@BeforeEach
	void installTheFilterOnAConfigurationWithAppenders() {
		loggerContext = (LoggerContext) LogManager.getContext(false);
		var configuration = new DefaultConfiguration();
		var root = configuration.getRootLogger();
		List.copyOf(root.getAppenders().keySet()).forEach(root::removeAppender);
		root.setLevel(Level.TRACE);
		root.addAppender(appender("root"), null, null);
		var accepting = new LoggerConfig(ACCEPTING_LOGGER, Level.TRACE, false);
		accepting.addFilter(ThresholdFilter.createFilter(Level.TRACE, Filter.Result.ACCEPT, Filter.Result.NEUTRAL));
		accepting.addAppender(appender("accepting"), null, null);
		configuration.addLogger(ACCEPTING_LOGGER, accepting);

		installer = new SessionIdLogFilterInstaller(loggerContext);
		loggerContext.setConfiguration(configuration);
	}

	@AfterEach
	void restoreTheConfiguration() {
		installer.destroy();
		loggerContext.reconfigure();
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("loggingCalls")
	void exceptionWithTheSessionId_isNotWrittenWhileAnotherExceptionIs(LoggingCall call) {
		call.log(ACCEPTING_LOGGER, JettySessionExceptions.readOfInvalidatedSession());
		call.log(ACCEPTING_LOGGER, new IllegalStateException("unrelated failure"));

		assertTrue(output.toString().contains("unrelated failure"), output::toString);
		assertFalse(output.toString().contains(JettySessionExceptions.SESSION_ID), output::toString);
	}

	@Test
	void loggerConfigurationAddedAfterTheInstallation_passesItsEventsToTheRootThatDeniesThem() {
		var configuration = loggerContext.getConfiguration();
		configuration.addLogger(LATER_LOGGER, new LoggerConfig(LATER_LOGGER, Level.TRACE, true));
		loggerContext.updateLoggers();
		var log = LogFactory.getLog(LATER_LOGGER);

		log.warn("Request failed", JettySessionExceptions.readOfInvalidatedSession());
		log.warn("Request failed", new IllegalStateException("unrelated failure"));

		assertTrue(output.toString().contains("unrelated failure"), output::toString);
		assertFalse(output.toString().contains(JettySessionExceptions.SESSION_ID), output::toString);
	}

	/** One way of logging an exception through one of the logging APIs. */
	@FunctionalInterface
	interface LoggingCall {
		void log(String loggerName, IllegalStateException exception);
	}

	static Stream<Named<LoggingCall>> loggingCalls() {
		return Stream.of(
				call("commons-logging, with the exception", (name, e) ->
						LogFactory.getLog(name).warn("Request failed", e)),
				call("commons-logging, with the exception as a cause", (name, e) ->
						LogFactory.getLog(name).error("Request failed", new RuntimeException("wrapped", e))),
				call("commons-logging, with the exception also in the message text", (name, e) ->
						LogFactory.getLog(name).error("Failed to complete request: " + e,
								new ServletException("Request processing failed: " + e, e))),
				call("SLF4J, with the exception", (name, e) ->
						LoggerFactory.getLogger(name).warn("Request failed", e)),
				call("SLF4J, with the exception after a parameter", (name, e) ->
						LoggerFactory.getLogger(name).warn("Request {} failed", "GET /", e)),
				call("SLF4J fluent, with the exception as the cause", (name, e) ->
						LoggerFactory.getLogger(name).atWarn().setMessage("Request failed").setCause(e).log()),
				call("Log4j, caught", (name, e) -> LogManager.getLogger(name).catching(e)),
				call("Log4j, as the message", (name, e) -> LogManager.getLogger(name).error(e)),
				// an array, as a single supplier would pick the overload that takes it as a plain parameter
				call("Log4j, as a supplied parameter", (name, e) ->
						LogManager.getLogger(name).error("Request failed: {}", new Supplier<?>[] {() -> e}))
		);
	}

	private static Named<LoggingCall> call(String description, LoggingCall call) {
		return Named.of(description, call);
	}

	private WriterAppender appender(String name) {
		var appender = WriterAppender.newBuilder()
				.setName(name)
				.setTarget(output)
				.setLayout(PatternLayout.newBuilder().withPattern("%m%n%ex").build())
				.build();
		appender.start();
		return appender;
	}
}
