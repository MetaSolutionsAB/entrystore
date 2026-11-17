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
import org.jspecify.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Recognises the {@link IllegalStateException}s that Jetty's session handling throws with a session id in their
 * message, most often because a concurrent request has just ended the session, by the class that throws them and the
 * start of their message. Two other Jetty exceptions carry ids and are not matched: the 400 for a request with the
 * cookies of two sessions, which Jetty answers before the application and logs only at DEBUG, below its floor, and the
 * {@link IllegalArgumentException} of a session cache given no key or session. The log filter, the request-level
 * handlers and the session reads that tolerate an ended session share this one rule. Logs nothing itself, as the log
 * filter calls it while deciding an event.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class SessionIdExceptions {

	private static final Map<String, List<String>> MESSAGE_PREFIXES_BY_THROWER = Map.of(
			"org.eclipse.jetty.session.ManagedSession", List.of(
					"Not valid for write: id=",
					"Invalid for read: id=",
					"No session manager for session ",
					"Response committed "),
			"org.eclipse.jetty.session.AbstractSessionCache", List.of("Session "));

	/**
	 * @return whether the throwable, or a cause or suppressed exception of it at any depth, is one of the recognised
	 * exceptions
	 */
	public static boolean foundIn(@Nullable Throwable throwable) {
		if (throwable == null) {
			return false;
		}
		Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
		Deque<Throwable> pending = new ArrayDeque<>();
		pending.push(throwable);
		while (!pending.isEmpty()) {
			Throwable current = pending.pop();
			if (!visited.add(current)) {
				continue;
			}
			if (isRecognised(current)) {
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

	private static boolean isRecognised(Throwable throwable) {
		if (!(throwable instanceof IllegalStateException) || throwable.getMessage() == null) {
			return false;
		}
		StackTraceElement[] stackTrace = throwable.getStackTrace();
		if (stackTrace.length == 0) {
			return false;
		}
		List<String> prefixes = MESSAGE_PREFIXES_BY_THROWER.get(stackTrace[0].getClassName());
		return prefixes != null && prefixes.stream().anyMatch(throwable.getMessage()::startsWith);
	}
}
