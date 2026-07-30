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

import org.entrystore.rest.springboot.configuration.SmtpProperties.Addresses;
import org.entrystore.rest.springboot.configuration.SmtpProperties.SmtpSecurity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SmtpPropertiesTest {

	@ParameterizedTest(name = "security={0}, ssl={1} -> {2}")
	@CsvSource(nullValues = "NULL", value = {
			"NULL,     NULL,       OFF",
			"SSL,      NULL,       SSL",
			"STARTTLS, NULL,       STARTTLS",
			"NULL,     starttls,   STARTTLS",
			"NULL,     ssl,        SSL",
			"NULL,     off,        OFF",
			"NULL,     STARTTLS,   STARTTLS",
			"SSL,      ssl,        SSL"
	})
	void security_resolvesTheCanonicalKeyThenTheDeprecatedAlias(
			SmtpSecurity security, String ssl, SmtpSecurity expected) {
		SmtpProperties properties = properties(security, ssl);

		// The canonical constructor folds the alias in, so the component itself carries the answer and
		// there is no accessor left that returns a stale one.
		assertEquals(expected, properties.security());
		assertEquals(expected, properties.effectiveSecurity());
	}

	@Test
	void canonicalKeyWins_overADisagreeingAliasWithoutFailingStartup() {
		// Previously a hard IllegalArgumentException, which made the migration path the documentation
		// prescribes — add entrystore.smtp.security next to an existing entrystore.smtp.ssl — an outage.
		SmtpProperties properties = properties(SmtpSecurity.STARTTLS, "off");

		assertEquals(SmtpSecurity.STARTTLS, properties.effectiveSecurity());
		assertTrue(properties.usesDeprecatedSslKey());
	}

	@ParameterizedTest(name = "ssl=''{0}'' -> OFF, startup survives")
	@CsvSource({"true", "on", "yes", "1", "tls", "enabled"})
	void unmappableAliasValue_isIgnoredRatherThanAbortingStartup(String ssl) {
		// entrystore.smtp.ssl was never read before 6.1, so its value was never constrained. A stale
		// entry must not be able to take the whole application down; it resolves to OFF — exactly what
		// that deployment already did — and is reported at ERROR.
		SmtpProperties properties = properties(null, ssl);

		assertEquals(SmtpSecurity.OFF, properties.effectiveSecurity());
		assertTrue(properties.usesDeprecatedSslKey(), "the key is still present, so the deprecation stands");
	}

	@Test
	void absentEmailBlock_bindsToEmptyAddressesRatherThanNull() {
		SmtpProperties properties = new SmtpProperties("smtp.example.com", 25, null, null, null, null, true,
				Duration.ofSeconds(5), Duration.ofSeconds(5), Duration.ofSeconds(5), 3, null);

		assertNotNull(properties.email(), "an absent entrystore.smtp.email block must not leave a null");
		assertEquals(new Addresses(null, null, null), properties.email());
	}

	@Test
	void hasCredentials_requiresBothAndTreatsBlankAsUnset() {
		// A blank password binds to "" rather than null, which previously forced mail.smtp.auth with an
		// empty credential and made every send fail.
		assertFalse(withCredentials(null, null).hasCredentials());
		assertTrue(withCredentials("user", "secret").hasCredentials());
		assertFalse(withCredentials("   ", "   ").hasCredentials());
	}

	@Test
	void halfSetCredentialPair_failsFast() {
		assertThrows(IllegalArgumentException.class, () -> withCredentials("user", null));
		assertThrows(IllegalArgumentException.class, () -> withCredentials(null, "secret"));
		assertThrows(IllegalArgumentException.class, () -> withCredentials("user", "  "));
	}

	@Test
	void isConfigured_isFalseForBlankHost() {
		assertFalse(hostOf(null).isConfigured());
		assertFalse(hostOf("   ").isConfigured());
		assertTrue(hostOf("smtp.example.com").isConfigured());
	}

	@Test
	void portOutsideTheValidRange_failsFastNamingTheKeyAndValue() {
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
				() -> new SmtpProperties("smtp.example.com", 70000, null, null, null, null, true,
						Duration.ofSeconds(5), Duration.ofSeconds(5), Duration.ofSeconds(5), 3, null));

		assertEquals("entrystore.smtp.port must be between 1 and 65535, got 70000", e.getMessage());
	}

	@Test
	void nonPositiveTimeout_failsFastNamingTheKebabCaseKey() {
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
				() -> new SmtpProperties("smtp.example.com", 25, null, null, null, null, true,
						Duration.ZERO, Duration.ofSeconds(5), Duration.ofSeconds(5), 3, null));

		assertTrue(e.getMessage().startsWith("entrystore.smtp.connection-timeout must be positive"),
				"got: " + e.getMessage());
	}

	@Test
	void maxSendAttemptsOutsideTheRange_failsFast() {
		// Bounded above because the retry loop has no backoff and runs on the request thread: 500
		// attempts against a firewalled MTA would pin a Jetty thread for roughly 42 minutes.
		assertThrows(IllegalArgumentException.class, () -> withMaxSendAttempts(0));
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> withMaxSendAttempts(500));
		assertEquals("entrystore.smtp.max-send-attempts must be between 1 and 10, got 500", e.getMessage());
		assertEquals(10, withMaxSendAttempts(10).maxSendAttempts());
	}

	@Test
	void bindsEveryKeyFromTheEnvironment() {
		// Constructing the record directly cannot catch a misspelt component: these assertions are what
		// pin the actual property-key spellings, including relaxed binding of the kebab-case names and
		// the nested entrystore.smtp.email block.
		runner().withPropertyValues(
				"entrystore.smtp.host=smtp.example.com",
				"entrystore.smtp.port=2525",
				"entrystore.smtp.security=starttls",
				"entrystore.smtp.username=user",
				"entrystore.smtp.password=secret",
				"entrystore.smtp.check-server-identity=false",
				"entrystore.smtp.connection-timeout=9s",
				"entrystore.smtp.read-timeout=8s",
				"entrystore.smtp.write-timeout=7s",
				"entrystore.smtp.max-send-attempts=5",
				"entrystore.smtp.email.from=from@example.com",
				"entrystore.smtp.email.bcc=bcc@example.com",
				"entrystore.smtp.email.reply-to=reply@example.com"
		).run(context -> {
			SmtpProperties smtp = context.getBean(SmtpProperties.class);
			assertEquals("smtp.example.com", smtp.host());
			assertEquals(2525, smtp.port());
			assertEquals(SmtpSecurity.STARTTLS, smtp.security());
			assertEquals("user", smtp.username());
			assertEquals("secret", smtp.password());
			assertFalse(smtp.checkServerIdentity());
			assertEquals(Duration.ofSeconds(9), smtp.connectionTimeout());
			assertEquals(Duration.ofSeconds(8), smtp.readTimeout());
			assertEquals(Duration.ofSeconds(7), smtp.writeTimeout());
			assertEquals(5, smtp.maxSendAttempts());
			assertEquals(new Addresses("from@example.com", "bcc@example.com", "reply@example.com"), smtp.email());
		});
	}

	@Test
	void bindsTheDeprecatedSslKeyFromTheEnvironment() {
		// The bug this ticket fixes: entrystore.smtp.ssl was documented in every released
		// entrystore.properties_example but never read, so these deployments sent plaintext.
		runner().withPropertyValues("entrystore.smtp.ssl=starttls")
				.run(context -> {
					SmtpProperties smtp = context.getBean(SmtpProperties.class);
					assertEquals(SmtpSecurity.STARTTLS, smtp.effectiveSecurity());
					assertTrue(smtp.usesDeprecatedSslKey());
				});
	}

	@Test
	void staleBooleanAliasValue_bindsAndStartsInsteadOfAbortingTheContext() {
		runner().withPropertyValues("entrystore.smtp.ssl=true")
				.run(context -> {
					assertNull(context.getStartupFailure(),
							"a stale entrystore.smtp.ssl value must never abort startup");
					assertEquals(SmtpSecurity.OFF, context.getBean(SmtpProperties.class).effectiveSecurity());
				});
	}

	@Test
	void unrecognisedCanonicalValue_failsStartup() {
		runner().withPropertyValues("entrystore.smtp.security=tls")
				.run(context -> assertNotNull(context.getStartupFailure(),
						"an unrecognised value for the canonical key must fail startup, not mean 'off'"));
	}

	@Test
	void noSmtpKeysAtAll_bindsDefaultsAndReportsUnconfigured() {
		runner().run(context -> {
			SmtpProperties smtp = context.getBean(SmtpProperties.class);
			assertFalse(smtp.isConfigured());
			assertEquals(25, smtp.port());
			assertEquals(SmtpSecurity.OFF, smtp.effectiveSecurity());
			assertFalse(smtp.usesDeprecatedSslKey());
			assertEquals(Duration.ofSeconds(5), smtp.connectionTimeout());
			assertEquals(3, smtp.maxSendAttempts());
			assertNotNull(smtp.email());
		});
	}

	private static ApplicationContextRunner runner() {
		return new ApplicationContextRunner().withUserConfiguration(EnableSmtpProperties.class);
	}

	@EnableConfigurationProperties(SmtpProperties.class)
	static class EnableSmtpProperties {
	}

	private static SmtpProperties properties(SmtpSecurity security, String ssl) {
		return new SmtpProperties("smtp.example.com", 25, security, ssl, null, null, true,
				Duration.ofSeconds(5), Duration.ofSeconds(5), Duration.ofSeconds(5), 3, null);
	}

	private static SmtpProperties withCredentials(String username, String password) {
		return new SmtpProperties("smtp.example.com", 25, null, null, username, password, true,
				Duration.ofSeconds(5), Duration.ofSeconds(5), Duration.ofSeconds(5), 3, null);
	}

	private static SmtpProperties withMaxSendAttempts(int maxSendAttempts) {
		return new SmtpProperties("smtp.example.com", 25, null, null, null, null, true,
				Duration.ofSeconds(5), Duration.ofSeconds(5), Duration.ofSeconds(5), maxSendAttempts, null);
	}

	private static SmtpProperties hostOf(String host) {
		return new SmtpProperties(host, 25, null, null, null, null, true,
				Duration.ofSeconds(5), Duration.ofSeconds(5), Duration.ofSeconds(5), 3, null);
	}
}
