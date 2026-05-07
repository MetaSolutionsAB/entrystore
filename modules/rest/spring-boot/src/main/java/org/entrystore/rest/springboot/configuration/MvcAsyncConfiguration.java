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

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Configuration properties for the bounded MVC async-dispatch thread pool. Consumed by
 * {@code MvcConfiguration.configureAsyncSupport}, which uses these values to construct the
 * {@code ThreadPoolTaskExecutor} that Spring MVC uses for {@code StreamingResponseBody} and
 * {@code DeferredResult} dispatch (replacing Spring's default unbounded
 * {@code SimpleAsyncTaskExecutor}). Overflow beyond {@code maxPoolSize + queueCapacity} is
 * rejected via {@code AbortPolicy} and mapped to HTTP 503 by {@code AppExceptionHandler}.
 */
@ConfigurationProperties(prefix = "mvc.async")
public record MvcAsyncConfiguration(
		@DefaultValue("8") int corePoolSize,
		@DefaultValue("32") int maxPoolSize,
		@DefaultValue("64") int queueCapacity,
		@DefaultValue("15000") long defaultTimeoutMs
) {
	public MvcAsyncConfiguration {
		if (corePoolSize < 0) {
			throw new IllegalArgumentException(
					"mvc.async.core-pool-size must be >= 0, got " + corePoolSize);
		}
		if (maxPoolSize < 1 || maxPoolSize < corePoolSize) {
			throw new IllegalArgumentException(
					"mvc.async.max-pool-size must be >= max(1, core-pool-size), got " + maxPoolSize);
		}
		if (queueCapacity < 0) {
			throw new IllegalArgumentException(
					"mvc.async.queue-capacity must be >= 0, got " + queueCapacity);
		}
		if (defaultTimeoutMs <= 0) {
			throw new IllegalArgumentException(
					"mvc.async.default-timeout-ms must be > 0, got " + defaultTimeoutMs);
		}
	}
}
