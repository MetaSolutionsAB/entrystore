package org.entrystore.rest.standalone.quarkus.configuration;

import io.smallrye.config.ConfigMapping;

@ConfigMapping(prefix = "info")
public interface EntryStoreConfiguration {

	App app();

	interface App {
		String version();
	}
}
