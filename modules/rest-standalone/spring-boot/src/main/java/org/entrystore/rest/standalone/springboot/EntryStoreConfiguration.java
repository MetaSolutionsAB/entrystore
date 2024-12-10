package org.entrystore.rest.standalone.springboot;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "info")
public record EntryStoreConfiguration(App app) {

	public record App(String version) {
	}
}
