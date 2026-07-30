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

import com.google.common.html.HtmlEscapers;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeUtility;
import lombok.extern.slf4j.Slf4j;
import org.entrystore.Entry;
import org.entrystore.User;
import org.entrystore.repository.util.EntryUtil;
import org.entrystore.rest.springboot.configuration.DeprecatedEmailAddressProperties;
import org.entrystore.rest.springboot.configuration.SmtpProperties;
import org.jetbrains.annotations.Nullable;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Sends the transactional emails.
 *
 * <p>Sending returns {@code false} rather than throwing, and each caller decides what that means:
 * {@code MessageService} turns it into a 503, signup into a 400, and the password-reset dispatch
 * swallows it so the response stays byte-identical whether or not the account exists. Keeping that
 * contract is why the send loop catches {@code MailException} — see {@link #sendMessage}.
 *
 * <p>Transport settings are fixed when the {@code JavaMailSender} bean is built, not re-read per send,
 * so a runtime mutation of the legacy {@code Config} does not affect SMTP. Message <em>content</em>
 * (subjects, templates) is still resolved per send by {@link MailTemplateRenderer}.
 */
@Slf4j
@Component
public class EmailSender {

	private final JavaMailSender mailSender;
	private final SmtpProperties smtp;
	private final MailTemplateRenderer templates;

	private final @Nullable String defaultFrom;
	private final @Nullable String bcc;
	private final @Nullable String defaultReplyTo;

	public EmailSender(JavaMailSender mailSender,
					   SmtpProperties smtp,
					   DeprecatedEmailAddressProperties deprecatedAddresses,
					   MailTemplateRenderer templates) {
		this.mailSender = mailSender;
		this.smtp = smtp;
		this.templates = templates;
		this.defaultFrom = resolveDeprecatable(
				smtp.email().from(), deprecatedAddresses.from(), "entrystore.smtp.email.from", "entrystore.auth.email.from");
		this.bcc = resolveDeprecatable(
				smtp.email().bcc(), deprecatedAddresses.bcc(), "entrystore.smtp.email.bcc", "entrystore.auth.email.bcc");
		this.defaultReplyTo = smtp.email().replyTo();
	}

	private static @Nullable String resolveDeprecatable(@Nullable String preferred, @Nullable String deprecated,
														String preferredKey, String deprecatedKey) {
		if (preferred != null) {
			return preferred;
		}
		if (deprecated != null) {
			log.warn("Using deprecated configuration key {}; rename it to {}", deprecatedKey, preferredKey);
		}
		return deprecated;
	}

	public boolean sendMessage(String msgTo, String msgSubject, String msgBody) {
		return sendMessage(msgTo, msgSubject, msgBody, null, null);
	}

	public boolean sendMessage(String msgTo, String msgSubject, String msgBody,
							   @Nullable String msgFrom, @Nullable String msgReplyTo) {
		String from = (msgFrom == null || msgFrom.isBlank()) ? defaultFrom : msgFrom;
		String replyTo = (msgReplyTo == null || msgReplyTo.isBlank()) ? defaultReplyTo : msgReplyTo;

		// Guard before touching the sender. Jakarta Mail defaults a null host to localhost, so reaching
		// the transport unconfigured would relay through whatever MTA listens on localhost:25.
		if (!smtp.isConfigured()) {
			log.warn("SMTP host is not configured; email to {} not sent", msgTo);
			return false;
		}
		if (from == null || from.isBlank()) {
			log.warn("SMTP from-address is not configured; email to {} not sent", msgTo);
			return false;
		}

		MimeMessage message;
		try {
			message = mailSender.createMimeMessage();
			message.setFrom(new InternetAddress(from));
			if (replyTo != null && !replyTo.isBlank()) {
				message.setReplyTo(InternetAddress.parse(replyTo));
			}
			if (bcc != null && !bcc.isBlank()) {
				message.addRecipients(Message.RecipientType.BCC, InternetAddress.parse(bcc));
			}
			message.addRecipients(Message.RecipientType.TO, InternetAddress.parse(msgTo));
			// Collapse CR/LF first so a user-supplied subject cannot terminate the header and inject
			// another one. Reachable from POST /message: HtmlSanitizer.sanitizeToPlainText strips markup
			// but then HTML-unescapes, turning &#13;&#10; back into a real CRLF, and setSubject leaves an
			// all-ASCII value untouched.
			String subject = msgSubject.replace('\r', ' ').replace('\n', ' ');
			// An already RFC 2047-encoded subject is folded rather than re-encoded, preserving the exact
			// header bytes EntryStore 6.0 emitted (MimeUtility leaves an all-ASCII encoded word alone, so
			// only the fold width would otherwise differ). Only =?utf-8? is detected; an encoded word in
			// another charset is re-encoded, as before. The fold's own CRLF continuations are added after
			// the strip above and are legitimate header folding, not injection.
			if (subject.toLowerCase(Locale.ROOT).startsWith("=?utf-8?")) {
				message.setHeader("Subject", MimeUtility.fold(9, subject));
			} else {
				message.setSubject(subject, "UTF-8");
			}
			message.setText(msgBody, "UTF-8", "html");
		} catch (MessagingException e) {
			// Building the message failed — retrying cannot help. Log the exception, not just its
			// message: a malformed configured address surfaces here and the key is not otherwise named.
			log.error("Failed to build email to {} (subject [{}])", msgTo, msgSubject, e);
			return false;
		}

		for (int attempt = 1; attempt <= smtp.maxSendAttempts(); attempt++) {
			try {
				mailSender.send(message);
				return true;
			} catch (MailException e) {
				// mailSender.send declares only the unchecked MailException, so nothing forces this
				// catch to exist — omit it and a send failure escapes as a 500 instead of the false this
				// method's contract promises. Pass the exception through: unlike jakarta.mail's
				// MessagingException, MailException.getMessage() does not append the cause, so logging
				// the message alone would drop the ConnectException / SSLHandshakeException underneath.
				log.error("Attempt {}/{} to send email to {} failed",
						attempt, smtp.maxSendAttempts(), msgTo, e);
			}
		}

		return false;
	}

	public boolean sendSignupConfirmation(String recipientName, String recipientEmail, String confirmationLink) {
		// Name and email are escaped because they are user-supplied and land in an HTML body; the
		// confirmation link is generated by us and must stay literal.
		return sendTemplateMessage(EmailTemplate.SIGNUP, recipientEmail, substitutions(
				"__CONFIRMATION_LINK__", confirmationLink,
				"__NAME__", escape(recipientName),
				"__EMAIL__", escape(recipientEmail)));
	}

	public boolean sendPasswordResetConfirmation(String recipientEmail, String confirmationLink) {
		return sendTemplateMessage(EmailTemplate.PASSWORD_RESET, recipientEmail, substitutions(
				"__CONFIRMATION_LINK__", confirmationLink,
				"__EMAIL__", escape(recipientEmail)));
	}

	/**
	 * Builds a substitution map from alternating key/value pairs. Deliberately not {@code Map.of}:
	 * {@link #escape} returns null for a null input and {@link MailTemplateRenderer#render} skips null
	 * values to leave the placeholder in place, whereas {@code Map.of} rejects null values outright and
	 * would turn a missing display name into an NPE — a 500 on the signup path, which has no
	 * surrounding catch.
	 */
	private static Map<String, String> substitutions(@Nullable String... keysAndValues) {
		Map<String, String> substitutions = new LinkedHashMap<>();
		for (int i = 0; i < keysAndValues.length; i += 2) {
			substitutions.put(keysAndValues[i], keysAndValues[i + 1]);
		}
		return substitutions;
	}

	public void sendPasswordChangeConfirmation(Entry userEntry) {
		// The password has already been changed by the caller; sending the confirmation email is
		// best-effort. Never let an email failure (e.g. unconfigured SMTP/base URL, or an unexpected
		// recipient/template problem) escape and turn a successful password change into an HTTP 500
		// (ENTRYSTORE-1028).
		try {
			String msgTo = ((User) userEntry.getResource()).getName();
			if (!msgTo.contains("@")) {
				msgTo = EntryUtil.getEmail(userEntry);
			}

			if (msgTo == null || !msgTo.contains("@")) {
				log.warn("Unable to send email, invalid email address of recipient: {}", msgTo);
				return;
			}

			String recipientName = EntryUtil.getName(userEntry);
			if (recipientName == null) {
				recipientName = "";
			}

			if (!sendTemplateMessage(EmailTemplate.PASSWORD_CHANGE, msgTo,
					substitutions("__NAME__", escape(recipientName)))) {
				log.warn("Password change confirmation email to {} could not be sent", msgTo);
			}
		} catch (RuntimeException e) {
			log.error("Failed to send password change confirmation email", e);
		}
	}

	private boolean sendTemplateMessage(EmailTemplate template, String recipient, Map<String, String> substitutions) {
		String body = templates.render(template, substitutions);
		if (body == null) {
			return false;
		}
		return sendMessage(recipient, templates.subject(template), body);
	}

	private static @Nullable String escape(@Nullable String value) {
		return value == null ? null : HtmlEscapers.htmlEscaper().escape(value);
	}
}
