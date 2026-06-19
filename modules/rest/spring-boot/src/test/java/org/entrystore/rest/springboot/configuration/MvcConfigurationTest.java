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

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.servlet.config.annotation.AsyncSupportConfigurer;

import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class MvcConfigurationTest {

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
			.withBean(MvcAsyncConfiguration.class, () -> new MvcAsyncConfiguration(8, 32, 16, 15000L))
			.withUserConfiguration(MvcConfiguration.class);

	@Test
	void registersBoundedAsyncExecutorConfiguredFromProperties() {
		runner.run(ctx -> {
			assertThat(ctx).hasSingleBean(ThreadPoolTaskExecutor.class);
			ThreadPoolTaskExecutor executor = ctx.getBean(ThreadPoolTaskExecutor.class);
			assertEquals(8, executor.getCorePoolSize());
			assertEquals(32, executor.getMaxPoolSize());
			assertEquals(16, executor.getQueueCapacity());
			assertEquals("mvc-async-", executor.getThreadNamePrefix());
			// AbortPolicy is what surfaces overflow as a RejectedExecutionException -> 503 in AppExceptionHandler.
			assertInstanceOf(ThreadPoolExecutor.AbortPolicy.class,
					executor.getThreadPoolExecutor().getRejectedExecutionHandler());
		});
	}

	@Test
	void configureAsyncSupportWiresTheBoundedExecutorAndTimeout() {
		// Guards the load-bearing half of the fix: dispatch must use the bounded mvc-async- executor
		// (not the default unbounded SimpleAsyncTaskExecutor) and the configured timeout. The bean
		// tests above only prove the executor is built, not that configureAsyncSupport actually uses it.
		MvcConfiguration mvcConfiguration =
				new MvcConfiguration(new MvcAsyncConfiguration(8, 32, 16, 15000L));
		AsyncSupportConfigurer configurer = mock(AsyncSupportConfigurer.class);

		mvcConfiguration.configureAsyncSupport(configurer);

		ArgumentCaptor<ThreadPoolTaskExecutor> executorCaptor = ArgumentCaptor.forClass(ThreadPoolTaskExecutor.class);
		verify(configurer).setTaskExecutor(executorCaptor.capture());
		verify(configurer).setDefaultTimeout(15000L);
		assertEquals("mvc-async-", executorCaptor.getValue().getThreadNamePrefix());
	}

	@Test
	void shutsDownExecutorWhenContextCloses() {
		// Regression guard for ENTRYSTORE-1051: as an inline (non-bean) executor, destroy() was never
		// called and the non-daemon mvc-async-* threads survived shutdown. As a managed bean the
		// container shuts the pool down on context close.
		AtomicReference<ThreadPoolTaskExecutor> executorRef = new AtomicReference<>();
		runner.run(ctx -> executorRef.set(ctx.getBean(ThreadPoolTaskExecutor.class)));
		// run() has closed the context, invoking the bean's destroy(); a terminated pool means the
		// non-daemon mvc-async-* worker threads are gone, not merely that shutdown was signalled.
		assertTrue(executorRef.get().getThreadPoolExecutor().isTerminated());
	}
}
