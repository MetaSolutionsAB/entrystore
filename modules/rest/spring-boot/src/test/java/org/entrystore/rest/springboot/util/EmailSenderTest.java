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

package org.entrystore.rest.springboot.util;

import jakarta.mail.Message;
import jakarta.mail.internet.MimeMessage;
import org.entrystore.Entry;
import org.entrystore.User;
import org.entrystore.config.Config;
import org.entrystore.repository.config.PropertiesConfiguration;
import org.entrystore.repository.config.Settings;
import org.entrystore.rest.springboot.configuration.DeprecatedEmailAddressProperties;
import org.entrystore.rest.springboot.configuration.SmtpProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class EmailSenderTest {

	private static final String FROM = "noreply@example.com";

	private JavaMailSender mailSender;

	@BeforeEach
	void setUp() {
		mailSender = mock(JavaMailSender.class);
		when(mailSender.createMimeMessage()).thenAnswer(invocation -> new JavaMailSenderImpl().createMimeMessage());
	}

	@Test
	void sendMessage_unsetHost_returnsFalseWithoutTouchingTheSender() {
		EmailSender sender = senderWith(smtp(null, FROM, null, null), new PropertiesConfiguration("test"));

		assertFalse(assertDoesNotThrow(() -> sender.sendMessage("user@example.com", "subj", "body")),
				"sendMessage must return false when SMTP host is unset");
		// The guard has to fire before the sender is used: JavaMailSenderImpl with no host falls back to
		// the Jakarta Mail default and would quietly try localhost:25.
		verifyNoInteractions(mailSender);
	}

	@Test
	void sendMessage_blankHost_returnsFalseWithoutTouchingTheSender() {
		EmailSender sender = senderWith(smtp("   ", FROM, null, null), new PropertiesConfiguration("test"));

		assertFalse(assertDoesNotThrow(() -> sender.sendMessage("user@example.com", "subj", "body")));
		verifyNoInteractions(mailSender);
	}

	@Test
	void sendMessage_missingFromAddress_returnsFalseWithoutTouchingTheSender() {
		EmailSender sender = senderWith(smtp("smtp.example.com", null, null, null), new PropertiesConfiguration("test"));

		assertFalse(assertDoesNotThrow(() -> sender.sendMessage("user@example.com", "subj", "body")));
		verifyNoInteractions(mailSender);
	}

	@Test
	void sendSignupConfirmation_populatesTheMessageAndEscapesUserSuppliedValues(@TempDir Path tmp) throws Exception {
		Config config = configWithTemplate(tmp, EmailTemplate.SIGNUP,
				"<p>__NAME__ (__EMAIL__)</p><a href=\"__CONFIRMATION_LINK__\">confirm</a>");
		config.setProperty(EmailTemplate.SIGNUP.getSubjectKey(), "Confirm your sign-up");
		EmailSender sender = senderWith(smtp("smtp.example.com", FROM, "audit@example.com", "help@example.com"), config);

		boolean sent = sender.sendSignupConfirmation("Ada <b>Lovelace</b>", "ada@example.com",
				"https://entrystore.example/confirm?token=a$b");

		assertTrue(sent);
		MimeMessage message = captureSentMessage();
		assertEquals(FROM, message.getFrom()[0].toString());
		assertEquals("ada@example.com", message.getRecipients(Message.RecipientType.TO)[0].toString());
		assertEquals("audit@example.com", message.getRecipients(Message.RecipientType.BCC)[0].toString());
		assertEquals("help@example.com", message.getReplyTo()[0].toString());
		assertEquals("Confirm your sign-up", message.getSubject());
		// setText fills the DataHandler but only writes the Content-Type header during saveChanges(),
		// which is what Transport.send does on the way out — so do the same before asserting on it.
		message.saveChanges();
		assertTrue(message.getContentType().contains("text/html"), "body must be sent as HTML");
		assertTrue(message.getContentType().contains("UTF-8"), "body must be sent as UTF-8");

		String body = message.getContent().toString();
		assertTrue(body.contains("Ada &lt;b&gt;Lovelace&lt;/b&gt;"), "the display name must be HTML-escaped");
		assertFalse(body.contains("<b>Lovelace</b>"), "unescaped user markup must not reach the body");
		// The link is ours, not user input: it must survive verbatim, including the '$' that the previous
		// regex-based substitution would have consumed as a group reference.
		assertTrue(body.contains("https://entrystore.example/confirm?token=a$b"),
				"the confirmation link must be inserted literally");
	}

	@Test
	void sendMessage_transientFailures_areRetriedUpToTheConfiguredLimit(@TempDir Path tmp) throws IOException {
		EmailSender sender = senderWith(smtp("smtp.example.com", FROM, null, null), configWithTemplate(tmp));
		doThrow(new MailSendException("first"))
				.doThrow(new MailSendException("second"))
				.doNothing()
				.when(mailSender).send(any(MimeMessage.class));

		assertTrue(sender.sendMessage("user@example.com", "subj", "body"));
		verify(mailSender, times(3)).send(any(MimeMessage.class));
	}

	@Test
	void sendMessage_persistentFailure_returnsFalseAfterExactlyThreeAttempts(@TempDir Path tmp) throws IOException {
		// The attempt count is load-bearing: PasswordResetResourceIT relies on three ECONNREFUSED
		// attempts completing in milliseconds rather than blocking the request thread.
		EmailSender sender = senderWith(smtp("smtp.example.com", FROM, null, null), configWithTemplate(tmp));
		doThrow(new MailSendException("down")).when(mailSender).send(any(MimeMessage.class));

		// MailException is unchecked, so a missed catch would escape here rather than fail to compile.
		assertFalse(assertDoesNotThrow(() -> sender.sendMessage("user@example.com", "subj", "body")));
		verify(mailSender, times(3)).send(any(MimeMessage.class));
	}

	@Test
	void sendMessage_explicitFromAndReplyTo_overrideTheConfiguredDefaults(@TempDir Path tmp) throws Exception {
		EmailSender sender = senderWith(smtp("smtp.example.com", FROM, null, "help@example.com"), configWithTemplate(tmp));
		doNothing().when(mailSender).send(any(MimeMessage.class));

		assertTrue(sender.sendMessage("user@example.com", "subj", "body",
				"sender@example.com", "reply@example.com"));

		MimeMessage message = captureSentMessage();
		assertEquals("sender@example.com", message.getFrom()[0].toString());
		assertEquals("reply@example.com", message.getReplyTo()[0].toString());
	}

	@Test
	void deprecatedFromKey_isUsedWhenTheCanonicalOneIsUnset(@TempDir Path tmp) throws Exception {
		EmailSender sender = new EmailSender(mailSender,
				smtp("smtp.example.com", null, null, null),
				new DeprecatedEmailAddressProperties("legacy@example.com", null),
				new MailTemplateRenderer(configWithTemplate(tmp)));

		assertTrue(sender.sendMessage("user@example.com", "subj", "body"));
		assertEquals("legacy@example.com", captureSentMessage().getFrom()[0].toString());
	}

	@Test
	void sendMessage_subjectWithCrLf_cannotInjectAnExtraHeader(@TempDir Path tmp) throws Exception {
		// POST /message subjects are user-supplied, and HtmlSanitizer.sanitizeToPlainText strips markup
		// but then HTML-unescapes, so &#13;&#10; arrives here as a real CRLF.
		EmailSender sender = senderWith(smtp("smtp.example.com", FROM, null, null), configWithTemplate(tmp));

		assertTrue(sender.sendMessage("user@example.com",
				"Hi\r\nReply-To: attacker@evil.tld\r\nContent-Type: text/html", "body"));

		MimeMessage message = captureSentMessage();
		message.saveChanges();
		assertEquals(1, message.getHeader("Subject").length, "the subject must remain a single header");
		assertFalse(message.getSubject().contains("\r"), "CR must not survive into the header");
		assertFalse(message.getSubject().contains("\n"), "LF must not survive into the header");
		// Reply-To was never set on this message, so an injected one would show up here.
		assertNull(message.getHeader("Reply-To"), "no header may be injected via the subject");
	}

	@Test
	void sendMessage_preEncodedUtf8Subject_isFoldedNotReEncoded(@TempDir Path tmp) throws Exception {
		String encoded = "=?utf-8?B?" + Base64.getEncoder()
				.encodeToString("Ärende".getBytes(StandardCharsets.UTF_8)) + "?=";
		EmailSender sender = senderWith(smtp("smtp.example.com", FROM, null, null), configWithTemplate(tmp));

		assertTrue(sender.sendMessage("user@example.com", encoded, "body"));

		MimeMessage message = captureSentMessage();
		message.saveChanges();
		// Folded, not wrapped in a second encoded word: decoding once must give the original text.
		assertEquals("Ärende", message.getSubject());
	}

	@Test
	void sendMessage_maxSendAttemptsOne_doesNotRetry(@TempDir Path tmp) throws IOException {
		SmtpProperties once = new SmtpProperties("smtp.example.com", 25, null, null, null, null, true,
				Duration.ofSeconds(5), Duration.ofSeconds(5), Duration.ofSeconds(5), 1,
				new SmtpProperties.Addresses(FROM, null, null));
		EmailSender sender = senderWith(once, configWithTemplate(tmp));
		doThrow(new MailSendException("down")).when(mailSender).send(any(MimeMessage.class));

		assertFalse(sender.sendMessage("user@example.com", "subj", "body"));
		verify(mailSender, times(1)).send(any(MimeMessage.class));
	}

	@Test
	void sendPasswordResetConfirmation_usesThePasswordResetTemplateAndSubject(@TempDir Path tmp) throws Exception {
		// Without this, swapping the enum constant would send the sign-up body for a password reset and
		// nothing would fail — the ITs only extract a token from the body.
		Config config = configWithTemplate(tmp, EmailTemplate.PASSWORD_RESET, "reset __EMAIL__ __CONFIRMATION_LINK__");
		config.setProperty(EmailTemplate.PASSWORD_RESET.getSubjectKey(), "Reset your password");
		EmailSender sender = senderWith(smtp("smtp.example.com", FROM, null, null), config);

		assertTrue(sender.sendPasswordResetConfirmation("ada@example.com", "https://entrystore.example/c?t=1"));

		MimeMessage message = captureSentMessage();
		assertEquals("Reset your password", message.getSubject());
		assertEquals("reset ada@example.com https://entrystore.example/c?t=1", message.getContent().toString());
	}

	@Test
	void sendSignupConfirmation_unreadableTemplate_returnsFalseWithoutSending(@TempDir Path tmp) {
		Config config = new PropertiesConfiguration("test");
		config.setProperty(EmailTemplate.SIGNUP.getTemplatePathKey(), tmp.resolve("missing.html").toString());
		EmailSender sender = senderWith(smtp("smtp.example.com", FROM, null, null), config);

		assertFalse(sender.sendSignupConfirmation("Ada", "ada@example.com", "https://entrystore.example/c"));
		verifyNoInteractions(mailSender);
	}

	@Test
	void deprecatedBccKey_isUsedWhenTheCanonicalOneIsUnset(@TempDir Path tmp) throws Exception {
		EmailSender sender = new EmailSender(mailSender,
				smtp("smtp.example.com", FROM, null, null),
				new DeprecatedEmailAddressProperties(null, "legacy-bcc@example.com"),
				new MailTemplateRenderer(configWithTemplate(tmp)));

		assertTrue(sender.sendMessage("user@example.com", "subj", "body"));

		MimeMessage message = captureSentMessage();
		assertEquals("legacy-bcc@example.com", message.getRecipients(Message.RecipientType.BCC)[0].toString());
		assertEquals(FROM, message.getFrom()[0].toString(), "the from-address must not be affected");
	}

	@Test
	void sendPasswordChangeConfirmation_doesNotThrow_whenBaseUrlIsMissing() {
		// Regression for ENTRYSTORE-1028: the password change is already committed by the time the
		// best-effort confirmation email is sent, so a missing/blank base URL (which used to NPE while
		// substituting __DOMAIN__) must never make the PUT return 500.
		assertPasswordChangeTemplateOnClasspath();
		Config config = new PropertiesConfiguration("test");
		// BASE_URL deliberately unset.
		EmailSender sender = senderWith(smtp("smtp.example.com", FROM, null, null), config);

		assertDoesNotThrow(() -> sender.sendPasswordChangeConfirmation(userEntryNamed("changed@example.com")));
	}

	@Test
	void sendPasswordChangeConfirmation_swallowsRuntimeExceptionWhileBuildingEmail() {
		// The best-effort confirmation email must never fail the (already-committed) password change,
		// even when building it throws an unexpected RuntimeException.
		assertPasswordChangeTemplateOnClasspath();
		Config config = mock(Config.class);
		when(config.getString(eq(EmailTemplate.PASSWORD_CHANGE.getSubjectKey()), anyString()))
				.thenThrow(new RuntimeException("boom while building confirmation email"));
		EmailSender sender = senderWith(smtp("smtp.example.com", FROM, null, null), config);

		assertDoesNotThrow(() -> sender.sendPasswordChangeConfirmation(userEntryNamed("changed@example.com")));
	}

	@Test
	void sendPasswordChangeConfirmation_invalidRecipient_doesNotSend() {
		assertPasswordChangeTemplateOnClasspath();
		EmailSender sender = senderWith(smtp("smtp.example.com", FROM, null, null), new PropertiesConfiguration("test"));

		Entry entry = mock(Entry.class);
		User user = mock(User.class);
		when(user.getName()).thenReturn("no-at-sign");
		when(entry.getResource()).thenReturn(user);

		assertDoesNotThrow(() -> sender.sendPasswordChangeConfirmation(entry));
		verifyNoInteractions(mailSender);
	}

	private MimeMessage captureSentMessage() {
		ArgumentCaptor<MimeMessage> captor = ArgumentCaptor.forClass(MimeMessage.class);
		verify(mailSender).send(captor.capture());
		return captor.getValue();
	}

	private EmailSender senderWith(SmtpProperties smtp, Config config) {
		return new EmailSender(mailSender, smtp,
				new DeprecatedEmailAddressProperties(null, null),
				new MailTemplateRenderer(config));
	}

	private static SmtpProperties smtp(String host, String from, String bcc, String replyTo) {
		return new SmtpProperties(host, 25, null, null, null, null, true,
				Duration.ofSeconds(5), Duration.ofSeconds(5), Duration.ofSeconds(5), 3,
				new SmtpProperties.Addresses(from, bcc, replyTo));
	}

	private static Config configWithTemplate(Path tmp) throws IOException {
		return configWithTemplate(tmp, EmailTemplate.SIGNUP, "body");
	}

	private static Config configWithTemplate(Path tmp, EmailTemplate template, String body) throws IOException {
		Path file = tmp.resolve(template.getClasspathResource());
		Files.writeString(file, body);
		Config config = new PropertiesConfiguration("test");
		config.setProperty(template.getTemplatePathKey(), file.toString());
		return config;
	}

	private static Entry userEntryNamed(String name) {
		User user = mock(User.class);
		when(user.getName()).thenReturn(name);
		Entry entry = mock(Entry.class);
		when(entry.getResource()).thenReturn(user);
		return entry;
	}

	private static void assertPasswordChangeTemplateOnClasspath() {
		assertNotNull(Thread.currentThread().getContextClassLoader().getResourceAsStream("email_pwchange.html"),
				"email_pwchange.html must be on the test classpath, otherwise these tests are vacuous");
	}
}
