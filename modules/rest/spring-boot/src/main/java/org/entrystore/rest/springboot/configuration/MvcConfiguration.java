package org.entrystore.rest.springboot.configuration;

import lombok.RequiredArgsConstructor;
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
		// Default Spring MVC async executor (SimpleAsyncTaskExecutor) creates a new platform
		// thread per request without bound or pool. For anonymous endpoints that return
		// StreamingResponseBody (e.g. /sparql), this means an attacker can pin thousands of
		// native threads in parallel — bypassing per-request size, body, and time caps.
		// Replace with a bounded pool that rejects the overflow as 503/AbortPolicy instead.
		ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
		executor.setCorePoolSize(asyncConfig.corePoolSize());
		executor.setMaxPoolSize(asyncConfig.maxPoolSize());
		executor.setQueueCapacity(asyncConfig.queueCapacity());
		executor.setThreadNamePrefix("mvc-async-");
		executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
		executor.initialize();
		configurer.setTaskExecutor(executor);
		configurer.setDefaultTimeout(asyncConfig.defaultTimeoutMs());
	}
}
