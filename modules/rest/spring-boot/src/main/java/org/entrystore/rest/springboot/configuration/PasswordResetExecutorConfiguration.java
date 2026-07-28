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
	 * {@code initialize()} on startup, and on context close {@code ExecutorConfigurationSupport}
	 * calls {@code shutdown()} (graceful, because {@code waitForTasksToCompleteOnShutdown} is set)
	 * then awaits termination for {@value #PASSWORD_RESET_DRAIN_SECONDS} seconds — giving in-flight
	 * SMTP sends a chance to finish rather than dropping a reset email the user is already waiting
	 * for.
	 * <p>
	 * Note that on drain timeout Spring only logs a warning; unlike the hand-rolled {@code @PreDestroy}
	 * this replaced, it does <em>not</em> follow up with {@code shutdownNow()}. That is deliberate and
	 * harmless here: the workers are daemons so they cannot hold JVM exit open, and the work they are
	 * blocked on is an SMTP socket read, which {@code Thread.interrupt()} would not have unblocked
	 * anyway.
	 * <p>
	 * {@code newThread} is overridden to install an {@code UncaughtExceptionHandler}:
	 * {@code AuthService.dispatchPasswordResetEmail} deliberately runs bcrypt and {@code putToken}
	 * outside its own try/catch (nothing needs cleaning up before the token is stored), and relies on
	 * this handler to make a {@code Throwable} from that stretch reach the logs instead of being
	 * swallowed by {@code ThreadPoolExecutor}'s default {@code Worker.run} path.
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
		};
		executor.setCorePoolSize(PASSWORD_RESET_POOL_SIZE);
		executor.setMaxPoolSize(PASSWORD_RESET_POOL_SIZE);
		executor.setQueueCapacity(PASSWORD_RESET_QUEUE_CAPACITY);
		executor.setThreadNamePrefix("password-reset-async-");
		executor.setDaemon(true);
		executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
		executor.setWaitForTasksToCompleteOnShutdown(true);
		executor.setAwaitTerminationSeconds(PASSWORD_RESET_DRAIN_SECONDS);
		return executor;
	}
}
