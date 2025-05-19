package org.entrystore.rest.standalone.springboot.configuration;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.web.accept.ContentNegotiationManager;
import org.springframework.web.accept.ContentNegotiationStrategy;
import org.springframework.web.accept.HeaderContentNegotiationStrategy;
import org.springframework.web.servlet.config.annotation.ContentNegotiationConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

@Configuration
@RequiredArgsConstructor
public class MvcConfiguration implements WebMvcConfigurer {

	private final ContentNegotiationManager manager;

	@Override
	public void configureContentNegotiation(ContentNegotiationConfigurer configurer) {

		List<ContentNegotiationStrategy> existingStrategies = manager.getStrategies();
		ContentNegotiationStrategy defaultStrategy = (existingStrategies.isEmpty()) ? new HeaderContentNegotiationStrategy() : existingStrategies.getFirst();
		existingStrategies.add(new EntryEndpointContentNegotiationStrategy(defaultStrategy));

		configurer
			.defaultContentType(MediaType.APPLICATION_JSON)
			.favorParameter(true)
			.parameterName("format")
			.mediaType("xml", MediaType.APPLICATION_XML)
			.mediaType("json", MediaType.APPLICATION_JSON)
			.strategies(existingStrategies);
	}
}
