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
import org.entrystore.rest.springboot.util.CapturingAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.Properties;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MailConfigurationTest {

	private final MailConfiguration configuration = new MailConfiguration();

	private CapturingAppender logAppender;

	@BeforeEach
	void captureLogging() {
		logAppender = CapturingAppender.attachTo(MailConfiguration.class);
	}

	@AfterEach
	void releaseLogging() {
		logAppender.close();
	}

	@Test
	void startTls_enablesAndRequiresTheUpgrade() {
		Properties props = javaMailPropertiesFor("starttls", null, null);

		assertEquals("true", props.get("mail.smtp.starttls.enable"));
		// "required" matters: without it a server that does not offer STARTTLS silently falls back to
		// plaintext, which is the failure mode this whole key exists to prevent.
		assertEquals("true", props.get("mail.smtp.starttls.required"));
		assertNull(props.get("mail.smtp.ssl.enable"));
	}

	@Test
	void implicitSsl_enablesTheSocketFactoryWithoutFallback() {
		Properties props = javaMailPropertiesFor("ssl", null, null);

		assertEquals("true", props.get("mail.smtp.ssl.enable"));
		assertEquals("465", props.get("mail.smtp.socketFactory.port"));
		assertEquals("javax.net.ssl.SSLSocketFactory", props.get("mail.smtp.socketFactory.class"));
		assertEquals("false", props.get("mail.smtp.socketFactory.fallback"),
				"fallback would let a failed TLS handshake downgrade to plaintext");
		assertNull(props.get("mail.smtp.starttls.enable"));
	}

	@Test
	void securityOff_writesNoTlsProperties() {
		Properties props = javaMailPropertiesFor("off", null, null);

		assertFalse(props.keySet().stream().anyMatch(key -> key.toString().contains("starttls")));
		assertNull(props.get("mail.smtp.ssl.enable"));
	}

	@ParameterizedTest(name = "security={0}, ssl={1} -> {2} on the wire")
	@CsvSource(nullValues = "NULL", value = {
			"NULL,     starttls, mail.smtp.starttls.enable",
			"NULL,     ssl,      mail.smtp.ssl.enable",
			"NULL,     STARTTLS, mail.smtp.starttls.enable",
			// The canonical key wins over a disagreeing alias, and an unrecognised canonical value falls
			// through to a usable alias rather than silently meaning plaintext.
			"starttls, off,      mail.smtp.starttls.enable",
			"ssl,      starttls, mail.smtp.ssl.enable",
			"tls,      starttls, mail.smtp.starttls.enable"
	})
	void configuredTransportSecurity_reachesTheJavaMailProperties(String security, String ssl, String expectedProperty) {
		// The whole point of the ticket: a deployment configured only with the deprecated key must get
		// real transport security. Asserting effectiveSecurity() in SmtpPropertiesTest is not enough —
		// nothing there proves MailConfiguration reads it rather than a raw component.
		SmtpProperties smtp = new SmtpProperties("smtp.example.com", 587, security, ssl, null, null, true,
				Duration.ofSeconds(5), Duration.ofSeconds(5), Duration.ofSeconds(5), 3, null);

		Properties props = ((JavaMailSenderImpl) configuration.javaMailSender(smtp)).getJavaMailProperties();

		assertEquals("true", props.get(expectedProperty));
	}

	@ParameterizedTest(name = "unrecognised {0}={1} -> no TLS properties, sender still built")
	@CsvSource(nullValues = "NULL", value = {
			"ssl,      true",
			"ssl,      tls",
			"security, tls",
			"security, none"
	})
	void unrecognisedTransportSecurityValue_producesNoTlsPropertiesAndStillBuildsTheSender(
			String key, String value) {
		String security = "security".equals(key) ? value : null;
		String ssl = "ssl".equals(key) ? value : null;
		SmtpProperties smtp = new SmtpProperties("smtp.example.com", 587, security, ssl, null, null, true,
				Duration.ofSeconds(5), Duration.ofSeconds(5), Duration.ofSeconds(5), 3, null);

		Properties props = ((JavaMailSenderImpl) configuration.javaMailSender(smtp)).getJavaMailProperties();

		assertNull(props.get("mail.smtp.starttls.enable"));
		assertNull(props.get("mail.smtp.ssl.enable"));
	}

	@Test
	void plaintextWithAConfiguredHost_isAlwaysWarnedAbout() {
		// Previously conditional on credentials being set, which left a deployment relaying password-reset
		// links over plaintext with nothing above an INFO line. This warning is also what replaces the
		// strict enum: an unrecognised security value now resolves to OFF, so it has to be visible.
		configuration.javaMailSender(senderProperties("off", null, null));

		assertTrue(warnings().anyMatch(message -> message.contains("transport security is off")),
				"a configured host on plaintext must warn even without credentials; got: " + logAppender);
	}

	@Test
	void plaintextWithCredentials_warnsThatTheCredentialsAreExposedToo() {
		configuration.javaMailSender(senderProperties("off", "user", "secret"));

		assertTrue(warnings().anyMatch(message -> message.contains("SMTP credentials")),
				"got: " + logAppender);
	}

	@Test
	void transportSecurityEnabled_producesNoPlaintextWarning() {
		configuration.javaMailSender(senderProperties("starttls", null, null));

		assertFalse(warnings().anyMatch(message -> message.contains("transport security is off")));
	}

	@Test
	void unconfiguredHost_warnsThatNoEmailWillBeSent() {
		SmtpProperties smtp = new SmtpProperties(null, 25, "off", null, null, null, true,
				Duration.ofSeconds(5), Duration.ofSeconds(5), Duration.ofSeconds(5), 3, null);

		configuration.javaMailSender(smtp);

		assertTrue(warnings().anyMatch(message -> message.contains("SMTP is not configured")));
		// Without a host there is nothing to relay over plaintext, so the plaintext warning must not fire.
		assertFalse(warnings().anyMatch(message -> message.contains("transport security is off")));
	}

	@Test
	void credentials_setMailSmtpAuthAndLandOnTheSender() {
		// JavaMailSenderImpl never writes mail.smtp.auth itself. It does not force authentication —
		// Jakarta Mail attempts AUTH whenever credentials exist and the server advertises it — but it
		// makes the connect fail fast on a missing credential, as EntryStore 6.0's Session did.
		JavaMailSenderImpl sender = senderFor("off", "user", "secret");

		assertEquals("true", sender.getJavaMailProperties().get("mail.smtp.auth"));
		assertEquals("user", sender.getUsername());
		assertEquals("secret", sender.getPassword());
	}

	@Test
	void withoutCredentials_mailSmtpAuthIsNotWritten() {
		assertNull(javaMailPropertiesFor("off", null, null).get("mail.smtp.auth"));
	}

	@Test
	void halfSetCredentials_leaveTheSenderAnonymous() {
		// SmtpProperties degrades the pair rather than aborting startup, so nothing must reach the sender.
		JavaMailSenderImpl sender = senderFor("off", "relay-user", null);

		assertNull(sender.getJavaMailProperties().get("mail.smtp.auth"));
		assertNull(sender.getUsername());
		assertNull(sender.getPassword());
	}

	@Test
	void timeoutsAreWrittenAsMilliseconds() {
		SmtpProperties smtp = new SmtpProperties("smtp.example.com", 587, "off", null, null, null, true,
				Duration.ofSeconds(3), Duration.ofSeconds(7), Duration.ofMillis(1500), 3, null);

		Properties props = ((JavaMailSenderImpl) configuration.javaMailSender(smtp)).getJavaMailProperties();

		assertEquals("3000", props.get("mail.smtp.connectiontimeout"));
		assertEquals("7000", props.get("mail.smtp.timeout"));
		assertEquals("1500", props.get("mail.smtp.writetimeout"));
	}

	@Test
	void serverIdentityCheckIsWrittenExplicitly() {
		// The Jakarta Mail default for this has varied across versions and EntryStore 6.0 always set it,
		// so leaving it to the implementation would be a silent security regression.
		assertEquals("true", javaMailPropertiesFor("off", null, null)
				.get("mail.smtp.ssl.checkserveridentity"));

		SmtpProperties relaxed = new SmtpProperties("smtp.example.com", 25, "off", null, null, null, false,
				Duration.ofSeconds(5), Duration.ofSeconds(5), Duration.ofSeconds(5), 3, null);
		assertEquals("false", ((JavaMailSenderImpl) configuration.javaMailSender(relaxed))
				.getJavaMailProperties().get("mail.smtp.ssl.checkserveridentity"));
	}

	@Test
	void hostAndPortAreSetOnTheSenderNotInTheProperties() {
		JavaMailSenderImpl sender = senderFor("off", null, null);

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
		SmtpProperties smtp = new SmtpProperties(null, 25, "off", null, null, null, true,
				Duration.ofSeconds(5), Duration.ofSeconds(5), Duration.ofSeconds(5), 3, null);

		assertNull(((JavaMailSenderImpl) configuration.javaMailSender(smtp)).getHost());
	}

	@Test
	void protocolAndEncodingMatchTheSessionEntryStore60Built() {
		JavaMailSenderImpl sender = senderFor("off", null, null);

		assertEquals("smtp", sender.getProtocol());
		assertEquals("UTF-8", sender.getDefaultEncoding());
	}

	@Test
	void sslSocketFactoryPortTracksTheConfiguredPort() {
		SmtpProperties smtp = new SmtpProperties("smtp.example.com", 2525, "ssl", null, null, null, true,
				Duration.ofSeconds(5), Duration.ofSeconds(5), Duration.ofSeconds(5), 3, null);

		Properties props = ((JavaMailSenderImpl) configuration.javaMailSender(smtp)).getJavaMailProperties();

		assertEquals("2525", props.get("mail.smtp.socketFactory.port"),
				"the socket factory must dial the configured port, not a hardcoded 465");
	}

	@Test
	void beanIsDeclaredAsTheJavaMailSenderInterface() throws NoSuchMethodException {
		// Load-bearing and otherwise enforced by nothing: Boot's MailSenderValidatorAutoConfiguration is
		// @ConditionalOnSingleCandidate(JavaMailSenderImpl.class), so narrowing this return type would
		// activate spring.mail.test-connection and open an SMTP connection during startup. Every other
		// test here casts the result, so all of them would stay green through that change.
		Method factoryMethod = MailConfiguration.class.getDeclaredMethod("javaMailSender", SmtpProperties.class);

		assertEquals(JavaMailSender.class, factoryMethod.getReturnType(),
				"the @Bean method must stay declared as the JavaMailSender interface");
	}

	private Stream<String> warnings() {
		return logAppender.messagesAt(Level.WARN);
	}

	private Properties javaMailPropertiesFor(String security, String username, String password) {
		return senderFor(security, username, password).getJavaMailProperties();
	}

	private JavaMailSenderImpl senderFor(String security, String username, String password) {
		return (JavaMailSenderImpl) configuration.javaMailSender(senderProperties(security, username, password));
	}

	private static SmtpProperties senderProperties(String security, String username, String password) {
		return new SmtpProperties("smtp.example.com", 465, security, null, username, password, true,
				Duration.ofSeconds(5), Duration.ofSeconds(5), Duration.ofSeconds(5), 3, null);
	}
}
