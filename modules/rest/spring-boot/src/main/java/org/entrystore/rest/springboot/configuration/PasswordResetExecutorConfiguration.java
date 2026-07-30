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

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

@Slf4j
@Configuration
public class PasswordResetExecutorConfiguration {

	private static final int PASSWORD_RESET_POOL_SIZE = 2;
	private static final int PASSWORD_RESET_QUEUE_CAPACITY = 100;
	private static final int PASSWORD_RESET_DRAIN_SECONDS = 30;

	/**
	 * Executor for the asynchronous part of the password-reset flow (token generation, bcrypt, SMTP
	 * send). {@code AuthService.pwReset} moves that work off the request thread so the nonexistent /
	 * disabled / active branches all return without the bcrypt+SMTP timing gap, closing a
	 * timing-based account-enumeration side channel.
	 * <p>
	 * Pool size is intentionally small: pwReset throughput is bounded by
	 * {@code PasswordResetRateLimiter}, so two threads are enough to absorb concurrent dispatches
	 * without blocking the request path. Threads are daemons so a shutdown that misses the container
	 * callback still does not block JVM exit.
	 * <p>
	 * The queue is bounded to cap heap growth under distributed credential-stuffing or an SMTP
	 * outage: at queue saturation {@code execute} throws {@code TaskRejectedException} (a
	 * {@link java.util.concurrent.RejectedExecutionException}), which
	 * {@code AuthService.submitPasswordResetDispatch} catches and treats as a client-silent drop —
	 * the response stays at the generic 200 so the timing-equivalence guarantee is preserved, but
	 * the rejection is logged at ERROR and increments the {@code auth.pwreset.rejected} Micrometer
	 * counter so operators can alert on sustained drops via monitoring rather than log scraping. A
	 * {@code CallerRunsPolicy} would have run the rejected task on the request thread, which
	 * re-opens the timing oracle this whole executor exists to close.
	 * <p>
	 * Declared as a bean so the container owns its lifecycle: {@code afterPropertiesSet()} calls
	 * {@code initialize()} on startup, and on context close the executor is shut down as described
	 * below, discarding the queued backlog and awaiting the already-running tasks for
	 * {@value #PASSWORD_RESET_DRAIN_SECONDS} seconds. The await is best-effort: {@code shutdownNow()}
	 * has already interrupted the workers at that point — an in-flight SMTP send usually still
	 * completes only because a blocking socket write does not respond to interruption.
	 * <p>
	 * Discarding rather than draining is deliberate, and takes both flags below to actually happen.
	 * {@code waitForTasksToCompleteOnShutdown} stays off so {@code shutdown()} takes the
	 * {@code shutdownNow()} branch. {@code acceptTasksAfterContextClose} is on because without it the
	 * two workers keep draining the queue through the coordinated {@code SmartLifecycle} stop phase,
	 * blocking context close on the lifecycle latch for up to {@code timeoutPerShutdownPhase} (30s)
	 * before {@code destroy()} finally discards the rest: {@code ThreadPoolTaskExecutor}'s
	 * {@code initiateEarlyShutdown()} is a no-op unless {@code strictEarlyShutdown} is set, and
	 * {@code markShutdown()} defeats the {@code beforeExecute} pause guard, so nothing actually stops
	 * the workers during that phase. With the flag set the executor skips the stop phase entirely and
	 * {@code destroy()} → {@code shutdownNow()} is the first and only shutdown step. Its side effect —
	 * dispatches submitted during the close window are still accepted, then dropped — is harmless
	 * here: Boot's graceful shutdown stops the connector before bean destruction, and a client-silent
	 * drop is the contract anyway.
	 * <p>
	 * Draining the queue instead would not terminate — two workers against {@code Email}'s 5s SMTP
	 * timeouts clear roughly a dozen sends in {@value #PASSWORD_RESET_DRAIN_SECONDS} seconds, so a
	 * queue anywhere near {@value #PASSWORD_RESET_QUEUE_CAPACITY} cannot finish, and on timeout Spring
	 * only logs a warning without following up with {@code shutdownNow()} the way the hand-rolled
	 * {@code @PreDestroy} this replaced did. Those tasks would then keep running past context close,
	 * storing tokens in a cache the restart is about to discard and mailing links that are already
	 * dead. Dropping a queued dispatch is the outcome {@code AuthService.submitPasswordResetDispatch}
	 * already accepts on rejection; {@code cancelRemainingTask} logs each one so the drop is not
	 * silent.
	 * <p>
	 * {@code newThread} is overridden to install an {@code UncaughtExceptionHandler}:
	 * {@code AuthService.dispatchPasswordResetEmail} deliberately runs bcrypt and {@code putToken}
	 * outside its own try/catch (nothing needs cleaning up before the token is stored), and relies on
	 * this handler to make a {@code Throwable} from that stretch reach the logs instead of being
	 * swallowed by {@code ThreadPoolExecutor}'s default {@code Worker.run} path. That depends on the
	 * task being handed to {@code execute()}, as {@code submitPasswordResetDispatch} does — a
	 * {@code submit()} would capture the same {@code Throwable} into the returned {@code Future},
	 * where the handler never fires.
	 */
	@Bean
	ThreadPoolTaskExecutor passwordResetTaskExecutor() {
		ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor() {
			@Override
			public Thread newThread(Runnable runnable) {
				Thread thread = super.newThread(runnable);
				thread.setUncaughtExceptionHandler((t, ex) ->
						log.error("Uncaught error in password-reset worker {}", t.getName(), ex));
				return thread;
			}

			@Override
			protected void cancelRemainingTask(Runnable task) {
				log.warn("Dropping queued password-reset dispatch on shutdown; the user must request a new reset");
				super.cancelRemainingTask(task);
			}
		};
		executor.setCorePoolSize(PASSWORD_RESET_POOL_SIZE);
		executor.setMaxPoolSize(PASSWORD_RESET_POOL_SIZE);
		executor.setQueueCapacity(PASSWORD_RESET_QUEUE_CAPACITY);
		executor.setThreadNamePrefix("password-reset-async-");
		executor.setDaemon(true);
		executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
		executor.setAcceptTasksAfterContextClose(true);
		executor.setAwaitTerminationSeconds(PASSWORD_RESET_DRAIN_SECONDS);
		return executor;
	}
}
