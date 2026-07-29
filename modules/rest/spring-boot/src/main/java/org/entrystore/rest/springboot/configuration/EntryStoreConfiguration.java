/*
 * Copyright (c) 2007-2026 MetaSolutions AB
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.entrystore.rest.springboot.configuration;

import com.github.benmanes.caffeine.cache.Ticker;
import lombok.RequiredArgsConstructor;
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
import org.entrystore.rest.springboot.util.PrincipalManagerUtil;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.Environment;
import org.springframework.core.env.PropertySource;
import org.springframework.web.client.RestClient;

import java.time.Duration;

@Configuration
@RequiredArgsConstructor
public class EntryStoreConfiguration {

	private static final String ENTRYSTORE_CONFIG_PREFIX = "entrystore";

	private final Environment environment;

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

	@Bean(destroyMethod = "shutdown")
	public RepositoryManagerImpl createRepositoryManager(Config config) {
		String baseURI = config.getString(Settings.BASE_URL);
		if (baseURI == null) {
			throw new IllegalStateException("No base URL specified, set " + Settings.BASE_URL);
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

	/**
	 * Constructs the scheduler only — {@code BackupSchedulerStarter} arms the Quartz schedule once the
	 * application is ready. Returns {@code null} when no cron expression is configured, which leaves
	 * the {@code Optional<BackupScheduler>} injection points empty.
	 */
	@Bean
	@ConditionalOnProperty(name = Settings.BACKUP_SCHEDULER, havingValue = "on")
	public BackupScheduler backupScheduler(RepositoryManagerImpl repositoryManager) {
		PrincipalManager pm = repositoryManager.getPrincipalManager();
		return PrincipalManagerUtil.runAsAdmin(pm, () -> BackupScheduler.createInstance(repositoryManager));
	}

	/**
	 * Dedicated RestClient for the reCAPTCHA verifier. Tight timeouts ensure that a hung
	 * Google siteverify call cannot pin a Jetty request thread indefinitely while users
	 * try to sign up or reset their password.
	 */
	@Bean
	public RestClient recaptchaRestClient(RestClient.Builder builder) {
		HttpClientSettings settings = HttpClientSettings.defaults()
			.withConnectTimeout(Duration.ofSeconds(3))
			.withReadTimeout(Duration.ofSeconds(5));
		return builder
			.requestFactory(ClientHttpRequestFactoryBuilder.detect().build(settings))
			.build();
	}

	@Bean
	public Ticker systemTicker() {
		return Ticker.systemTicker();
	}

}
