package org.entrystore.rest.standalone.springboot.configuration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "entrystore")
public record EntryStorePropertiesConfiguration(Configuration configuration) {

	public record Configuration(String path) {
	}
}
