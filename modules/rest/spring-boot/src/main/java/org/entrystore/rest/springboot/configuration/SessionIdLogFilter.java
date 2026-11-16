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
import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.filter.AbstractFilter;
import org.apache.logging.log4j.message.Message;

import java.util.List;

/**
 * Keeps session ids out of the logs by holding the categories that handle sessions to a lowest level, whatever level
 * {@code logging.level.*} or {@code /management/loggers} sets: INFO for the session, session-authentication, logout
 * and security-context classes of Spring Security, for {@code SessionContextRepository} and for Jetty, ERROR for the
 * session handling of Jetty, and DEBUG for Spring MVC's {@code DispatcherServlet}. A category includes its
 * subcategories. {@link SessionIdLogFilterInstaller} installs it as a context-wide filter.
 *
 * <p>Log4j asks a context-wide filter before the level check of every logging call, through the overload that
 * matches the call, so each overload is implemented: those of {@link AbstractFilter} first copy the parameters into
 * an array.
 */
final class SessionIdLogFilter extends AbstractFilter {

	static final SessionIdLogFilter INSTANCE = new SessionIdLogFilter();

	private static final List<Floor> FLOORS = List.of(
			new Floor("org.springframework.security.core.session", Level.INFO),
			new Floor("org.springframework.security.web.session", Level.INFO),
			new Floor("org.springframework.security.web.authentication.session", Level.INFO),
			new Floor("org.springframework.security.web.authentication.logout", Level.INFO),
			new Floor("org.springframework.security.web.context", Level.INFO),
			new Floor("org.entrystore.rest.springboot.security.SessionContextRepository", Level.INFO),
			new Floor("org.eclipse.jetty", Level.INFO),
			new Floor("org.eclipse.jetty.session", Level.ERROR),
			new Floor("org.springframework.web.servlet.DispatcherServlet", Level.DEBUG)
	);

	private SessionIdLogFilter() {
		super(Result.DENY, Result.NEUTRAL);
	}

	/** @return whether the logger is held to a lowest level that is more severe than the level */
	static boolean isDenied(String loggerName, Level level) {
		for (Floor floor : FLOORS) {
			if (floor.covers(loggerName) && !level.isMoreSpecificThan(floor.lowest())) {
				return true;
			}
		}
		return false;
	}

	private Result decide(String loggerName, Level level) {
		return loggerName != null && isDenied(loggerName, level) ? onMatch : onMismatch;
	}

	@Override
	public Result filter(LogEvent event) {
		return decide(event.getLoggerName(), event.getLevel());
	}

	@Override
	public Result filter(Logger logger, Level level, Marker marker, Message msg, Throwable t) {
		return decide(logger.getName(), level);
	}

	@Override
	public Result filter(Logger logger, Level level, Marker marker, Object msg, Throwable t) {
		return decide(logger.getName(), level);
	}

	@Override
	public Result filter(Logger logger, Level level, Marker marker, String msg) {
		return decide(logger.getName(), level);
	}

	@Override
	public Result filter(Logger logger, Level level, Marker marker, String msg, Object... params) {
		return decide(logger.getName(), level);
	}

	@Override
	public Result filter(Logger logger, Level level, Marker marker, String msg, Object p0) {
		return decide(logger.getName(), level);
	}

	@Override
	public Result filter(Logger logger, Level level, Marker marker, String msg, Object p0, Object p1) {
		return decide(logger.getName(), level);
	}

	@Override
	public Result filter(Logger logger, Level level, Marker marker, String msg, Object p0, Object p1, Object p2) {
		return decide(logger.getName(), level);
	}

	@Override
	public Result filter(Logger logger, Level level, Marker marker, String msg, Object p0, Object p1, Object p2,
						 Object p3) {
		return decide(logger.getName(), level);
	}

	@Override
	public Result filter(Logger logger, Level level, Marker marker, String msg, Object p0, Object p1, Object p2,
						 Object p3, Object p4) {
		return decide(logger.getName(), level);
	}

	@Override
	public Result filter(Logger logger, Level level, Marker marker, String msg, Object p0, Object p1, Object p2,
						 Object p3, Object p4, Object p5) {
		return decide(logger.getName(), level);
	}

	@Override
	public Result filter(Logger logger, Level level, Marker marker, String msg, Object p0, Object p1, Object p2,
						 Object p3, Object p4, Object p5, Object p6) {
		return decide(logger.getName(), level);
	}

	@Override
	public Result filter(Logger logger, Level level, Marker marker, String msg, Object p0, Object p1, Object p2,
						 Object p3, Object p4, Object p5, Object p6, Object p7) {
		return decide(logger.getName(), level);
	}

	@Override
	public Result filter(Logger logger, Level level, Marker marker, String msg, Object p0, Object p1, Object p2,
						 Object p3, Object p4, Object p5, Object p6, Object p7, Object p8) {
		return decide(logger.getName(), level);
	}

	@Override
	public Result filter(Logger logger, Level level, Marker marker, String msg, Object p0, Object p1, Object p2,
						 Object p3, Object p4, Object p5, Object p6, Object p7, Object p8, Object p9) {
		return decide(logger.getName(), level);
	}

	/** The least severe level that a category and its subcategories may write. */
	private record Floor(String category, Level lowest) {

		boolean covers(String loggerName) {
			return loggerName.startsWith(category)
					&& (loggerName.length() == category.length() || loggerName.charAt(category.length()) == '.');
		}
	}
}
