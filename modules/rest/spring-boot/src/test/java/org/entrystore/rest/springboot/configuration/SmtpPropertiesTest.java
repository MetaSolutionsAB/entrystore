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

import org.apache.logging.log4j.Level;
import org.entrystore.rest.springboot.configuration.SmtpProperties.Addresses;
import org.entrystore.rest.springboot.configuration.SmtpProperties.SmtpSecurity;
import org.entrystore.rest.springboot.util.CapturingAppender;
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
			"ssl,      NULL,       SSL",
			"starttls, NULL,       STARTTLS",
			"off,      NULL,       OFF",
			"STARTTLS, NULL,       STARTTLS",
			"NULL,     starttls,   STARTTLS",
			"NULL,     ssl,        SSL",
			"NULL,     off,        OFF",
			"NULL,     STARTTLS,   STARTTLS",
			"ssl,      ssl,        SSL",
			// An unrecognised canonical value falls through to the alias rather than winning as garbage.
			"tls,      starttls,   STARTTLS",
			"tls,      NULL,       OFF"
	})
	void effectiveSecurity_resolvesTheCanonicalKeyThenTheDeprecatedAlias(
			String security, String ssl, SmtpSecurity expected) {
		assertEquals(expected, properties(security, ssl).effectiveSecurity());
	}

	@Test
	void rawAccessorsReturnTheConfiguredStrings_notTheEffectiveValue() {
		SmtpProperties properties = properties("tls", "starttls");

		// security() and ssl() are the raw bound values, deliberately: a call site writing
		// "ssl".equalsIgnoreCase(smtp.security()) would reintroduce the original silent-plaintext bug,
		// so effectiveSecurity() is the only accessor that answers what is actually applied.
		assertEquals("tls", properties.security());
		assertEquals("starttls", properties.ssl());
		assertEquals(SmtpSecurity.STARTTLS, properties.effectiveSecurity());
	}

	@Test
	void canonicalKeyWins_overADisagreeingAliasWithoutFailingStartup() {
		// Previously a hard IllegalArgumentException, which made the migration path the documentation
		// prescribes — add entrystore.smtp.security next to an existing entrystore.smtp.ssl — an outage.
		SmtpProperties properties = properties("starttls", "off");

		assertEquals(SmtpSecurity.STARTTLS, properties.effectiveSecurity());
		assertTrue(properties.usesDeprecatedSslKey());
	}

	@ParameterizedTest(name = "unrecognised ''{0}'' in either key -> unresolved, startup survives")
	@CsvSource({"true", "on", "yes", "1", "tls", "none", "enabled"})
	void unrecognisedValue_isUnresolvedRatherThanAbortingStartup(String value) {
		// Aborting startup would take the whole REST API down over a mail setting. But leniency is not
		// permission to fail open: the value stays unresolved, and EmailSender refuses to send rather than
		// sending in the clear under a configuration the operator believes is encrypted.
		assertTrue(properties(null, value).securityIsUnresolved());
		assertTrue(properties(value, null).securityIsUnresolved());
		assertFalse(properties(value, null).plaintextIsDeclared(),
				"an unresolvable value is not a declaration of plaintext");
	}

	@ParameterizedTest(name = "relaxed spelling ''{0}'' still resolves to STARTTLS")
	@CsvSource({"STARTTLS", "starttls", "START_TLS", "start-tls", "Start.Tls", "StartTls"})
	void relaxedSpellings_resolveAsBootsEnumBindingDid(String value) {
		// While this bound as an enum, Boot's LenientObjectToEnumConverterFactory canonicalised on letters
		// and digits, so every one of these reached STARTTLS. Matching the constant name exactly would
		// silently demote ENTRYSTORE_SMTP_SECURITY=START_TLS — the form an environment variable naturally
		// carries — to plaintext.
		assertEquals(SmtpSecurity.STARTTLS, properties(value, null).effectiveSecurity());
		assertFalse(properties(value, null).securityIsUnresolved());
	}

	@Test
	void explicitOff_declaresPlaintextWhileAnUnsetKeyDoesNot() {
		// What makes MailConfiguration's "transport security is off" warning clearable, exactly as that
		// warning advertises. An unclearable warning is a warning that gets filtered — along with the
		// deprecated-key, cleartext-credentials and check-server-identity warnings beside it.
		assertTrue(properties("off", null).plaintextIsDeclared());
		assertTrue(properties(null, "off").plaintextIsDeclared());
		assertFalse(properties(null, null).plaintextIsDeclared());
		assertEquals(SmtpSecurity.OFF, properties(null, null).effectiveSecurity());
	}

	@Test
	void unresolvedSecurity_isReportedAtErrorNamingTheKeyAndValue() {
		// These diagnostics are the whole replacement for the fail-fast validation that was removed, and
		// nothing covered them: deleting the report or flipping its condition kept the suite green.
		try (CapturingAppender appender = CapturingAppender.attachTo(SmtpProperties.class)) {
			properties("tls", null);

			appender.assertCapturedSomething();
			assertTrue(appender.messagesAt(Level.ERROR).anyMatch(message ->
							message.contains("entrystore.smtp.security") && message.contains("tls")),
					"the ERROR must name both the key and the offending value; got: " + appender);
		}
	}

	@Test
	void unresolvedAliasIsReportedOnlyWhenTheCanonicalKeyDidNotDecide() {
		// ssl=true beside security=starttls used to log "transport security is off" while STARTTLS was in
		// fact required — a false diagnostic on the exact migration path the documentation prescribes.
		try (CapturingAppender appender = CapturingAppender.attachTo(SmtpProperties.class)) {
			properties("starttls", "true");

			assertEquals(0, appender.countAt(Level.ERROR),
					"the canonical key decided the outcome, so the alias must stay quiet; got: " + appender);
		}
	}

	@Test
	void disagreeingKeys_areReportedAtWarnWithTheCanonicalKeyWinning() {
		try (CapturingAppender appender = CapturingAppender.attachTo(SmtpProperties.class)) {
			SmtpProperties properties = properties("starttls", "off");

			assertEquals(SmtpSecurity.STARTTLS, properties.effectiveSecurity());
			assertTrue(appender.messagesAt(Level.WARN)
							.anyMatch(message -> message.contains("disagree")),
					"got: " + appender);
		}
	}

	@Test
	void withoutAConfiguredHost_theSecurityDiagnosticsStayQuiet() {
		// A stale value in a deployment that never sends mail would otherwise log ERROR on every boot,
		// which ERROR-based alerting cannot tell apart from a live relay with broken transport security.
		try (CapturingAppender appender = CapturingAppender.attachTo(SmtpProperties.class)) {
			new SmtpProperties(null, 25, "tls", null, null, null, true,
					Duration.ofSeconds(5), Duration.ofSeconds(5), Duration.ofSeconds(5), 3, null);

			assertEquals(0, appender.countAt(Level.ERROR), "got: " + appender);
		}
	}

	@Test
	void anExcessiveBlockingBudget_isReportedAtWarn() {
		try (CapturingAppender appender = CapturingAppender.attachTo(SmtpProperties.class)) {
			new SmtpProperties("smtp.example.com", 25, "off", null, null, null, true,
					Duration.ofMinutes(5), Duration.ofMinutes(5), Duration.ofMinutes(5), 10, null);

			assertTrue(appender.messagesAt(Level.WARN)
							.anyMatch(message -> message.contains("max-send-attempts")),
					"got: " + appender);
		}
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
	void halfSetCredentialPair_disablesAuthenticationInsteadOfAbortingStartup() {
		// EntryStore 6.0 degraded to an anonymous session here and still delivered mail. Throwing would
		// escalate a mail-only misconfiguration into a full startup outage on upgrade.
		SmtpProperties usernameOnly = withCredentials("relay-user", null);

		assertFalse(usernameOnly.hasCredentials());
		// The component is left as bound rather than nulled: requiring both above is what keeps a half-set
		// pair away from the sender, and keeping it means /actuator/configprops still shows the key as
		// bound-and-discarded instead of unbound, which would point at a binding problem rather than at the
		// empty value the operator actually set.
		assertEquals("relay-user", usernameOnly.username());

		// Including the empty-string form, which is what ${SMTP_PASSWORD} resolving to "" binds to.
		assertFalse(withCredentials(null, "secret").hasCredentials());
		assertFalse(withCredentials("relay-user", "  ").hasCredentials());
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
		// Bounded above because the retry loop has no backoff: 500 attempts against a firewalled MTA
		// would occupy its thread for roughly 42 minutes.
		assertThrows(IllegalArgumentException.class, () -> withMaxSendAttempts(0));
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> withMaxSendAttempts(500));
		assertEquals("entrystore.smtp.max-send-attempts must be between 1 and 10, got 500", e.getMessage());
		assertEquals(10, withMaxSendAttempts(10).maxSendAttempts());
	}

	@Test
	void worstCaseSendDuration_chargesTheReadTimeoutOncePerBlockingRead() {
		// Each key is bounded individually, but their product is not — this is what the startup warning
		// reports, so the arithmetic behind it is worth pinning. read-timeout is mail.smtp.timeout, which
		// bounds an individual read rather than the exchange, and one SMTP conversation blocks on seven or
		// so responses; charging it once per attempt understated the figure several-fold.
		SmtpProperties properties = new SmtpProperties("smtp.example.com", 25, null, null, null, null, true,
				Duration.ofSeconds(10), Duration.ofSeconds(20), Duration.ofSeconds(30), 4, null);

		// 4 x (10 connect + 7 x 20 read + 30 write) = 12 minutes.
		assertEquals(Duration.ofMinutes(12), properties.worstCaseSendDuration());
	}

	@Test
	void defaultTimeoutsAndAttemptCount_stayBelowTheReportingThreshold() {
		// 3 x (5 + 7 x 5 + 5) = 135s, under the 180s threshold: a deployment that changes nothing stays
		// quiet even though the estimate is now several times larger.
		assertEquals(Duration.ofSeconds(135), hostOf("smtp.example.com").worstCaseSendDuration());
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
			assertEquals(SmtpSecurity.STARTTLS, smtp.effectiveSecurity());
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
	void staleValueInEitherKey_bindsAndStartsInsteadOfAbortingTheContext() {
		// The canonical key is covered here as well as the alias: it has been read since 4.8 (via
		// Settings.SMTP_SECURITY) but never validated, so a deployment carrying security=tls boots today
		// and must keep booting. Binding it as a strict enum would take the whole context down.
		runner().withPropertyValues("entrystore.smtp.ssl=true")
				.run(context -> {
					assertNull(context.getStartupFailure(),
							"a stale entrystore.smtp.ssl value must never abort startup");
					assertEquals(SmtpSecurity.OFF, context.getBean(SmtpProperties.class).effectiveSecurity());
				});

		runner().withPropertyValues("entrystore.smtp.security=tls")
				.run(context -> {
					assertNull(context.getStartupFailure(),
							"an unrecognised entrystore.smtp.security value must never abort startup");
					assertEquals(SmtpSecurity.OFF, context.getBean(SmtpProperties.class).effectiveSecurity());
				});
	}

	@Test
	void literalPlaceholderFromTheExampleFile_bindsWithoutAbortingStartup() {
		// entrystore.properties_example documents the key as `starttls|ssl|off`. Uncommented verbatim —
		// which is how the surrounding keys are written — that literal must stay harmless.
		runner().withPropertyValues("entrystore.smtp.security=starttls|ssl|off")
				.run(context -> {
					assertNull(context.getStartupFailure());
					assertEquals(SmtpSecurity.OFF, context.getBean(SmtpProperties.class).effectiveSecurity());
				});
	}

	@Test
	void halfSetCredentialsFromTheEnvironment_startTheContext() {
		runner().withPropertyValues("entrystore.smtp.username=relay-user")
				.run(context -> {
					assertNull(context.getStartupFailure(),
							"a half-set credential pair must not abort startup");
					assertFalse(context.getBean(SmtpProperties.class).hasCredentials());
				});
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

	private static SmtpProperties properties(String security, String ssl) {
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
