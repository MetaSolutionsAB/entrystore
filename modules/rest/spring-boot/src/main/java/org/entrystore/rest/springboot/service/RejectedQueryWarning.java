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

package org.entrystore.rest.springboot.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Warns about public SPARQL queries rejected for lack of a free slot, at most once per minute. Each rejection is also
 * logged on its own at INFO, which an operator does not watch; this line says how often it happens and what to tune.
 * The first rejection after a quiet minute warns at once; the ones within the minute after a warning are counted into
 * the next one.
 */
final class RejectedQueryWarning {

	private static final Duration INTERVAL = Duration.ofMinutes(1);

	private final Clock clock;
	private final Consumer<String> warn;
	private final int slots;
	private final long waitSeconds;
	private final AtomicLong rejected = new AtomicLong();
	private final AtomicReference<Instant> lastWarning = new AtomicReference<>();

	RejectedQueryWarning(Clock clock, Consumer<String> warn, int slots, Duration wait) {
		this.clock = clock;
		this.warn = warn;
		this.slots = slots;
		this.waitSeconds = wait.toSeconds();
	}

	void record() {
		rejected.incrementAndGet();
		Instant now = clock.instant();
		Instant last = lastWarning.get();
		if (last != null && now.isBefore(last.plus(INTERVAL))) {
			return;
		}
		if (!lastWarning.compareAndSet(last, now)) {
			return;
		}
		long count = rejected.getAndSet(0);
		String period = last == null
				? "since startup"
				: "in the last " + Duration.between(last, now).toSeconds() + " s";
		warn.accept(("Rejected %d public SPARQL queries %s: all %d slots busy for longer than %d s; consider raising "
				+ "entrystore.repository.public.sparql.max-concurrent-queries")
				.formatted(count, period, slots, waitSeconds));
	}
}
