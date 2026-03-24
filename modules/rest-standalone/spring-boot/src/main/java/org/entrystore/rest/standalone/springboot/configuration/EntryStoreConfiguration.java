package org.entrystore.rest.standalone.springboot.configuration;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.entrystore.ContextManager;
import org.entrystore.PrincipalManager;
import org.entrystore.config.Config;
import org.entrystore.config.Configurations;
import org.entrystore.impl.RepositoryManagerImpl;
import org.entrystore.repository.RepositoryManager;
import org.entrystore.repository.backup.BackupScheduler;
import org.entrystore.repository.config.PropertiesConfiguration;
import org.entrystore.repository.config.Settings;
import org.entrystore.repository.config.SortedProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.Environment;
import org.springframework.core.env.PropertySource;
import org.springframework.web.client.RestTemplate;

import java.net.URI;

@Slf4j
@Configuration
@RequiredArgsConstructor
public class EntryStoreConfiguration {

	private static final String ENTRYSTORE_CONFIG_PREFIX = "entrystore";

	private final Environment environment;

	@PostConstruct
	public void logBeanStatus() {
		if (!"on".equalsIgnoreCase(environment.getProperty(Settings.BACKUP_SCHEDULER))) {
			log.warn("Backup is disabled in configuration");
		}
	}

	/**
	 * Creates a bean with Entrystore configuration needed for core.
	 * Properties (key and value pairs) are read from property files (*.yml and *.properties) that were loaded on init by Spring-boot
	 *
	 * @return a Config class (essentially a wrapper around java.util.Properties) needed for Entrystore core to work
	 */
	@Bean
	public Config createEntryStoreConfiguration() {
		SortedProperties properties = fetchSpringPropertiesWithPrefix(ENTRYSTORE_CONFIG_PREFIX);
		return Configurations.synchronizedConfig(new PropertiesConfiguration("EntryStore Configuration", properties));
	}

	private SortedProperties fetchSpringPropertiesWithPrefix(String prefix) {

		SortedProperties properties = new SortedProperties();
		if (environment instanceof ConfigurableEnvironment configEnv) {
			for (PropertySource<?> propertySource : configEnv.getPropertySources()) {
				if (propertySource instanceof EnumerablePropertySource<?> enumerable) {
					for (String key : enumerable.getPropertyNames()) {
						if (key.startsWith(prefix)) {
							properties.put(key, environment.getProperty(key));
						}
					}
				}
			}
		}
		return properties;
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
	@ConditionalOnProperty(name = Settings.BACKUP_SCHEDULER, havingValue = "on")
	public BackupScheduler backupScheduler(RepositoryManagerImpl repositoryManager) {
		log.info("Starting backup scheduler");
		PrincipalManager pm = repositoryManager.getPrincipalManager();
		URI currentUser = pm.getAuthenticatedUserURI();
		try {
			pm.setAuthenticatedUserURI(pm.getAdminUser().getURI());
			BackupScheduler bs = BackupScheduler.createInstance(repositoryManager);
			if (bs != null) {
				bs.run();
			}
			return bs;
		} finally {
			pm.setAuthenticatedUserURI(currentUser);
		}
	}

	@Bean
	public RestTemplate restTemplate() {
		return new RestTemplate();
	}

}
