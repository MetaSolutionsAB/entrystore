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

package org.entrystore.repository.util;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.LongStream;

/**
 * Waits for tests that race threads on monitors. Not a Lombok {@code @UtilityClass}: javac cannot resolve a
 * non-star static import of its members.
 */
public final class ThreadAssertions {

	private ThreadAssertions() {
	}

	/**
	 * Waits for the latch; an interrupt (for example from cancelling the task that waits) ends the wait with an
	 * exception.
	 */
	public static void awaitOrThrow(CountDownLatch latch) {
		try {
			if (!latch.await(10, TimeUnit.SECONDS)) {
				throw new IllegalStateException("Latch was not released");
			}
		} catch (InterruptedException e) {
			throw new IllegalStateException("Interrupted", e);
		}
	}

	/**
	 * Waits until the threads have finished, and fails as soon as some of them deadlock on monitors. Only platform
	 * threads are seen by the deadlock detection.
	 */
	public static void awaitFinishedWithoutDeadlock(Thread... threads) throws InterruptedException {
		ThreadMXBean threadMXBean = ManagementFactory.getThreadMXBean();
		Set<Long> threadIds = Arrays.stream(threads).map(Thread::threadId).collect(Collectors.toSet());
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
		while (Arrays.stream(threads).anyMatch(Thread::isAlive)) {
			long[] deadlocked = threadMXBean.findMonitorDeadlockedThreads();
			if (deadlocked != null && LongStream.of(deadlocked).anyMatch(threadIds::contains)) {
				throw new AssertionError("Threads deadlocked");
			}
			if (System.nanoTime() > deadline) {
				throw new AssertionError("Threads did not finish");
			}
			Thread.sleep(10);
		}
	}
}
