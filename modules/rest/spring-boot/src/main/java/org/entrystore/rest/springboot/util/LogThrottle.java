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

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * Admits at most one log line per interval for a diagnostic that can fire once per request on an
 * attacker-reachable path — without the throttle, such a line turns the condition it reports into a
 * log/disk amplifier. Lock-free and safe to call from latency-sensitive callbacks (e.g. Caffeine
 * eviction listeners, which run inside the cache's eviction maintenance). The {@code AtomicLong}
 * provides the visibility the current call sites need (the thread invoking the callback varies over
 * time); the CAS additionally makes concurrent callers admit exactly one, as befits a shared util.
 */
public final class LogThrottle {

	private final long intervalNanos;
	private final LongSupplier nanoTime;
	private final AtomicLong lastAdmittedNanos;

	public LogThrottle(Duration interval) {
		this(interval, System::nanoTime);
	}

	LogThrottle(Duration interval, LongSupplier nanoTime) {
		if (interval.isZero() || interval.isNegative()) {
			throw new IllegalArgumentException("LogThrottle interval must be positive, got: " + interval);
		}
		this.intervalNanos = interval.toNanos();
		this.nanoTime = nanoTime;
		// Seed one interval in the past so the first occurrence is always admitted. (Seeding with
		// Long.MIN_VALUE would overflow the subtraction below and suppress it instead.)
		this.lastAdmittedNanos = new AtomicLong(nanoTime.getAsLong() - intervalNanos);
	}

	/**
	 * True at most once per interval; the caller logs only on true. This call CONSUMES the
	 * interval's single token when it returns true — evaluate it last in any condition, after every
	 * cheaper filter, or an admitted token is burned on an occurrence that is then not logged.
	 * A rejected call does not extend the interval.
	 */
	public boolean tryAcquire() {
		long now = nanoTime.getAsLong();
		long last = lastAdmittedNanos.get();
		if (now - last < intervalNanos) {
			return false;
		}
		return lastAdmittedNanos.compareAndSet(last, now);
	}
}
