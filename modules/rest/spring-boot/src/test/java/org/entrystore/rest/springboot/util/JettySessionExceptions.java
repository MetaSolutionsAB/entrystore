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

import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import org.eclipse.jetty.session.ManagedSession;
import org.eclipse.jetty.session.SessionData;
import org.eclipse.jetty.session.SessionHandler;
import org.mockito.stubbing.Answer;

import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Produces the exceptions that Jetty's session handling throws, from a real {@link ManagedSession}, so that a test
 * meets what a request meets when a concurrent request ends its session, with {@link #SESSION_ID} in the message.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class JettySessionExceptions {

	/** The id of the session in the messages, for asserting that it reaches no log line and no response. */
	public static final String SESSION_ID = "node0jetty-session-exceptions-id";

	/** @return what reading a session throws after a concurrent request has ended it */
	public static IllegalStateException readOfInvalidatedSession() {
		ManagedSession session = invalidatedSession();
		return assertThrows(IllegalStateException.class, () -> session.getAttribute("attribute"));
	}

	/** @return what writing to a session throws after a concurrent request has ended it */
	public static IllegalStateException writeOfInvalidatedSession() {
		ManagedSession session = invalidatedSession();
		return assertThrows(IllegalStateException.class, () -> session.setAttribute("attribute", "value"));
	}

	/** @return what reading a session throws that is no longer held by its session cache */
	public static IllegalStateException readOfNonResidentSession() {
		ManagedSession session = newSession();
		return assertThrows(IllegalStateException.class, () -> session.getAttribute("attribute"));
	}

	/** @return what writing to a session throws that is no longer held by its session cache */
	public static IllegalStateException writeOfNonResidentSession() {
		ManagedSession session = newSession();
		return assertThrows(IllegalStateException.class, () -> session.setAttribute("attribute", "value"));
	}

	/**
	 * @return a Mockito answer that throws the exception as it is; {@code thenThrow} and {@code doThrow} refill its
	 * stack trace, which removes the Jetty frame that {@link SessionIdExceptions} recognises it by
	 */
	public static Answer<Object> throwing(Throwable exception) {
		return invocation -> {
			throw exception;
		};
	}

	/**
	 * @return an exception with the message whose first stack frame is in the class, for the session exceptions that
	 * a unit test cannot make Jetty throw
	 */
	public static IllegalStateException thrownIn(String className, String message) {
		var exception = new IllegalStateException(message);
		exception.setStackTrace(new StackTraceElement[] {new StackTraceElement(className, "method", null, -1)});
		return exception;
	}

	private static ManagedSession invalidatedSession() {
		ManagedSession session = newSession();
		session.beginInvalidate();
		session.finishInvalidate();
		return session;
	}

	private static ManagedSession newSession() {
		long now = System.currentTimeMillis();
		return new ManagedSession(new SessionHandler(), new SessionData(SESSION_ID, "/", "localhost", now, now, now,
				-1));
	}
}
