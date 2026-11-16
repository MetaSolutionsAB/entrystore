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

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/**
 * Keeps session ids out of the logs by holding the categories that handle sessions or request headers to a lowest
 * level, whatever level {@code logging.level.*} or {@code /management/loggers} sets: INFO for the session,
 * session-authentication, logout and security-context classes of Spring Security, for
 * {@code SessionContextRepository}, for Spring's {@code ForwardedHeaderFilter} and for Jetty, ERROR for the session
 * handling of Jetty, and DEBUG for Spring MVC's {@code DispatcherServlet}. A category includes its subcategories. It
 * also denies, at every level, an event that carries the exception Jetty throws for a session that has ended, as its
 * exception, a cause, a suppressed exception or a parameter. While Log4j replaces its configuration, as on a reload
 * through {@code monitorInterval}, the new configuration alone decides what is written until the previous one has
 * stopped. {@link SessionIdLogFilterInstaller} installs it as a context-wide filter.
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
			new Floor("org.springframework.web.filter.ForwardedHeaderFilter", Level.INFO),
			new Floor("org.eclipse.jetty", Level.INFO),
			new Floor("org.eclipse.jetty.session", Level.ERROR),
			new Floor("org.springframework.web.servlet.DispatcherServlet", Level.DEBUG)
	);

	private static final String SESSION_HANDLING_PACKAGE = "org.eclipse.jetty.session.";

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

	/**
	 * @return whether the throwable, a cause or a suppressed exception of it, at any depth, is the exception Jetty
	 * throws when a session that has ended is read or written
	 */
	static boolean carriesEndedSessionException(Throwable throwable) {
		Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
		Deque<Throwable> pending = new ArrayDeque<>();
		pending.push(throwable);
		while (!pending.isEmpty()) {
			Throwable current = pending.pop();
			if (!visited.add(current)) {
				continue;
			}
			if (current instanceof IllegalStateException && isThrownBySessionHandling(current)) {
				return true;
			}
			if (current.getCause() != null) {
				pending.push(current.getCause());
			}
			for (Throwable suppressed : current.getSuppressed()) {
				pending.push(suppressed);
			}
		}
		return false;
	}

	private static boolean isThrownBySessionHandling(Throwable throwable) {
		StackTraceElement[] stackTrace = throwable.getStackTrace();
		return stackTrace.length > 0 && stackTrace[0].getClassName().startsWith(SESSION_HANDLING_PACKAGE);
	}

	private static boolean carries(Object value) {
		return value instanceof Throwable throwable && carriesEndedSessionException(throwable);
	}

	private static boolean belowFloor(String loggerName, Level level) {
		return loggerName != null && isDenied(loggerName, level);
	}

	private Result decide(boolean deny) {
		return deny ? onMatch : onMismatch;
	}

	@Override
	public Result filter(LogEvent event) {
		return decide(belowFloor(event.getLoggerName(), event.getLevel()) || carries(event.getThrown()));
	}

	@Override
	public Result filter(Logger logger, Level level, Marker marker, Message msg, Throwable t) {
		return decide(belowFloor(logger.getName(), level) || carries(t) || msg != null && carries(msg.getThrowable()));
	}

	@Override
	public Result filter(Logger logger, Level level, Marker marker, Object msg, Throwable t) {
		return decide(belowFloor(logger.getName(), level) || carries(msg) || carries(t));
	}

	@Override
	public Result filter(Logger logger, Level level, Marker marker, String msg) {
		return decide(belowFloor(logger.getName(), level));
	}

	@Override
	public Result filter(Logger logger, Level level, Marker marker, String msg, Object... params) {
		if (belowFloor(logger.getName(), level)) {
			return onMatch;
		}
		if (params != null) {
			for (Object param : params) {
				if (carries(param)) {
					return onMatch;
				}
			}
		}
		return onMismatch;
	}

	@Override
	public Result filter(Logger logger, Level level, Marker marker, String msg, Object p0) {
		return decide(belowFloor(logger.getName(), level) || carries(p0));
	}

	@Override
	public Result filter(Logger logger, Level level, Marker marker, String msg, Object p0, Object p1) {
		return decide(belowFloor(logger.getName(), level) || carries(p0) || carries(p1));
	}

	@Override
	public Result filter(Logger logger, Level level, Marker marker, String msg, Object p0, Object p1, Object p2) {
		return decide(belowFloor(logger.getName(), level) || carries(p0) || carries(p1) || carries(p2));
	}

	@Override
	public Result filter(Logger logger, Level level, Marker marker, String msg, Object p0, Object p1, Object p2,
						 Object p3) {
		return decide(belowFloor(logger.getName(), level) || carries(p0) || carries(p1) || carries(p2)
				|| carries(p3));
	}

	@Override
	public Result filter(Logger logger, Level level, Marker marker, String msg, Object p0, Object p1, Object p2,
						 Object p3, Object p4) {
		return decide(belowFloor(logger.getName(), level) || carries(p0) || carries(p1) || carries(p2)
				|| carries(p3) || carries(p4));
	}

	@Override
	public Result filter(Logger logger, Level level, Marker marker, String msg, Object p0, Object p1, Object p2,
						 Object p3, Object p4, Object p5) {
		return decide(belowFloor(logger.getName(), level) || carries(p0) || carries(p1) || carries(p2)
				|| carries(p3) || carries(p4) || carries(p5));
	}

	@Override
	public Result filter(Logger logger, Level level, Marker marker, String msg, Object p0, Object p1, Object p2,
						 Object p3, Object p4, Object p5, Object p6) {
		return decide(belowFloor(logger.getName(), level) || carries(p0) || carries(p1) || carries(p2)
				|| carries(p3) || carries(p4) || carries(p5) || carries(p6));
	}

	@Override
	public Result filter(Logger logger, Level level, Marker marker, String msg, Object p0, Object p1, Object p2,
						 Object p3, Object p4, Object p5, Object p6, Object p7) {
		return decide(belowFloor(logger.getName(), level) || carries(p0) || carries(p1) || carries(p2)
				|| carries(p3) || carries(p4) || carries(p5) || carries(p6) || carries(p7));
	}

	@Override
	public Result filter(Logger logger, Level level, Marker marker, String msg, Object p0, Object p1, Object p2,
						 Object p3, Object p4, Object p5, Object p6, Object p7, Object p8) {
		return decide(belowFloor(logger.getName(), level) || carries(p0) || carries(p1) || carries(p2)
				|| carries(p3) || carries(p4) || carries(p5) || carries(p6) || carries(p7) || carries(p8));
	}

	@Override
	public Result filter(Logger logger, Level level, Marker marker, String msg, Object p0, Object p1, Object p2,
						 Object p3, Object p4, Object p5, Object p6, Object p7, Object p8, Object p9) {
		return decide(belowFloor(logger.getName(), level) || carries(p0) || carries(p1) || carries(p2)
				|| carries(p3) || carries(p4) || carries(p5) || carries(p6) || carries(p7) || carries(p8)
				|| carries(p9));
	}

	/** The least severe level that a category and its subcategories may write. */
	private record Floor(String category, Level lowest) {

		boolean covers(String loggerName) {
			return loggerName.startsWith(category)
					&& (loggerName.length() == category.length() || loggerName.charAt(category.length()) == '.');
		}
	}
}
