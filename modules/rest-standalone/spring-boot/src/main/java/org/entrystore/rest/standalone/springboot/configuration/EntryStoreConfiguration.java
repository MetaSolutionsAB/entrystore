package org.entrystore.rest.standalone.springboot.configuration;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.entrystore.PrincipalManager;
import org.entrystore.config.Config;
import org.entrystore.impl.RepositoryManagerImpl;
import org.entrystore.repository.RepositoryManager;
import org.entrystore.repository.config.ConfigurationManager;
import org.entrystore.repository.config.Settings;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.net.URI;

@Slf4j
@Configuration
@RequiredArgsConstructor
public class EntryStoreConfiguration {

	@Value("${entrystore.solr.url}")
	private String solrUrl;

	private final EntryStorePropertiesConfiguration propertiesConfiguration;

	@Bean
	public Config createEntryStoreConfiguration() throws IOException {
		var configURI = propertiesConfiguration.configuration().path();
		Config config;
		if (configURI != null) {
			log.info("Manually specified config location at {}", configURI);
			config = new ConfigurationManager(URI.create(configURI)).getConfiguration();
		} else {
			log.info("No config location specified, looking within classpath");
			config = new ConfigurationManager(ConfigurationManager.getConfigurationURI()).getConfiguration();
		}

		if (solrUrl != null) {
			config.setProperty("entrystore.solr.url", solrUrl);
		}
		return config;
	}

	@Bean
	public RepositoryManagerImpl createRepositoryManager(Config config) {
		String baseURI = config.getString(Settings.BASE_URL);
		if (baseURI == null) {
			log.error("No Base URI specified, exiting");
			System.exit(1);
		}
		return new RepositoryManagerImpl(baseURI, config);
	}

	@Bean
	public PrincipalManager createPrincipalManager(RepositoryManager repositoryManager) {
		return repositoryManager.getPrincipalManager();
	}
}
