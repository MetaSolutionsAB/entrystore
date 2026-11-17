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

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.Filter;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.filter.Filterable;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;

/**
 * Installs {@link SessionIdLogFilter} on the Log4j configuration, as a context-wide filter and on the root and every
 * named logger configuration, each time ahead of the filters already there, and again on every configuration that
 * replaces it, as a reconfiguration drops the filters added to the previous one. A logger configuration added later
 * without appenders of its own, as {@code /management/loggers} adds one, passes its events on to an ancestor that holds
 * the filter, at the latest the root. Log4j announces a new configuration only after applying it and stopping the
 * previous one, so the new one runs without the filter until then. Created after Spring Boot has started Jetty without
 * its connectors and before it adds them, so the filter is in place before the first request; never lazy, as no other
 * bean depends on it.
 */
@Component
@Lazy(false)
class SessionIdLogFilterInstaller implements PropertyChangeListener, DisposableBean {

	private final LoggerContext loggerContext;

	SessionIdLogFilterInstaller() {
		this((LoggerContext) LogManager.getContext(false));
	}

	SessionIdLogFilterInstaller(LoggerContext loggerContext) {
		this.loggerContext = loggerContext;
		loggerContext.addPropertyChangeListener(this);
		install(loggerContext.getConfiguration());
	}

	@Override
	public void propertyChange(PropertyChangeEvent event) {
		if (LoggerContext.PROPERTY_CONFIG.equals(event.getPropertyName())
				&& event.getNewValue() instanceof Configuration configuration) {
			install(configuration);
		}
	}

	/** Leaves the filter installed, so that it outlasts this application context in a shared logger context. */
	@Override
	public void destroy() {
		loggerContext.removePropertyChangeListener(this);
	}

	private static void install(Configuration configuration) {
		// a configuration stops its filters when it is replaced
		SessionIdLogFilter.INSTANCE.start();
		placeFirst(configuration);
		placeFirst(configuration.getRootLogger());
		configuration.getLoggers().values().forEach(SessionIdLogFilterInstaller::placeFirst);
	}

	/** Places the filter once, ahead of the target's own filters, as the first filter that accepts an event wins. */
	private static void placeFirst(Filterable target) {
		target.removeFilter(SessionIdLogFilter.INSTANCE);
		Filter others = target.getFilter();
		target.addFilter(SessionIdLogFilter.INSTANCE);
		if (others != null) {
			target.removeFilter(others);
			target.addFilter(others);
		}
	}
}
