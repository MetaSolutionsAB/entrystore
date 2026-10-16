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

package org.entrystore.impl;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Configurator;
import org.apache.logging.log4j.core.config.Property;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;

/**
 * Captures what one class logs, so a branch whose only effect is what it logs can be asserted on. Raises that
 * logger to the given level for the duration: the shared test configuration pins {@code org.entrystore.impl} at
 * ERROR, which would otherwise drop the event before any appender sees it.
 */
final class LogCapture implements AutoCloseable {

	private final Logger logger;
	private final Level previousLevel;
	private final List<LogEvent> events = new CopyOnWriteArrayList<>();
	private final AbstractAppender appender;

	LogCapture(Class<?> owner, Level level) {
		logger = (Logger) LogManager.getLogger(owner);
		previousLevel = logger.getLevel();
		appender = new AbstractAppender("capture-" + owner.getSimpleName(), null, null, true, Property.EMPTY_ARRAY) {
			@Override
			public void append(LogEvent event) {
				events.add(event.toImmutable());
			}
		};
		appender.start();
		// Level first: setLevel installs a new LoggerConfig for this class, and an appender added before it
		// would stay on the inherited one, where close() could not remove it again.
		Configurator.setLevel(owner.getName(), level);
		logger.addAppender(appender);
	}

	/**
	 * Events at {@code level} whose message contains {@code needle}. An empty needle counts every event at that
	 * level, so a test can bound what was reported and not only what it looked for.
	 */
	long count(Level level, String needle) {
		return events.stream().filter(matching(level, needle)).count();
	}

	/** The first event at {@code level} whose message contains {@code needle}. */
	Optional<LogEvent> first(Level level, String needle) {
		return events.stream().filter(matching(level, needle)).findFirst();
	}

	private static Predicate<LogEvent> matching(Level level, String needle) {
		return event -> level.equals(event.getLevel()) && event.getMessage().getFormattedMessage().contains(needle);
	}

	@Override
	public void close() {
		logger.removeAppender(appender);
		Configurator.setLevel(logger.getName(), previousLevel);
		appender.stop();
	}
}
