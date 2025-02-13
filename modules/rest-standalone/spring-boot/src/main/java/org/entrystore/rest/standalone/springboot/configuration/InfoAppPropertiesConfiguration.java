package org.entrystore.rest.standalone.springboot.configuration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "info")
public record InfoAppPropertiesConfiguration(App app) {

	public record App(String version) {
	}
}
