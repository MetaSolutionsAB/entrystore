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

package org.entrystore.rest.springboot.util;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Configurator;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.config.Property;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

/**
 * Collects the log events one class emits, so a test can assert on a log line that <em>is</em> the
 * observable behaviour — a security diagnostic that must fire, a value that must never reach the log
 * verbatim, or a severity that alerting depends on.
 *
 * <p>Attaching lowers the target logger to DEBUG and undoes that on {@link #close()}, so use it in a
 * try-with-resources or detach it in an {@code @AfterEach}. The module logs through log4j2 (see
 * {@code spring-boot-starter-log4j2} in its pom), which by default configures only a console appender
 * at ERROR — without the level change a WARN assertion would silently never match.
 *
 * <p>Closing removes the level rather than writing one back when the target had no explicit
 * configuration of its own. {@code Logger.getLevel()} answers the <em>effective</em> inherited level, so
 * restoring it through {@code Configurator.setLevel} would install a permanent explicit
 * {@code LoggerConfig} at that level in the shared {@code LoggerContext} — after which lowering the root
 * level to observe the same class would silently drop events for the rest of the JVM's life.
 */
public final class CapturingAppender extends AbstractAppender implements AutoCloseable {

	private final List<LogEvent> events = Collections.synchronizedList(new ArrayList<>());
	private final Logger logger;
	private final @Nullable Level explicitLevelToRestore;

	private CapturingAppender(Logger logger) {
		super("CapturingAppender-" + logger.getName(), null, null, true, Property.EMPTY_ARRAY);
		this.logger = logger;
		// Only an entry whose name matches this logger exactly is its own configuration; anything else is
		// an ancestor's, and must be left to keep being inherited.
		LoggerConfig config = logger.get();
		this.explicitLevelToRestore = config.getName().equals(logger.getName()) ? config.getLevel() : null;
	}

	/** Attaches a fresh appender to the logger {@code type} logs through. */
	public static CapturingAppender attachTo(Class<?> type) {
		Logger logger = (Logger) LogManager.getLogger(type);
		CapturingAppender appender = new CapturingAppender(logger);
		appender.start();
		logger.addAppender(appender);
		Configurator.setLevel(logger.getName(), Level.DEBUG);
		return appender;
	}

	@Override
	public void append(LogEvent event) {
		events.add(event.toImmutable());
	}

	@Override
	public void close() {
		logger.removeAppender(this);
		if (explicitLevelToRestore != null) {
			Configurator.setLevel(logger.getName(), explicitLevelToRestore);
		} else {
			// attachTo created this LoggerConfig, so removing it is the exact inverse. Configurator only
			// ever assigns a level to a LoggerConfig, never drops one, so there is no setLevel call that
			// undoes the attach.
			LoggerContext context = logger.getContext();
			context.getConfiguration().removeLogger(logger.getName());
			context.updateLoggers();
		}
		stop();
	}

	public Stream<String> messagesAt(Level level) {
		return snapshot().stream()
				.filter(event -> event.getLevel() == level)
				.map(event -> event.getMessage().getFormattedMessage());
	}

	public Stream<String> allMessages() {
		return snapshot().stream().map(event -> event.getMessage().getFormattedMessage());
	}

	public long countAt(Level level) {
		return messagesAt(level).count();
	}

	/**
	 * Fails when nothing at all was captured.
	 *
	 * <p>A positive control for negative assertions. {@code noneMatch} and {@code assertFalse(anyMatch)}
	 * are both satisfied by an empty capture, so a test asserting "this value never reaches the log"
	 * keeps passing if the log call it is guarding is deleted, or if the appender was never wired to the
	 * class under test. Call this alongside such an assertion so the test still has to observe something.
	 */
	public void assertCapturedSomething() {
		if (snapshot().isEmpty()) {
			throw new AssertionError("no log events were captured at all, so a negative assertion here "
					+ "proves nothing — is the logging still in place and the appender attached to the "
					+ "right class?");
		}
	}

	/** Every captured message, for use in assertion failure descriptions. */
	@Override
	public String toString() {
		return allMessages().toList().toString();
	}

	private List<LogEvent> snapshot() {
		synchronized (events) {
			return List.copyOf(events);
		}
	}
}
