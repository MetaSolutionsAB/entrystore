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

import org.entrystore.rest.springboot.configuration.SmtpProperties.SmtpSecurity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import java.time.Duration;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MailConfigurationTest {

	private final MailConfiguration configuration = new MailConfiguration();

	@Test
	void startTls_enablesAndRequiresTheUpgrade() {
		Properties props = javaMailPropertiesFor(SmtpSecurity.STARTTLS, null, null);

		assertEquals("true", props.get("mail.smtp.starttls.enable"));
		// "required" matters: without it a server that does not offer STARTTLS silently falls back to
		// plaintext, which is the failure mode this whole key exists to prevent.
		assertEquals("true", props.get("mail.smtp.starttls.required"));
		assertNull(props.get("mail.smtp.ssl.enable"));
	}

	@Test
	void implicitSsl_enablesTheSocketFactoryWithoutFallback() {
		Properties props = javaMailPropertiesFor(SmtpSecurity.SSL, null, null);

		assertEquals("true", props.get("mail.smtp.ssl.enable"));
		assertEquals("465", props.get("mail.smtp.socketFactory.port"));
		assertEquals("javax.net.ssl.SSLSocketFactory", props.get("mail.smtp.socketFactory.class"));
		assertEquals("false", props.get("mail.smtp.socketFactory.fallback"),
				"fallback would let a failed TLS handshake downgrade to plaintext");
		assertNull(props.get("mail.smtp.starttls.enable"));
	}

	@Test
	void securityOff_writesNoTlsProperties() {
		Properties props = javaMailPropertiesFor(SmtpSecurity.OFF, null, null);

		assertFalse(props.keySet().stream().anyMatch(key -> key.toString().contains("starttls")));
		assertNull(props.get("mail.smtp.ssl.enable"));
	}

	@ParameterizedTest(name = "ssl={0} alone -> {1} on the wire")
	@CsvSource({"starttls, mail.smtp.starttls.enable", "ssl, mail.smtp.ssl.enable"})
	void deprecatedSslAlias_reachesTheJavaMailProperties(String alias, String expectedProperty) {
		// The whole point of the ticket: a deployment configured only with the deprecated key must get
		// real transport security. Asserting effectiveSecurity() in SmtpPropertiesTest is not enough —
		// nothing there proves MailConfiguration reads it rather than the raw security component.
		SmtpProperties smtp = new SmtpProperties("smtp.example.com", 587, null, alias, null, null, true,
				Duration.ofSeconds(5), Duration.ofSeconds(5), Duration.ofSeconds(5), 3, null);

		Properties props = ((JavaMailSenderImpl) configuration.javaMailSender(smtp)).getJavaMailProperties();

		assertEquals("true", props.get(expectedProperty));
	}

	@Test
	void unmappableSslAlias_producesNoTlsPropertiesAndStillBuildsTheSender() {
		SmtpProperties smtp = new SmtpProperties("smtp.example.com", 587, null, "true", null, null, true,
				Duration.ofSeconds(5), Duration.ofSeconds(5), Duration.ofSeconds(5), 3, null);

		Properties props = ((JavaMailSenderImpl) configuration.javaMailSender(smtp)).getJavaMailProperties();

		assertNull(props.get("mail.smtp.starttls.enable"));
		assertNull(props.get("mail.smtp.ssl.enable"));
	}

	@Test
	void canonicalKeyWins_whenBothKeysAreSetToDifferentValues() {
		SmtpProperties smtp = new SmtpProperties("smtp.example.com", 587, SmtpSecurity.STARTTLS, "off",
				null, null, true, Duration.ofSeconds(5), Duration.ofSeconds(5), Duration.ofSeconds(5), 3, null);

		Properties props = ((JavaMailSenderImpl) configuration.javaMailSender(smtp)).getJavaMailProperties();

		assertEquals("true", props.get("mail.smtp.starttls.enable"));
	}

	@Test
	void credentials_forceAuthenticationAndAreSetOnTheSender() {
		// JavaMailSenderImpl never writes mail.smtp.auth itself. It does not force authentication —
		// Jakarta Mail attempts AUTH whenever credentials exist and the server advertises it — but it
		// makes the connect fail fast on a missing credential, as EntryStore 6.0's Session did.
		JavaMailSenderImpl sender = senderFor(SmtpSecurity.OFF, "user", "secret");

		assertEquals("true", sender.getJavaMailProperties().get("mail.smtp.auth"));
		assertEquals("user", sender.getUsername());
		assertEquals("secret", sender.getPassword());
	}

	@Test
	void withoutCredentials_authenticationIsNotForced() {
		assertNull(javaMailPropertiesFor(SmtpSecurity.OFF, null, null).get("mail.smtp.auth"));
	}

	@Test
	void timeoutsAreWrittenAsMilliseconds() {
		SmtpProperties smtp = new SmtpProperties("smtp.example.com", 587, SmtpSecurity.OFF, null, null, null, true,
				Duration.ofSeconds(3), Duration.ofSeconds(7), Duration.ofMillis(1500), 3, null);

		Properties props = ((JavaMailSenderImpl) configuration.javaMailSender(smtp)).getJavaMailProperties();

		assertEquals("3000", props.get("mail.smtp.connectiontimeout"));
		assertEquals("7000", props.get("mail.smtp.timeout"));
		assertEquals("1500", props.get("mail.smtp.writetimeout"));
	}

	@Test
	void serverIdentityCheckIsWrittenExplicitly() {
		// The Jakarta Mail default for this has varied across versions; the pre-migration code always
		// set it, so leaving it to the implementation would be a silent security regression.
		assertEquals("true", javaMailPropertiesFor(SmtpSecurity.OFF, null, null)
				.get("mail.smtp.ssl.checkserveridentity"));

		SmtpProperties relaxed = new SmtpProperties("smtp.example.com", 25, SmtpSecurity.OFF, null, null, null, false,
				Duration.ofSeconds(5), Duration.ofSeconds(5), Duration.ofSeconds(5), 3, null);
		assertEquals("false", ((JavaMailSenderImpl) configuration.javaMailSender(relaxed))
				.getJavaMailProperties().get("mail.smtp.ssl.checkserveridentity"));
	}

	@Test
	void hostAndPortAreSetOnTheSenderNotInTheProperties() {
		JavaMailSenderImpl sender = senderFor(SmtpSecurity.OFF, null, null);

		assertEquals("smtp.example.com", sender.getHost());
		assertEquals(465, sender.getPort());
		// setHost is the only place the host lives, and JavaMailSenderImpl passes it to Transport.connect
		// unconditionally. Asserting mail.smtp.host is absent keeps a second, drift-prone copy out of the
		// Jakarta Mail properties. (EmailSender's unconfigured-host guard reads SmtpProperties and is
		// independent of this.)
		assertNull(sender.getJavaMailProperties().get("mail.smtp.host"));
	}

	@Test
	void unconfiguredHost_leavesTheSenderHostUnsetSoNoAccidentalLocalhostDelivery() {
		SmtpProperties smtp = new SmtpProperties(null, 25, SmtpSecurity.OFF, null, null, null, true,
				Duration.ofSeconds(5), Duration.ofSeconds(5), Duration.ofSeconds(5), 3, null);

		assertNull(((JavaMailSenderImpl) configuration.javaMailSender(smtp)).getHost());
	}

	@Test
	void protocolAndEncodingMatchThePreMigrationSession() {
		JavaMailSenderImpl sender = senderFor(SmtpSecurity.OFF, null, null);

		assertEquals("smtp", sender.getProtocol());
		assertEquals("UTF-8", sender.getDefaultEncoding());
	}

	private Properties javaMailPropertiesFor(SmtpSecurity security, String username, String password) {
		return senderFor(security, username, password).getJavaMailProperties();
	}

	private JavaMailSenderImpl senderFor(SmtpSecurity security, String username, String password) {
		SmtpProperties smtp = new SmtpProperties("smtp.example.com", 465, security, null, username, password, true,
				Duration.ofSeconds(5), Duration.ofSeconds(5), Duration.ofSeconds(5), 3, null);
		return (JavaMailSenderImpl) configuration.javaMailSender(smtp);
	}

	@Test
	void assertSslSocketFactoryPortTracksTheConfiguredPort() {
		SmtpProperties smtp = new SmtpProperties("smtp.example.com", 2525, SmtpSecurity.SSL, null, null, null, true,
				Duration.ofSeconds(5), Duration.ofSeconds(5), Duration.ofSeconds(5), 3, null);

		Properties props = ((JavaMailSenderImpl) configuration.javaMailSender(smtp)).getJavaMailProperties();

		assertTrue(props.get("mail.smtp.socketFactory.port").equals("2525"),
				"the socket factory must dial the configured port, not a hardcoded 465");
	}
}
