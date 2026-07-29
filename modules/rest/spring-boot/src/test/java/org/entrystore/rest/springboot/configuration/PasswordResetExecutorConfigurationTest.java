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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the two properties of this executor that {@code AuthService} depends on but cannot observe,
 * because its own tests stub the executor out: a saturated queue aborts rather than running the task
 * on the caller's thread, and shutdown discards the backlog instead of draining it.
 */
class PasswordResetExecutorConfigurationTest {

	// Mirrors the bean's own (private) sizing.
	private static final int POOL_SIZE = 2;
	private static final int QUEUE_CAPACITY = 100;

	private ThreadPoolTaskExecutor executor;
	private final CountDownLatch release = new CountDownLatch(1);

	@BeforeEach
	void setUp() {
		executor = new PasswordResetExecutorConfiguration().passwordResetTaskExecutor();
		executor.afterPropertiesSet();
	}

	@AfterEach
	void tearDown() {
		release.countDown();
		executor.shutdown();
	}

	@Test
	void passwordResetTaskExecutor_queueSaturated_abortsInsteadOfRunningOnTheCallerThread() throws Exception {
		occupyWorkersAndFillQueue();

		AtomicReference<Thread> ranOn = new AtomicReference<>();
		assertThrows(TaskRejectedException.class, () -> executor.execute(() -> ranOn.set(Thread.currentThread())),
				"a saturated queue must abort the dispatch so AuthService can count it and stay on the generic 200");
		assertNull(ranOn.get(), "a rejected dispatch must never run on the request thread — CallerRunsPolicy "
				+ "would reopen the bcrypt+SMTP timing oracle this executor exists to close");
	}

	@Test
	void passwordResetTaskExecutor_shutdown_dropsTheQueuedBacklog() throws Exception {
		AtomicInteger queuedRuns = new AtomicInteger();
		occupyWorkers();
		for (int i = 0; i < 10; i++) {
			executor.execute(queuedRuns::incrementAndGet);
		}

		// Takes the shutdownNow branch: interrupts the two blocked workers and discards the queue.
		// Draining instead would keep running these tasks after the context is gone, storing tokens in
		// a cache the restart discards and mailing links that are already dead.
		executor.shutdown();

		assertEquals(0, queuedRuns.get(), "queued dispatches must be dropped, not drained, on shutdown");
	}

	private void occupyWorkersAndFillQueue() throws InterruptedException {
		occupyWorkers();
		for (int i = 0; i < QUEUE_CAPACITY; i++) {
			executor.execute(() -> awaitQuietly(release));
		}
	}

	/** Blocks every worker thread, so anything submitted afterwards can only go to the queue. */
	private void occupyWorkers() throws InterruptedException {
		CountDownLatch started = new CountDownLatch(POOL_SIZE);
		for (int i = 0; i < POOL_SIZE; i++) {
			executor.execute(() -> {
				started.countDown();
				awaitQuietly(release);
			});
		}
		assertTrue(started.await(5, TimeUnit.SECONDS), "workers should have picked up the blocking tasks");
	}

	private static void awaitQuietly(CountDownLatch latch) {
		try {
			latch.await();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}
}
