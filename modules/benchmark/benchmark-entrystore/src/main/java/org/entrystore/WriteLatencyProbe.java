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

package org.entrystore;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/**
 * Creates an entry in a context of its own every 10 ms while a benchmarked operation runs, and times each create:
 * the latency shows how long the operation holds up other writers.
 */
final class WriteLatencyProbe {

	private static final long WRITE_PAUSE_MILLIS = 10;

	private static final long POLL_MILLIS = 100;

	private final List<Long> writeNanos = Collections.synchronizedList(new ArrayList<>());
	private final AtomicInteger failedWrites = new AtomicInteger();
	private final AtomicBoolean writing = new AtomicBoolean(true);
	private final Thread writer;

	/** Starts writing to {@code written} as {@code user}. */
	WriteLatencyProbe(Context written, PrincipalManager principalManager, URI user) {
		writer = Thread.ofPlatform().name("benchmark-writer").start(() -> {
			principalManager.setAuthenticatedUserURI(user);
			while (writing.get()) {
				long start = System.nanoTime();
				try {
					written.createResource(null, GraphType.None, null, null);
					writeNanos.add(System.nanoTime() - start);
				} catch (RuntimeException e) {
					failedWrites.incrementAndGet();
				}
				try {
					Thread.sleep(WRITE_PAUSE_MILLIS);
				} catch (InterruptedException e) {
					return;
				}
			}
		});
	}

	/** Stops the writer, which is no daemon: a benchmark that failed would otherwise never exit. */
	void stop() {
		writing.set(false);
		try {
			writer.join();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	/** Logs the latency of the writes made while {@code operation} ran; call after {@link #stop()}. */
	void log(String operation) {
		List<Long> sorted = new ArrayList<>(writeNanos);
		if (sorted.isEmpty()) {
			LogUtils.log.info("No write to the other context completed during {} ({} failed)", operation,
					failedWrites.get());
			return;
		}
		Collections.sort(sorted);
		LogUtils.log.info("Writes to the other context during {}: {}, p50 {} ms, max {} ms, {} failed", operation,
				sorted.size(), sorted.get(sorted.size() / 2) / 1_000_000, sorted.getLast() / 1_000_000,
				failedWrites.get());
	}

	/**
	 * Polls until {@code pending} answers false.
	 *
	 * @return false if it still answered true after {@code deadline}, or the waiting thread was interrupted
	 */
	static boolean await(BooleanSupplier pending, Duration deadline) {
		long end = System.nanoTime() + deadline.toNanos();
		while (pending.getAsBoolean()) {
			if (System.nanoTime() - end > 0) {
				return false;
			}
			try {
				Thread.sleep(POLL_MILLIS);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return false;
			}
		}
		return true;
	}
}
