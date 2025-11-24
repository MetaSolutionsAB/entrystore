package org.entrystore.rest.standalone.springboot.configuration;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.entrystore.ContextManager;
import org.entrystore.PrincipalManager;
import org.entrystore.config.Config;
import org.entrystore.impl.RepositoryManagerImpl;
import org.entrystore.repository.RepositoryManager;
import org.entrystore.repository.config.ConfigurationManager;
import org.entrystore.repository.config.Settings;
import org.entrystore.rest.standalone.springboot.service.auth.LoginTokenCache;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.net.URI;

import static org.entrystore.repository.config.Settings.AUTH_SAML_ENABLED;
import static org.entrystore.repository.config.Settings.SOLR_URL;

@Slf4j
@Configuration
@RequiredArgsConstructor
public class EntryStoreConfiguration {

	@Value("${entrystore.solr.url}")
	private String solrUrl;

	@Value("${app.security.saml.enabled:false}")
	private boolean samlAuthEnabled;

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

		// Pass Spring properties to ES config
		if (solrUrl != null) {
			config.setProperty(SOLR_URL, solrUrl);
		}
		config.setProperty(AUTH_SAML_ENABLED, samlAuthEnabled);

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

	@Bean
	public ContextManager createContextManager(RepositoryManager repositoryManager) {
		return repositoryManager.getContextManager();
	}

	@Bean
	public LoginTokenCache createLoginTokenCache(Config config) {
		return new LoginTokenCache(config);
	}

	@Bean
	public RestTemplate restTemplate() {
		return new RestTemplate();
	}

}
