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

import org.apache.commons.logging.Log;

import java.util.List;

/**
 * Minimal {@link Log} for the environment post-processors, which only ever emit via single-argument WARN (and,
 * where an INFO sink is given, single-argument INFO): it records those calls, and every other level and the
 * two-argument overloads fail loudly with an {@link AssertionError} so an unexpected production log call surfaces
 * as a test failure instead of being silently dropped.
 */
final class RecordingLog implements Log {

	private final List<String> warnings;
	private final List<String> infos;

	RecordingLog(List<String> warnings) {
		this(warnings, null);
	}

	RecordingLog(List<String> warnings, List<String> infos) {
		this.warnings = warnings;
		this.infos = infos;
	}

	@Override public void warn(Object message) {
		warnings.add(String.valueOf(message));
	}

	@Override public boolean isWarnEnabled() { return true; }
	@Override public boolean isDebugEnabled() { return false; }
	@Override public boolean isErrorEnabled() { return false; }
	@Override public boolean isFatalEnabled() { return false; }
	@Override public boolean isInfoEnabled() { return infos != null; }
	@Override public boolean isTraceEnabled() { return false; }

	@Override public void debug(Object message) { throw unexpected("debug"); }
	@Override public void debug(Object message, Throwable t) { throw unexpected("debug"); }
	@Override public void error(Object message) { throw unexpected("error"); }
	@Override public void error(Object message, Throwable t) { throw unexpected("error"); }
	@Override public void fatal(Object message) { throw unexpected("fatal"); }
	@Override public void fatal(Object message, Throwable t) { throw unexpected("fatal"); }
	@Override public void info(Object message) {
		if (infos == null) {
			throw unexpected("info");
		}
		infos.add(String.valueOf(message));
	}
	@Override public void info(Object message, Throwable t) { throw unexpected("info"); }
	@Override public void trace(Object message) { throw unexpected("trace"); }
	@Override public void trace(Object message, Throwable t) { throw unexpected("trace"); }
	@Override public void warn(Object message, Throwable t) { throw unexpected("warn(message, throwable)"); }

	private static AssertionError unexpected(String level) {
		return new AssertionError("Unexpected log call at level '" + level
				+ "'; the post-processor should only emit via single-argument WARN");
	}
}
