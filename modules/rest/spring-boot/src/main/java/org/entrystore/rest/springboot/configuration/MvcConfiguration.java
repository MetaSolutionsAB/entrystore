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

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.accept.ContentNegotiationStrategy;
import org.springframework.web.accept.HeaderContentNegotiationStrategy;
import org.springframework.web.servlet.config.annotation.AsyncSupportConfigurer;
import org.springframework.web.servlet.config.annotation.ContentNegotiationConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;
import java.util.concurrent.ThreadPoolExecutor;

@Configuration
@RequiredArgsConstructor
public class MvcConfiguration implements WebMvcConfigurer {

	private final MvcAsyncConfiguration asyncConfig;

	@Override
	public void configureContentNegotiation(ContentNegotiationConfigurer configurer) {

		ContentNegotiationStrategy defaultStrategy = new HeaderContentNegotiationStrategy();

		configurer
			.defaultContentType(MediaType.APPLICATION_JSON)
			.favorParameter(true)
			.parameterName("format")
			.mediaType("xml", MediaType.APPLICATION_XML)
			.mediaType("json", MediaType.APPLICATION_JSON)
			.strategies(List.of(new EntryEndpointContentNegotiationStrategy(defaultStrategy)));
	}

	@Override
	public void configureAsyncSupport(AsyncSupportConfigurer configurer) {
		configurer.setTaskExecutor(mvcAsyncTaskExecutor());
		configurer.setDefaultTimeout(asyncConfig.defaultTimeoutMs());
	}

	/**
	 * Bounded executor for Spring MVC async dispatch. The default executor
	 * ({@code SimpleAsyncTaskExecutor}) creates a new platform thread per request without bound or
	 * pool. For anonymous endpoints that return {@code StreamingResponseBody} (e.g. {@code /sparql}),
	 * that lets an attacker pin thousands of native threads in parallel — bypassing per-request size,
	 * body, and time caps. This bounded pool rejects the overflow with {@code AbortPolicy}
	 * ({@code RejectedExecutionException}, mapped to 503 by {@code AppExceptionHandler}) instead.
	 * <p>
	 * Declared as a bean so the container owns its lifecycle: {@code afterPropertiesSet()} calls
	 * {@code initialize()} on startup and {@code destroy()} shuts the pool down on context close,
	 * preventing the non-daemon {@code mvc-async-*} threads from surviving graceful shutdown.
	 */
	@Bean
	ThreadPoolTaskExecutor mvcAsyncTaskExecutor() {
		ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
		executor.setCorePoolSize(asyncConfig.corePoolSize());
		executor.setMaxPoolSize(asyncConfig.maxPoolSize());
		executor.setQueueCapacity(asyncConfig.queueCapacity());
		executor.setThreadNamePrefix("mvc-async-");
		executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
		return executor;
	}
}
