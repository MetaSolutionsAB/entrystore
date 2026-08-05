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

import jakarta.mail.Address;
import jakarta.mail.AuthenticationFailedException;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.SendFailedException;
import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.apache.logging.log4j.Level;
import org.entrystore.Entry;
import org.entrystore.User;
import org.entrystore.config.Config;
import org.entrystore.repository.config.PropertiesConfiguration;
import org.entrystore.repository.config.Settings;
import org.entrystore.rest.springboot.configuration.DeprecatedEmailAddressProperties;
import org.entrystore.rest.springboot.configuration.SmtpProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class EmailSenderTest {

	private static final String FROM = "noreply@example.com";

	private JavaMailSender mailSender;

	private CapturingAppender logAppender;

	@BeforeEach
	void setUp() {
		mailSender = mock(JavaMailSender.class);
		when(mailSender.createMimeMessage()).thenAnswer(invocation -> new JavaMailSenderImpl().createMimeMessage());

		logAppender = CapturingAppender.attachTo(EmailSender.class);
	}

	@AfterEach
	void releaseLogging() {
		logAppender.close();
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
		// The attempt count is load-bearing: PasswordResetResourceIT relies on three ECONNREFUSED attempts
		// completing in milliseconds. That path runs on AuthService's passwordResetExecutor, not the
		// request thread — see the "Do not add further synchronous work to the active branch" note there,
		// which exists to keep the response free of a timing-based account-enumeration side channel.
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

	@Test
	void internetAddressSingleArgConstructor_rejectsACommaSeparatedList() throws Exception {
		// The assumption the two tests below rest on: parse() splits a comma list and returns every
		// address, whereas the single-argument constructor throws unless the value is exactly one address.
		assertEquals(2, InternetAddress.parse("me@example.com,victim@target.tld").length);
		assertThrows(AddressException.class, () -> new InternetAddress("me@example.com,victim@target.tld"));
		assertEquals("me@example.com", new InternetAddress("me@example.com").getAddress());
	}

	@Test
	void sendMessage_commaSeparatedRecipient_isRejectedWithoutSending(@TempDir Path tmp) throws IOException {
		// An authenticated caller can rename its own principal to a comma list — setPrincipalName strips
		// only \p{Cf} and applies no email-format validation — and POST /message's recipient carries only
		// @NotBlank. Splitting it would relay attacker-authored HTML to a third party under the
		// deployment's own SPF/DKIM-aligned From address.
		EmailSender sender = senderWith(smtp("smtp.example.com", FROM, null, null), configWithTemplate(tmp));

		assertFalse(sender.sendMessage("me@example.com,victim@target.tld", "subj", "body"));
		verify(mailSender, never()).send(any(MimeMessage.class));
	}

	@Test
	void sendMessage_commaSeparatedReplyTo_isRejectedWithoutSending(@TempDir Path tmp) throws IOException {
		// Same shape: on the POST /message path Reply-To is the caller's own principal name.
		EmailSender sender = senderWith(smtp("smtp.example.com", FROM, null, null), configWithTemplate(tmp));

		assertFalse(sender.sendMessage("user@example.com", "subj", "body",
				null, "me@example.com,victim@target.tld"));
		verify(mailSender, never()).send(any(MimeMessage.class));
	}

	@ParameterizedTest(name = "recipient ''{0}'' is rejected before the transport is touched")
	@ValueSource(strings = {
			// A comma list: closed already, kept here so the whole rule is pinned in one place.
			"me@example.com,victim@target.tld",
			// RFC822 group syntax. new InternetAddress(String) enforces "exactly one address", and
			// jakarta.mail models a group as one address with isGroup() — SMTPTransport.sendMessage then
			// calls expandGroups() unconditionally and every member becomes an envelope RCPT TO.
			"g:a@evil.tld,b@evil.tld;",
			// CR/LF in the display-name phrase: checkAddress validates only the addr-spec.
			"\"x\r\nbcc: attacker@evil.tld\" <victim@example.com>"
	})
	void sendMessage_recipientThatIsNotASingleMailbox_isRejectedWithoutSending(String recipient,
																			  @TempDir Path tmp)
			throws IOException {
		// All three are reachable through the same self-rename primitive: ContextImpl grants every user
		// WriteResource on their own principal entry, and PrincipalManagerImpl.setPrincipalName strips only
		// \p{Cf}, trims and lowercases, so none of these are altered on the way in.
		EmailSender sender = senderWith(smtp("smtp.example.com", FROM, null, null), configWithTemplate(tmp));

		assertFalse(sender.sendMessage(recipient, "subj", "body"));

		verify(mailSender, never()).send(any(MimeMessage.class));
	}

	@Test
	void sendMessage_groupRecipient_wouldOtherwiseHaveExpandedToEveryMember() throws Exception {
		// The reason the case above matters, asserted against jakarta.mail rather than described in a
		// comment: the value passes a bare single-address constructor and then expands.
		InternetAddress group = new InternetAddress("g:a@evil.tld,b@evil.tld;");

		assertTrue(group.isGroup());
		assertEquals(2, group.getGroup(false).length,
				"one accepted address, two envelope recipients — which is the relay");
	}

	@Test
	void sendMessage_callerSuppliedReplyToThatIsAList_isRejected(@TempDir Path tmp) throws IOException {
		// The Reply-To override on the POST /message path is the caller's own principal name.
		EmailSender sender = senderWith(smtp("smtp.example.com", FROM, null, null), configWithTemplate(tmp));

		assertFalse(sender.sendMessage("user@example.com", "subj", "body", null,
				"me@example.com,victim@target.tld"));

		verify(mailSender, never()).send(any(MimeMessage.class));
	}

	@Test
	void sendMessage_configuredMultiAddressReplyTo_isStillAccepted(@TempDir Path tmp) throws Exception {
		// entrystore.smtp.email.reply-to has accepted a comma-separated list since 376abd6a (2019), which
		// read it with InternetAddress.parse, and a list is legal for Reply-To under RFC 5322. Constraining
		// the resolved value rather than the caller-supplied one broke every send for such a deployment:
		// sendSignupConfirmation and both confirmation paths reach here with msgReplyTo == null.
		EmailSender sender = senderWith(
				smtp("smtp.example.com", FROM, null, "support@example.com,tickets@example.com"),
				configWithTemplate(tmp));

		assertTrue(sender.sendMessage("user@example.com", "subj", "body"));

		Address[] replyTo = captureSentMessage().getReplyTo();
		assertEquals(2, replyTo.length, "both configured Reply-To addresses must survive");
		assertEquals("support@example.com", ((InternetAddress) replyTo[0]).getAddress());
		assertEquals("tickets@example.com", ((InternetAddress) replyTo[1]).getAddress());
	}

	@Test
	void construction_multiAddressBccAndReplyTo_leaveStartupQuiet(@TempDir Path tmp) throws IOException {
		// The list-shaped keys must not be reported as malformed at startup, which is what pins the shape
		// constants apart. Reply-To had no startup coverage at all.
		senderWith(smtp("smtp.example.com", FROM, "audit@example.com,archive@example.com",
				"support@example.com,tickets@example.com"), configWithTemplate(tmp));

		assertEquals(0, logAppender.countAt(Level.ERROR),
				"a legitimate address list must not be reported as unparseable; got: " + logAppender);
	}

	@Test
	void construction_malformedSingleAddressKey_isReportedNamingTheKey(@TempDir Path tmp) throws IOException {
		senderWith(smtp("smtp.example.com", "not an address", null, null), configWithTemplate(tmp));

		assertTrue(logAppender.messagesAt(Level.ERROR)
						.anyMatch(message -> message.contains("entrystore.smtp.email.from")),
				"the ERROR must name the key the value came from; got: " + logAppender);
	}

	@Test
	void sendMessage_unresolvedTransportSecurity_refusesToSendInTheClear(@TempDir Path tmp)
			throws IOException {
		// Binding stays lenient so mail configuration cannot abort startup, which means security=tls lands
		// on OFF. Refusing here is what keeps that from silently meaning plaintext.
		SmtpProperties smtp = new SmtpProperties("smtp.example.com", 25, "tls", null, null, null, true,
				Duration.ofSeconds(5), Duration.ofSeconds(5), Duration.ofSeconds(5), 3,
				new SmtpProperties.Addresses(FROM, null, null));
		EmailSender sender = senderWith(smtp, configWithTemplate(tmp));

		assertFalse(sender.sendMessage("user@example.com", "subj", "body"));

		verify(mailSender, never()).send(any(MimeMessage.class));
	}

	@Test
	void sendMessage_declaredPlaintext_stillSends(@TempDir Path tmp) throws Exception {
		// The guard above must key on "cannot be resolved", not on "is plaintext": a loopback MTA declared
		// with security=off is a supported configuration.
		SmtpProperties smtp = new SmtpProperties("smtp.example.com", 25, "off", null, null, null, true,
				Duration.ofSeconds(5), Duration.ofSeconds(5), Duration.ofSeconds(5), 3,
				new SmtpProperties.Addresses(FROM, null, null));

		assertTrue(senderWith(smtp, configWithTemplate(tmp)).sendMessage("user@example.com", "s", "b"));
	}

	@Test
	void sendMessage_multipleBccAddresses_areStillAccepted(@TempDir Path tmp) throws Exception {
		// bcc is operator-configured, so a list stays legal there — it must keep using parse().
		EmailSender sender = senderWith(
				smtp("smtp.example.com", FROM, "audit@example.com,archive@example.com", null),
				configWithTemplate(tmp));

		assertTrue(sender.sendMessage("user@example.com", "subj", "body"));
		assertEquals(2, captureSentMessage().getRecipients(Message.RecipientType.BCC).length);
	}

	@Test
	void sendMessage_unparseableRecipient_doesNotLogTheRawSubject(@TempDir Path tmp) throws IOException {
		// The build-failure branch had no coverage at all, and it logged the raw msgSubject parameter
		// rather than the CRLF-collapsed local — so a caller could forge lines in the ERROR log an
		// operator uses for abuse triage, reachable in one POST /message.
		EmailSender sender = senderWith(smtp("smtp.example.com", FROM, null, null), configWithTemplate(tmp));
		String forged = "Hi\r\nERROR Fake log line injected by the caller";

		// A space fails InternetAddress.checkAddress, so this reaches the catch rather than the sender.
		assertFalse(sender.sendMessage("a b@example.com", forged, "body"));
		verify(mailSender, never()).send(any(MimeMessage.class));
		// noneMatch is satisfied by an empty capture, so without this the test would keep passing if the
		// log call it guards were deleted, or if the appender stopped reaching this class.
		logAppender.assertCapturedSomething();
		assertTrue(logAppender.allMessages()
						.noneMatch(message -> message.contains("\r") || message.contains("\n")),
				"no log line may carry a raw CR/LF from the subject; got: " + logAppender);
	}

	@Test
	void sendMessage_permanentAuthFailure_isNotRetried(@TempDir Path tmp) throws IOException {
		// An expired SMTP password would otherwise produce max-send-attempts consecutive failed AUTHs per
		// send, which providers answer with a temporary account lockout. Shaped the way Spring actually
		// raises it — JavaMailSenderImpl wraps the jakarta.mail exception, and its own message is the fixed
		// "Authentication failed", so the server's reply code is only reachable through the cause.
		EmailSender sender = senderWith(smtp("smtp.example.com", FROM, null, null), configWithTemplate(tmp));
		doThrow(new MailAuthenticationException(
				new AuthenticationFailedException("535 5.7.8 Username and Password not accepted")))
				.when(mailSender).send(any(MimeMessage.class));

		assertFalse(sender.sendMessage("user@example.com", "subj", "body"));
		verify(mailSender, times(1)).send(any(MimeMessage.class));
	}

	@Test
	void sendMessage_transientAuthFailure_isRetried(@TempDir Path tmp) throws IOException {
		// Angus raises AuthenticationFailedException for a 4xx temporary failure exactly as for a permanent
		// 5xx, so classifying on the exception type alone dropped mail on a relay that merely rate-limits
		// AUTH — silently, and worst on the password-reset path where the response is a deliberate 200.
		EmailSender sender = senderWith(smtp("smtp.example.com", FROM, null, null), configWithTemplate(tmp));
		doThrow(new MailAuthenticationException(
				new AuthenticationFailedException("454 4.7.0 Temporary authentication failure")))
				.when(mailSender).send(any(MimeMessage.class));

		assertFalse(sender.sendMessage("user@example.com", "subj", "body"));
		verify(mailSender, times(3)).send(any(MimeMessage.class));
	}

	@Test
	void sendMessage_rejectedAddress_isNotRetried(@TempDir Path tmp) throws Exception {
		EmailSender sender = senderWith(smtp("smtp.example.com", FROM, null, null), configWithTemplate(tmp));
		SendFailedException rejected = new SendFailedException("550 no such user",
				new MessagingException("rejected"),
				new Address[0], new Address[0], new Address[]{new InternetAddress("user@example.com")});
		doThrow(new MailSendException("failed", rejected)).when(mailSender).send(any(MimeMessage.class));

		assertFalse(sender.sendMessage("user@example.com", "subj", "body"));
		verify(mailSender, times(1)).send(any(MimeMessage.class));
	}

	@Test
	void sendMessage_partialDelivery_isNotRetriedSoNoDuplicateIsSent(@TempDir Path tmp) throws Exception {
		// Reachable when a configured bcc is rejected after the recipient has already been served. The
		// MimeMessage is built once and AuthService generates one token per dispatch, so re-sending would
		// deliver a byte-identical duplicate — a second copy of the same still-valid password-reset link —
		// and the loop must stop even though send() threw. Returning true matters just as much: a false
		// return makes AuthService remove a token that is already in the recipient's inbox.
		EmailSender sender = senderWith(smtp("smtp.example.com", FROM, "bad@example.com", null),
				configWithTemplate(tmp));
		SendFailedException partial = new SendFailedException("bcc rejected",
				new MessagingException("550 bcc"),
				new Address[]{new InternetAddress("user@example.com")},
				new Address[0], new Address[]{new InternetAddress("bad@example.com")});
		doThrow(new MailSendException("failed", partial)).when(mailSender).send(any(MimeMessage.class));

		assertTrue(sender.sendMessage("user@example.com", "subj", "body"),
				"the intended recipient was reached, so the send counts as delivered");
		verify(mailSender, times(1)).send(any(MimeMessage.class));
	}

	@Test
	void sendMessage_partialDeliveryToAnAngleBracketedRecipient_isStillRecognised(@TempDir Path tmp)
			throws Exception {
		// The verdict has to compare parsed address against parsed address. Address.toString() re-renders,
		// dropping the angle brackets this caller supplied, so comparing it against the raw recipient
		// string reported a delivered message as failed — MessageService then answers 503 and a retrying
		// caller produces exactly the duplicate the branch above exists to prevent.
		EmailSender sender = senderWith(smtp("smtp.example.com", FROM, "bad@example.com", null),
				configWithTemplate(tmp));
		SendFailedException partial = new SendFailedException("bcc rejected",
				new MessagingException("550 bcc"),
				new Address[]{new InternetAddress("user@example.com")},
				new Address[0], new Address[]{new InternetAddress("bad@example.com")});
		doThrow(new MailSendException("failed", partial)).when(mailSender).send(any(MimeMessage.class));

		assertTrue(sender.sendMessage("<user@example.com>", "subj", "body"),
				"the delivered address is the same mailbox, just rendered without the brackets");
	}

	@Test
	void sendMessage_partialDeliveryViaFailedMessages_isAlsoDetected(@TempDir Path tmp) throws Exception {
		// MailSendException keeps the real jakarta.mail exceptions in getMessageExceptions() rather than in
		// getCause(), so both routes have to be searched.
		EmailSender sender = senderWith(smtp("smtp.example.com", FROM, null, null), configWithTemplate(tmp));
		SendFailedException partial = new SendFailedException("partly sent",
				new MessagingException("550"),
				new Address[]{new InternetAddress("other@example.com")},
				new Address[0], new Address[]{new InternetAddress("user@example.com")});
		doThrow(new MailSendException(Map.of("message", partial))).when(mailSender).send(any(MimeMessage.class));

		assertFalse(sender.sendMessage("user@example.com", "subj", "body"),
				"the intended recipient was not among the delivered addresses");
		verify(mailSender, times(1)).send(any(MimeMessage.class));
	}

	@Test
	void sendMessage_nonFinalAttemptsLogAtWarnAndOnlyTheLastAtError(@TempDir Path tmp) throws IOException {
		// ERROR-based alerting cannot distinguish "mail is down" from "mail is fine" if every attempt,
		// including ones the next attempt recovers from, is logged at ERROR.
		EmailSender sender = senderWith(smtp("smtp.example.com", FROM, null, null), configWithTemplate(tmp));
		doThrow(new MailSendException("down")).when(mailSender).send(any(MimeMessage.class));

		assertFalse(sender.sendMessage("user@example.com", "subj", "body"));

		assertEquals(2, logAppender.countAt(Level.WARN),
				"attempts 1 and 2 are recoverable; got: " + logAppender);
		assertEquals(1, logAppender.countAt(Level.ERROR),
				"only giving up is an incident; got: " + logAppender);
	}

	@Test
	void blankCanonicalFromKey_doesNotBeatTheDeprecatedOne(@TempDir Path tmp) throws Exception {
		// entrystore.smtp.email.from= binds to "", which previously won on a != null check and then failed
		// the blank guard on every send, so mail stopped with the deprecated key correctly set.
		EmailSender sender = new EmailSender(mailSender,
				smtp("smtp.example.com", "", null, null),
				new DeprecatedEmailAddressProperties("legacy@example.com", null),
				new MailTemplateRenderer(configWithTemplate(tmp)));

		assertTrue(sender.sendMessage("user@example.com", "subj", "body"));
		assertEquals("legacy@example.com", captureSentMessage().getFrom()[0].toString());
	}

	@Test
	void unparseableConfiguredFromAddress_isReportedAtStartupNamingTheKey(@TempDir Path tmp) throws IOException {
		// Deliberately not a startup failure — a malformed address is a mail-only misconfiguration — but it
		// otherwise surfaced per send with a log naming neither the key nor which address was at fault.
		senderWith(smtp("smtp.example.com", "not a valid address", null, null), configWithTemplate(tmp));

		assertTrue(logAppender.messagesAt(Level.ERROR)
						.anyMatch(message -> message.contains("entrystore.smtp.email.from")),
				"got: " + logAppender);
	}

	@Test
	void sendPasswordChangeConfirmation_usesThePasswordChangeTemplateAndSubject(@TempDir Path tmp) throws Exception {
		// The happy path was unasserted, so swapping the enum constant would have mailed a sign-up body to
		// real users with the whole suite green.
		Config config = configWithTemplate(tmp, EmailTemplate.PASSWORD_CHANGE, "changed for __NAME__");
		config.setProperty(EmailTemplate.PASSWORD_CHANGE.getSubjectKey(), "Your password has been changed");
		EmailSender sender = senderWith(smtp("smtp.example.com", FROM, null, null), config);

		sender.sendPasswordChangeConfirmation(userEntryNamed("changed@example.com"));

		MimeMessage message = captureSentMessage();
		assertEquals("Your password has been changed", message.getSubject());
		assertEquals("changed@example.com", message.getRecipients(Message.RecipientType.TO)[0].toString());
		assertEquals("changed for ", message.getContent().toString(),
				"a null display name leaves the placeholder consumed rather than throwing");
	}

	@Test
	void sendPasswordChangeConfirmation_namelessPrincipal_fallsBackWithoutNpe() {
		// User.getName() returning null previously NPE'd on contains("@") straight into the
		// ENTRYSTORE-1028 catch, making both the EntryUtil.getEmail fallback and this warning unreachable.
		assertPasswordChangeTemplateOnClasspath();
		EmailSender sender = senderWith(smtp("smtp.example.com", FROM, null, null), new PropertiesConfiguration("test"));

		Entry entry = mock(Entry.class);
		User user = mock(User.class);
		when(user.getName()).thenReturn(null);
		when(entry.getResource()).thenReturn(user);

		assertDoesNotThrow(() -> sender.sendPasswordChangeConfirmation(entry));
		verifyNoInteractions(mailSender);
		assertTrue(logAppender.allMessages().anyMatch(message -> message.contains("invalid email address")),
				"the fallback must reach the warning rather than the catch; got: " + logAppender);
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
