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
import org.apache.logging.log4j.core.Filter;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.DefaultConfiguration;
import org.apache.logging.log4j.core.filter.ThresholdFilter;
import org.apache.logging.log4j.core.impl.Log4jLogEvent;
import org.apache.logging.log4j.message.SimpleMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.Method;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionIdLogFilterTest {

	private static final String REGISTRY_LOGGER = "org.springframework.security.core.session.SessionRegistryImpl";

	private static final String FILTER_CHAIN_LOGGER = "org.springframework.security.web.FilterChainProxy";

	private LoggerContext loggerContext;

	@BeforeEach
	void startLoggerContext() {
		loggerContext = new LoggerContext(SessionIdLogFilterTest.class.getSimpleName());
		loggerContext.start(configurationAt(Level.TRACE));
		new SessionIdLogFilterInstaller(loggerContext);
	}

	@AfterEach
	void stopLoggerContext() {
		loggerContext.stop();
	}

	@ParameterizedTest(name = "{0} at {1}")
	@CsvSource(delimiter = '|', textBlock = """
			org.springframework.security.core.session.SessionRegistryImpl                                 | DEBUG
			org.springframework.security.web.session.ConcurrentSessionFilter                              | TRACE
			org.springframework.security.web.session                                                      | DEBUG
			org.springframework.security.web.authentication.session.ChangeSessionIdAuthenticationStrategy | DEBUG
			org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler           | DEBUG
			org.springframework.security.web.context.HttpSessionSecurityContextRepository                 | TRACE
			org.entrystore.rest.springboot.security.SessionContextRepository                              | DEBUG
			org.eclipse.jetty.server.Server                                                               | DEBUG
			org.eclipse.jetty.io.SocketChannelEndPoint                                                    | TRACE
			org.eclipse.jetty                                                                             | DEBUG
			org.eclipse.jetty.session.ManagedSession                                                      | DEBUG
			org.eclipse.jetty.session.AbstractSessionManager                                              | INFO
			org.eclipse.jetty.session.DefaultSessionIdManager                                             | WARN
			org.springframework.web.servlet.DispatcherServlet                                             | TRACE
			""")
	void coveredCategory_isNotLoggedBelowItsLowestLevelEvenWhenItsLevelAllowsIt(String loggerName, String level) {
		assertFalse(loggerContext.getLogger(loggerName).isEnabled(Level.valueOf(level)));
	}

	@ParameterizedTest(name = "{0} at {1}")
	@CsvSource(delimiter = '|', textBlock = """
			org.springframework.security.core.session.SessionRegistryImpl    | INFO
			org.entrystore.rest.springboot.security.SessionContextRepository | INFO
			org.eclipse.jetty.server.Server                                  | INFO
			org.eclipse.jetty.session.AbstractSessionManager                 | ERROR
			org.springframework.web.servlet.DispatcherServlet                | DEBUG
			""")
	void coveredCategory_isLoggedAtItsLowestLevel(String loggerName, String level) {
		assertTrue(loggerContext.getLogger(loggerName).isEnabled(Level.valueOf(level)));
	}

	@ParameterizedTest
	@ValueSource(strings = {
			"org.springframework.security.web.sessionx.Other",
			FILTER_CHAIN_LOGGER,
			"org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter",
			"org.springframework.security",
			"org.eclipse",
			"org.eclipse.jettyx.Other",
			"org.entrystore.rest.springboot.security.SessionContextRepositoryHelper"
	})
	void otherLogger_isLoggedAtTraceAsItsLevelAllows(String loggerName) {
		assertTrue(loggerContext.getLogger(loggerName).isEnabled(Level.TRACE));
	}

	@Test
	void allowedEvent_isLeftToTheLevelOfItsLogger() {
		loggerContext.setConfiguration(configurationAt(Level.WARN));

		assertFalse(loggerContext.getLogger(FILTER_CHAIN_LOGGER).isEnabled(Level.DEBUG));
		assertFalse(loggerContext.getLogger("org.eclipse.jetty.server.Server").isEnabled(Level.INFO));
	}

	@Test
	void coveredCategory_isNotLoggedAtDebugWhenAnEarlierContextWideFilterAccepts() {
		loggerContext.setConfiguration(configurationAtWarnWithAnAcceptingFilter());

		assertFalse(loggerContext.getLogger(REGISTRY_LOGGER).isEnabled(Level.DEBUG));
	}

	@Test
	void otherContextWideFilter_stillDecidesWhatThisOneAllows() {
		loggerContext.setConfiguration(configurationAtWarnWithAnAcceptingFilter());

		assertTrue(loggerContext.getLogger(FILTER_CHAIN_LOGGER).isEnabled(Level.DEBUG));
	}

	@Test
	void coveredCategory_isStillNotLoggedAtDebugAfterAReconfiguration() {
		loggerContext.setConfiguration(configurationAt(Level.TRACE));

		assertFalse(loggerContext.getLogger(REGISTRY_LOGGER).isEnabled(Level.DEBUG));
	}

	@Test
	void secondInstaller_leavesTheFilterInstalledOnce() {
		new SessionIdLogFilterInstaller(loggerContext);

		assertSame(SessionIdLogFilter.INSTANCE, loggerContext.getConfiguration().getFilter());
	}

	@Test
	void logEventOfCoveredCategoryAtDebug_isDenied() {
		var event = Log4jLogEvent.newBuilder().setLoggerName(REGISTRY_LOGGER).setLevel(Level.DEBUG).build();

		assertEquals(Filter.Result.DENY, SessionIdLogFilter.INSTANCE.filter(event));
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("filterOverloads")
	void filterOverload_deniesACoveredCategoryBelowItsLowestLevelOnly(FilterCall call) {
		var logger = loggerContext.getLogger(REGISTRY_LOGGER);

		assertEquals(Filter.Result.DENY, call.apply(logger, Level.DEBUG));
		assertEquals(Filter.Result.NEUTRAL, call.apply(logger, Level.INFO));
	}

	@Test
	void everyFilterOverload_isDeclared() {
		for (Method method : Filter.class.getMethods()) {
			if (method.getName().equals("filter")) {
				var parameterTypes = method.getParameterTypes();
				assertDoesNotThrow(() -> SessionIdLogFilter.class.getDeclaredMethod("filter", parameterTypes),
						method::toString);
			}
		}
	}

	/** One call of a {@code filter} overload of the filter, for a logger at a level. */
	@FunctionalInterface
	interface FilterCall {
		Filter.Result apply(Logger logger, Level level);
	}

	static Stream<Named<FilterCall>> filterOverloads() {
		var filter = SessionIdLogFilter.INSTANCE;
		return Stream.of(
				call("Message, Throwable",
						(l, v) -> filter.filter(l, v, null, new SimpleMessage("m"), (Throwable) null)),
				call("Object, Throwable", (l, v) -> filter.filter(l, v, null, (Object) "m", (Throwable) null)),
				call("String", (l, v) -> filter.filter(l, v, null, "m")),
				call("String, 1 parameter", (l, v) -> filter.filter(l, v, null, "m", "1")),
				call("String, 2 parameters", (l, v) -> filter.filter(l, v, null, "m", "1", "2")),
				call("String, 3 parameters", (l, v) -> filter.filter(l, v, null, "m", "1", "2", "3")),
				call("String, 4 parameters", (l, v) -> filter.filter(l, v, null, "m", "1", "2", "3", "4")),
				call("String, 5 parameters", (l, v) -> filter.filter(l, v, null, "m", "1", "2", "3", "4", "5")),
				call("String, 6 parameters", (l, v) -> filter.filter(l, v, null, "m", "1", "2", "3", "4", "5", "6")),
				call("String, 7 parameters",
						(l, v) -> filter.filter(l, v, null, "m", "1", "2", "3", "4", "5", "6", "7")),
				call("String, 8 parameters",
						(l, v) -> filter.filter(l, v, null, "m", "1", "2", "3", "4", "5", "6", "7", "8")),
				call("String, 9 parameters",
						(l, v) -> filter.filter(l, v, null, "m", "1", "2", "3", "4", "5", "6", "7", "8", "9")),
				call("String, 10 parameters",
						(l, v) -> filter.filter(l, v, null, "m", "1", "2", "3", "4", "5", "6", "7", "8", "9", "10")),
				// more parameters than any fixed-arity overload takes
				call("String, varargs", (l, v) -> filter.filter(l, v, null, "m",
						"1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11"))
		);
	}

	private static Named<FilterCall> call(String overload, FilterCall call) {
		return Named.of(overload, call);
	}

	/** @return a configuration whose root logger, and so every logger, logs at the level */
	private static Configuration configurationAt(Level level) {
		var configuration = new DefaultConfiguration();
		configuration.getRootLogger().setLevel(level);
		return configuration;
	}

	/** @return a configuration at WARN with a context-wide filter of its own that accepts every event */
	private static Configuration configurationAtWarnWithAnAcceptingFilter() {
		var configuration = configurationAt(Level.WARN);
		configuration.addFilter(ThresholdFilter.createFilter(Level.TRACE, Filter.Result.ACCEPT, Filter.Result.NEUTRAL));
		return configuration;
	}
}
