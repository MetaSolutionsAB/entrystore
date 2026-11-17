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

import jakarta.servlet.ServletException;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Duration;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionIdExceptionsTest {

	private static final String MANAGED_SESSION = "org.eclipse.jetty.session.ManagedSession";

	@ParameterizedTest(name = "{0}")
	@MethodSource("exceptionsOfJettyWithTheSessionId")
	void exceptionOfJettyWithTheSessionId_isRecognised(IllegalStateException exception) {
		assertTrue(exception.getMessage().contains(JettySessionExceptions.SESSION_ID), exception::getMessage);
		assertTrue(SessionIdExceptions.foundIn(exception));
	}

	@Test
	void exceptionOfTheSessionCache_isRecognised() {
		var exception = JettySessionExceptions.thrownIn("org.eclipse.jetty.session.AbstractSessionCache",
				"Session " + JettySessionExceptions.SESSION_ID + " is not managed");

		assertTrue(SessionIdExceptions.foundIn(exception));
	}

	@Test
	void recognisedException_isRecognisedAsACauseOfACause() {
		var exception = new ServletException("Request processing failed",
				new RuntimeException("wrapped", JettySessionExceptions.readOfInvalidatedSession()));

		assertTrue(SessionIdExceptions.foundIn(exception));
	}

	@Test
	void recognisedException_isRecognisedAsASuppressedExceptionOfACause() {
		var cause = new RuntimeException("cause");
		cause.addSuppressed(JettySessionExceptions.writeOfInvalidatedSession());

		assertTrue(SessionIdExceptions.foundIn(new RuntimeException("outer", cause)));
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("otherExceptions")
	void otherException_isNotRecognised(Throwable exception) {
		assertFalse(SessionIdExceptions.foundIn(exception));
	}

	@Test
	void causesThatFormACycle_areWalkedOnce() {
		var first = new RuntimeException("first");
		var second = new RuntimeException("second", first);
		first.initCause(second);
		second.addSuppressed(first);

		assertFalse(assertTimeoutPreemptively(Duration.ofSeconds(5), () -> SessionIdExceptions.foundIn(first)));
	}

	static Stream<Named<IllegalStateException>> exceptionsOfJettyWithTheSessionId() {
		return Stream.of(
				Named.of("read of an invalidated session", JettySessionExceptions.readOfInvalidatedSession()),
				Named.of("write of an invalidated session", JettySessionExceptions.writeOfInvalidatedSession()),
				Named.of("read of a non-resident session", JettySessionExceptions.readOfNonResidentSession()),
				Named.of("write of a non-resident session", JettySessionExceptions.writeOfNonResidentSession()),
				// as Jetty builds them; a unit test cannot make a real session throw these
				Named.of("id change without a session manager", JettySessionExceptions.thrownIn(MANAGED_SESSION,
						"No session manager for session " + JettySessionExceptions.SESSION_ID)),
				Named.of("id change after the response was committed", JettySessionExceptions.thrownIn(MANAGED_SESSION,
						"Response committed " + JettySessionExceptions.SESSION_ID))
		);
	}

	static Stream<Named<Throwable>> otherExceptions() {
		var sessionIdMessage = "Invalid for read: id=" + JettySessionExceptions.SESSION_ID + " not resident";
		var notIllegalState = new RuntimeException(sessionIdMessage);
		notIllegalState.setStackTrace(JettySessionExceptions.readOfInvalidatedSession().getStackTrace());
		var withoutStackTrace = new IllegalStateException(sessionIdMessage);
		withoutStackTrace.setStackTrace(new StackTraceElement[0]);
		return Stream.of(
				// Jetty's session exceptions without an id
				Named.of("session invalid", JettySessionExceptions.thrownIn(MANAGED_SESSION, "Session invalid")),
				Named.of("unable to change the session id",
						JettySessionExceptions.thrownIn(MANAGED_SESSION, "Unable to change session id")),
				Named.of("without a message", JettySessionExceptions.thrownIn(MANAGED_SESSION, null)),
				Named.of("message of a session exception from another class",
						JettySessionExceptions.thrownIn("org.eclipse.jetty.session.SessionData", sessionIdMessage)),
				Named.of("message of a session exception thrown here", new IllegalStateException(sessionIdMessage)),
				Named.of("message of a session exception without a stack trace", withoutStackTrace),
				Named.of("message and frame of a session exception, not an IllegalStateException", notIllegalState),
				Named.of("null", null)
		);
	}
}
