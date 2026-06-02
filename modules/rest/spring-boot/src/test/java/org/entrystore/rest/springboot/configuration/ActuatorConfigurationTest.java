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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.actuate.web.exchanges.HttpExchangeRepository;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ActuatorConfigurationTest {

	@ParameterizedTest(name = "{0}")
	@ValueSource(strings = {
			"entrystore.auth.adminpw",                      // admin-password override; key ends in "adminpw", not "password"
			"ENTRYSTORE.AUTH.ADMINPW",                      // matching is case-insensitive
			"entrystore.repository.store.password",
			"entrystore.solr.auth.password",
			"entrystore.smtp.password",
			"entrystore.auth.recaptcha.private-key",        // ends in "key"
			"spring.security.oauth2.client.registration.x.client-secret",
			"some.api.token",
			"vcap.services.db.credentials.uri"              // contains "credentials"
	})
	void masksSensitiveKeys(String key) {
		assertTrue(ActuatorConfiguration.isSensitiveEnvironmentKey(key), key + " should be masked");
	}

	@ParameterizedTest(name = "{0}")
	@ValueSource(strings = {
			"java.version",
			"server.port",
			"entrystore.solr.url",
			"entrystore.baseurl.folder",
			"entrystore.auth.password.rule.min-length",     // password POLICY, not a secret value
			"entrystore.auth.password.whitelist",
			"management.endpoints.web.base-path",
			"sun.java.command",                             // JVM-arg carrier — deliberately shown as operational data
			"JAVA_TOOL_OPTIONS"                             // JVM-arg carrier — deliberately shown as operational data
	})
	void showsNonSensitiveKeys(String key) {
		assertFalse(ActuatorConfiguration.isSensitiveEnvironmentKey(key), key + " should not be masked");
	}

	@ParameterizedTest(name = "{0} -> {1}")
	@CsvSource({
			"http://user:pass@host/db,           http://******@host/db",
			"http://:secret@host/db,             http://******@host/db",
			"jdbc:postgresql://u:p@h:5432/store, jdbc:postgresql://******@h:5432/store",
			"http://user@host/db,                http://******@host/db",
			"https://solr.example.com/solr,      https://solr.example.com/solr",
			"ldap://host:389/dc=x,               ldap://host:389/dc=x",
			"'not a url at all',                 'not a url at all'",
			"'contact user@example.com today',   'contact user@example.com today'"
	})
	void redactsUrlUserInfoFromValues(String input, String expected) {
		assertEquals(expected, ActuatorConfiguration.redactUrlUserInfo(input));
	}

	@Test
	void httpExchangeRepositoryPresentByDefault() {
		new ApplicationContextRunner()
				.withUserConfiguration(ActuatorConfiguration.class)
				.run(ctx -> assertThat(ctx).hasSingleBean(HttpExchangeRepository.class));
	}

	@Test
	void httpExchangeRepositoryAbsentWhenRecordingDisabled() {
		new ApplicationContextRunner()
				.withUserConfiguration(ActuatorConfiguration.class)
				.withPropertyValues("management.httpexchanges.recording.enabled=false")
				.run(ctx -> assertThat(ctx).doesNotHaveBean(HttpExchangeRepository.class));
	}
}
