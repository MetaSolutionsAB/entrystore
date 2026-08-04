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
import jakarta.mail.Address;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.SendFailedException;
import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import lombok.extern.slf4j.Slf4j;
import org.entrystore.Entry;
import org.entrystore.User;
import org.entrystore.repository.util.EntryUtil;
import org.entrystore.rest.springboot.configuration.DeprecatedEmailAddressProperties;
import org.entrystore.rest.springboot.configuration.SmtpProperties;
import org.jetbrains.annotations.Nullable;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailException;
import org.springframework.mail.MailParseException;
import org.springframework.mail.MailPreparationException;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

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
		Configured from = resolveDeprecatable(smtp.email().from(), deprecatedAddresses.from(),
				"entrystore.smtp.email.from", "entrystore.auth.email.from");
		Configured bccAddresses = resolveDeprecatable(smtp.email().bcc(), deprecatedAddresses.bcc(),
				"entrystore.smtp.email.bcc", "entrystore.auth.email.bcc");
		Configured replyTo = new Configured(smtp.email().replyTo(), "entrystore.smtp.email.reply-to");
		this.defaultFrom = from.value();
		this.bcc = bccAddresses.value();
		this.defaultReplyTo = replyTo.value();
		// Reported once at startup rather than validated into a boot failure: a malformed address is a
		// mail-only misconfiguration, and it otherwise surfaces per send with a log naming neither the
		// key nor which of the three addresses was at fault.
		reportUnparseableAddress(from, true);
		reportUnparseableAddress(bccAddresses, false);
		reportUnparseableAddress(replyTo, true);
	}

	/** A resolved configuration value together with the key it actually came from, for diagnostics. */
	private record Configured(@Nullable String value, String key) {}

	/**
	 * Prefers the canonical key and falls back to the deprecated one. The check is on <em>text</em>, not
	 * on null: {@code entrystore.smtp.email.from=} binds to {@code ""}, which would otherwise beat a
	 * correctly-set {@code entrystore.auth.email.from} and then fail the blank guard on every send.
	 */
	private static Configured resolveDeprecatable(@Nullable String preferred, @Nullable String deprecated,
												  String preferredKey, String deprecatedKey) {
		if (hasText(preferred)) {
			return new Configured(preferred, preferredKey);
		}
		if (hasText(deprecated)) {
			log.warn("Using deprecated configuration key {}; rename it to {}", deprecatedKey, preferredKey);
			return new Configured(deprecated, deprecatedKey);
		}
		return new Configured(null, preferredKey);
	}

	/**
	 * Logs an ERROR naming the key and the offending value when a configured envelope address will not
	 * parse, so the operator does not have to infer it from a per-send failure. {@code single}
	 * distinguishes the addresses that must be exactly one from {@code bcc}, which may be a list.
	 */
	private static void reportUnparseableAddress(Configured address, boolean single) {
		if (!hasText(address.value())) {
			return;
		}
		try {
			if (single) {
				new InternetAddress(address.value());
			} else {
				InternetAddress.parse(address.value());
			}
		} catch (AddressException e) {
			log.error("{}='{}' is not a valid email address{}; every send will fail while it stays set",
					address.key(), address.value(), single ? "" : " list");
		}
	}

	private static boolean hasText(@Nullable String value) {
		return value != null && !value.isBlank();
	}

	public boolean sendMessage(String msgTo, String msgSubject, String msgBody) {
		return sendMessage(msgTo, msgSubject, msgBody, null, null);
	}

	public boolean sendMessage(String msgTo, String msgSubject, String msgBody,
							   @Nullable String msgFrom, @Nullable String msgReplyTo) {
		String from = hasText(msgFrom) ? msgFrom : defaultFrom;
		String replyTo = hasText(msgReplyTo) ? msgReplyTo : defaultReplyTo;

		// Guard before touching the sender. Jakarta Mail defaults a null host to localhost, so reaching
		// the transport unconfigured would relay through whatever MTA listens on localhost:25.
		if (!smtp.isConfigured()) {
			log.warn("SMTP host is not configured; email to {} not sent", HttpUtil.sanitizeForLog(msgTo));
			return false;
		}
		if (!hasText(from)) {
			log.warn("SMTP from-address is not configured; email to {} not sent",
					HttpUtil.sanitizeForLog(msgTo));
			return false;
		}

		// Collapsed before anything reads it, so the same sanitized value reaches the header and every
		// log line below — a subject-scoped local inside the try would leave the catch logging the raw
		// parameter. Reachable from POST /message: HtmlSanitizer.sanitizeToPlainText strips markup but
		// then HTML-unescapes, turning &#13;&#10; back into a real CRLF, and setSubject leaves an
		// all-ASCII value untouched.
		String subject = msgSubject.replace('\r', ' ').replace('\n', ' ');

		MimeMessage message;
		try {
			message = mailSender.createMimeMessage();
			message.setFrom(new InternetAddress(from));
			if (hasText(replyTo)) {
				// Exactly one address. InternetAddress.parse accepts a comma-separated list, and on the
				// POST /message path replyTo is the caller's own principal name — which is never
				// format-validated (PrincipalManagerImpl.setPrincipalName strips only \p{Cf}, trims and
				// lowercases), so a list here would hand out a relay under the deployment's own From.
				message.setReplyTo(new InternetAddress[]{new InternetAddress(replyTo)});
			}
			if (hasText(bcc)) {
				// parse() is deliberate: bcc is operator-configured and may legitimately be a list.
				message.addRecipients(Message.RecipientType.BCC, InternetAddress.parse(bcc));
			}
			// Single address for the same reason as replyTo: msgTo is a caller-supplied recipient on the
			// POST /message path, carrying only @NotBlank from SendMessageRequestBody.
			message.addRecipients(Message.RecipientType.TO,
					new InternetAddress[]{new InternetAddress(msgTo)});
			message.setSubject(subject, "UTF-8");
			message.setText(msgBody, "UTF-8", "html");
		} catch (MessagingException e) {
			// Building the message failed — retrying cannot help. Log the exception, not just its message:
			// which address was rejected is only in the AddressException underneath. A malformed
			// *configured* address is additionally named by key at startup; this branch is what catches a
			// caller-supplied recipient, including the comma lists rejected above.
			log.error("Failed to build email to {} (subject [{}])",
					HttpUtil.sanitizeForLog(msgTo), HttpUtil.sanitizeForLog(subject), e);
			return false;
		}

		int attempts = smtp.maxSendAttempts();
		for (int attempt = 1; attempt <= attempts; attempt++) {
			try {
				mailSender.send(message);
				return true;
			} catch (MailException e) {
				// mailSender.send declares only the unchecked MailException, so nothing forces this
				// catch to exist — omit it and a send failure escapes as a 500 instead of the false this
				// method's contract promises. Pass the exception through: unlike jakarta.mail's
				// MessagingException, MailException.getMessage() does not append the cause, so logging
				// the message alone would drop the ConnectException / SSLHandshakeException underneath.
				Address[] delivered = deliveredAddresses(e);
				if (delivered.length > 0) {
					// Some recipient already has this message. Re-sending the same MimeMessage would
					// deliver a duplicate — and on the password-reset path, a duplicate carrying a
					// distinct valid token — so the loop stops here regardless of the outcome reported.
					log.error("Email to {} was only partially delivered on attempt {}/{} ({} recipient(s) "
									+ "reached); not retrying, because a retry would duplicate it",
							HttpUtil.sanitizeForLog(msgTo), attempt, attempts, delivered.length, e);
					return Arrays.stream(delivered)
							.anyMatch(address -> address.toString().equalsIgnoreCase(msgTo));
				}
				if (isPermanent(e)) {
					log.error("Attempt {}/{} to send email to {} failed permanently; not retrying",
							attempt, attempts, HttpUtil.sanitizeForLog(msgTo), e);
					return false;
				}
				// Only the final failure is an incident. Logging every attempt at ERROR — including ones
				// the next attempt recovers from — leaves ERROR-based alerting unable to tell "mail is
				// down" from "mail is fine".
				if (attempt == attempts) {
					log.error("Attempt {}/{} to send email to {} failed; giving up",
							attempt, attempts, HttpUtil.sanitizeForLog(msgTo), e);
				} else {
					log.warn("Attempt {}/{} to send email to {} failed; retrying",
							attempt, attempts, HttpUtil.sanitizeForLog(msgTo), e);
				}
			}
		}

		return false;
	}

	/**
	 * The addresses the transport confirmed as delivered, or an empty array when it reported none.
	 * {@code MailSendException} keeps the real {@code jakarta.mail} exceptions in
	 * {@link MailSendException#getMessageExceptions()} rather than in its cause, so both are searched.
	 */
	private static Address[] deliveredAddresses(MailException e) {
		return transportFailures(e)
				.map(SendFailedException::getValidSentAddresses)
				.filter(addresses -> addresses != null && addresses.length > 0)
				.findFirst()
				.orElseGet(() -> new Address[0]);
	}

	/**
	 * True when retrying cannot succeed: bad credentials, a message that will not parse, or an address
	 * the server rejected outright. Everything else — a refused connection, a timeout, a 4xx greylisting
	 * — stays transient, which is what keeps {@code PasswordResetResourceIT}'s ECONNREFUSED case running
	 * the full attempt count. Retrying a permanent failure is not merely wasted: repeated failed AUTHs
	 * against an expired password are what providers answer with a temporary account lockout.
	 */
	private static boolean isPermanent(MailException e) {
		if (e instanceof MailAuthenticationException || e instanceof MailParseException
				|| e instanceof MailPreparationException) {
			return true;
		}
		return causes(e).anyMatch(cause -> cause instanceof AddressException)
				|| transportFailures(e).anyMatch(failure -> failure.getInvalidAddresses() != null
						&& failure.getInvalidAddresses().length > 0);
	}

	private static Stream<SendFailedException> transportFailures(MailException e) {
		return causes(e)
				.filter(SendFailedException.class::isInstance)
				.map(SendFailedException.class::cast);
	}

	/**
	 * Every throwable that could carry the transport's verdict: the exception itself, its cause chain,
	 * and — for a {@code MailSendException} — the per-message exceptions and their own causes. Bounded,
	 * so a pathological cause cycle cannot spin here.
	 */
	private static Stream<Throwable> causes(MailException e) {
		if (e instanceof MailSendException sendFailure) {
			return Stream.concat(chain(e), Arrays.stream(sendFailure.getMessageExceptions())
					.flatMap(EmailSender::chain));
		}
		return chain(e);
	}

	private static Stream<Throwable> chain(Throwable throwable) {
		return Stream.iterate(throwable, Objects::nonNull, Throwable::getCause).limit(10);
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
	 * Builds a substitution map from alternating key/value pairs. Not {@code Map.of}, which rejects null
	 * values: {@link #escape} returns null for a null input and {@link MailTemplateRenderer#render} skips
	 * null values so the placeholder text stays in place, matching the per-placeholder null guards this
	 * replaced. That is a deliberate choice of a visible {@code __NAME__} over a 500 on the signup path,
	 * which has no surrounding catch.
	 */
	private static Map<String, String> substitutions(@Nullable String... keysAndValues) {
		if (keysAndValues.length % 2 != 0) {
			// A programming error, not a configuration one: without this the loop below reads past the
			// end and throws ArrayIndexOutOfBoundsException from inside the signup path.
			throw new IllegalArgumentException("substitutions() takes alternating key/value pairs, got "
					+ keysAndValues.length + " arguments");
		}
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
			// Null-checked before contains(): a nameless principal otherwise NPEs straight into the
			// catch below, which makes both the EntryUtil.getEmail fallback and the warning unreachable.
			if (msgTo == null || !msgTo.contains("@")) {
				msgTo = EntryUtil.getEmail(userEntry);
			}

			if (msgTo == null || !msgTo.contains("@")) {
				log.warn("Unable to send email, invalid email address of recipient: {}",
						HttpUtil.sanitizeForLog(msgTo));
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
