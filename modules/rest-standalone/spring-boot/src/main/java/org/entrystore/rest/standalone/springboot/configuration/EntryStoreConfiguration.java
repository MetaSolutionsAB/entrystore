package org.entrystore.rest.standalone.springboot.configuration;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.entrystore.PrincipalManager;
import org.entrystore.config.Config;
import org.entrystore.impl.RepositoryManagerImpl;
import org.entrystore.repository.RepositoryManager;
import org.entrystore.repository.config.ConfigurationManager;
import org.entrystore.repository.config.Settings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.net.URI;

@Slf4j
@Configuration
@RequiredArgsConstructor
public class EntryStoreConfiguration {

	private final EntryStorePropertiesConfiguration propertiesConfiguration;

	@Bean
	public Config createEntryStoreConfiguration() throws IOException {
		var configURI = propertiesConfiguration.configuration().path();
		if (configURI != null) {
			log.info("Manually specified config location at {}", configURI);
			return new ConfigurationManager(URI.create(configURI)).getConfiguration();
		} else {
			log.info("No config location specified, looking within classpath");
			return new ConfigurationManager(ConfigurationManager.getConfigurationURI()).getConfiguration();
		}
	}

	@Bean
	public RepositoryManager createRepositoryManager(Config config) {
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
