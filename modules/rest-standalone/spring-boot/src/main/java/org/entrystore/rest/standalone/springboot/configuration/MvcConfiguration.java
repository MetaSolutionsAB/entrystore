package org.entrystore.rest.standalone.springboot.configuration;

import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.web.accept.ContentNegotiationStrategy;
import org.springframework.web.accept.HeaderContentNegotiationStrategy;
import org.springframework.web.servlet.config.annotation.ContentNegotiationConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

@Configuration
public class MvcConfiguration implements WebMvcConfigurer {

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
}
