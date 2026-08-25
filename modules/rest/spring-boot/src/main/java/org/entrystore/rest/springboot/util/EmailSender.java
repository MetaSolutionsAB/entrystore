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
import java.util.regex.Pattern;
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

	/**
	 * An SMTP 4xx reply code at the start of a server response line — a transient negative completion
	 * per RFC 5321, as opposed to a permanent 5xx.
	 */
	private static final Pattern TRANSIENT_SMTP_REPLY = Pattern.compile("^\\s*4\\d\\d(\\s|-|$)");

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
		// key nor which of the three addresses was at fault. The shapes below are the same constants the
		// send path passes, so the single-versus-list rule cannot drift between startup and send.
		reportUnparseableAddress(from, FROM_SHAPE);
		reportUnparseableAddress(bccAddresses, BCC_SHAPE);
		reportUnparseableAddress(replyTo, CONFIGURED_REPLY_TO_SHAPE);
	}

	/** A resolved configuration value together with the key it actually came from, for diagnostics. */
	private record Configured(@Nullable String value, String key) {}

	/**
	 * Whether an address value may name more than one recipient. Operator-configured values may be
	 * lists; anything a caller can influence must be exactly one mailbox, because an unvalidated
	 * principal name reaching {@code InternetAddress.parse} is a mail relay under the deployment's own
	 * {@code From}.
	 */
	private enum AddressShape {
		/** Exactly one mailbox — no comma list, no RFC822 group, no CR/LF. See {@link #singleMailbox}. */
		SINGLE_MAILBOX,
		/** A comma-separated list, as {@code InternetAddress.parse} accepts. */
		LIST;

		InternetAddress[] parse(String value) throws AddressException {
			return this == SINGLE_MAILBOX
					? new InternetAddress[]{singleMailbox(value)}
					: InternetAddress.parse(value);
		}
	}

	private static final AddressShape FROM_SHAPE = AddressShape.SINGLE_MAILBOX;
	private static final AddressShape BCC_SHAPE = AddressShape.LIST;
	/**
	 * {@code entrystore.smtp.email.reply-to} is operator-configured and has accepted a comma-separated
	 * list since {@code 376abd6a} (2019), which read it with {@code InternetAddress.parse}. A list is
	 * legal for Reply-To under RFC 5322, so constraining this one would break every send for a
	 * deployment carrying it. The caller-supplied override on the {@code POST /message} path is
	 * constrained instead — see {@link #sendMessage}.
	 */
	private static final AddressShape CONFIGURED_REPLY_TO_SHAPE = AddressShape.LIST;

	/**
	 * Parses a value that must name exactly one mailbox, rejecting the two shapes
	 * {@code new InternetAddress(String)} accepts on its own:
	 *
	 * <ul>
	 * <li><b>RFC822 groups.</b> That constructor enforces "exactly one <em>address</em>", and
	 *     jakarta.mail models {@code g:a@x.tld,b@y.tld;} as a single {@code InternetAddress} with
	 *     {@code isGroup()}. {@code SMTPTransport.sendMessage} then calls {@code expandGroups()}
	 *     unconditionally, so that one address becomes N envelope {@code RCPT TO} recipients.
	 * <li><b>CR/LF in the display-name phrase.</b> {@code checkAddress} validates only the addr-spec, and
	 *     {@code quotePhrase} quotes without stripping, so the control characters survive into the header
	 *     value. {@code InternetAddress.toString(Address[], int)} folds them — they arrive as a
	 *     continuation line rather than as an injected header — but a caller still has no business
	 *     writing raw control characters into one.
	 * </ul>
	 */
	private static InternetAddress singleMailbox(String value) throws AddressException {
		if (value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
			throw new AddressException("address contains CR/LF");
		}
		InternetAddress address = new InternetAddress(value, true);
		if (address.isGroup()) {
			throw new AddressException("group addresses are not accepted");
		}
		return address;
	}

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
	 * parse, so the operator does not have to infer it from a per-send failure.
	 */
	private static void reportUnparseableAddress(Configured address, AddressShape shape) {
		if (!hasText(address.value())) {
			return;
		}
		try {
			shape.parse(address.value());
		} catch (AddressException e) {
			log.error("{}='{}' is not a valid email address{}; every send will fail while it stays set",
					address.key(), address.value(), shape == AddressShape.LIST ? " list" : "");
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
		boolean callerSuppliedReplyTo = hasText(msgReplyTo);
		String replyTo = callerSuppliedReplyTo ? msgReplyTo : defaultReplyTo;

		// Guard before touching the sender. Jakarta Mail defaults a null host to localhost, so reaching
		// the transport unconfigured would relay through whatever MTA listens on localhost:25.
		if (!smtp.isConfigured()) {
			log.warn("SMTP host is not configured; email to {} not sent", HttpUtil.sanitizeForLog(msgTo));
			return false;
		}
		if (smtp.securityIsUnresolved()) {
			// Binding stays lenient so a mail misconfiguration cannot abort startup, which means an
			// unrecognised entrystore.smtp.security lands on OFF. Refusing here is what keeps that from
			// meaning "send it in the clear": the operator who wrote security=tls believes the connection
			// is encrypted. The offending value is named by SmtpProperties at startup.
			log.error("SMTP transport security is not one of starttls|ssl|off; refusing to send email to {} "
					+ "unencrypted", HttpUtil.sanitizeForLog(msgTo));
			return false;
		}
		if (!hasText(from)) {
			log.warn("SMTP from-address is not configured; email to {} not sent",
					HttpUtil.sanitizeForLog(msgTo));
			return false;
		}

		// Collapsed before the header is built, because the Subject header must never carry a CR or LF:
		// HtmlSanitizer.sanitizeToPlainText strips markup and then HTML-unescapes, so a POST /message
		// subject of &#13;&#10; arrives here as a real CRLF, and setSubject leaves an all-ASCII value
		// untouched. Hoisted above the try as well so the catch below logs this value rather than the raw
		// parameter.
		String subject = msgSubject.replace('\r', ' ').replace('\n', ' ');

		MimeMessage message;
		InternetAddress recipient;
		try {
			message = mailSender.createMimeMessage();
			message.setFrom(singleMailbox(from));
			if (hasText(replyTo)) {
				// A caller-supplied Reply-To is the caller's own principal name, which is never
				// format-validated (PrincipalManagerImpl.setPrincipalName strips only \p{Cf}, trims and
				// lowercases), so it must name exactly one mailbox. The operator-configured default keeps
				// the list shape it has accepted since 2019 — see CONFIGURED_REPLY_TO_SHAPE.
				message.setReplyTo(callerSuppliedReplyTo
						? AddressShape.SINGLE_MAILBOX.parse(replyTo)
						: CONFIGURED_REPLY_TO_SHAPE.parse(replyTo));
			}
			if (hasText(bcc)) {
				message.addRecipients(Message.RecipientType.BCC, BCC_SHAPE.parse(bcc));
			}
			// Caller-supplied on the POST /message path, carrying only @NotBlank from
			// SendMessageRequestBody, so the same single-mailbox rule applies. Kept for the
			// partial-delivery comparison below, which has to compare parsed address against parsed
			// address rather than against this raw string.
			recipient = singleMailbox(msgTo);
			message.addRecipients(Message.RecipientType.TO, new InternetAddress[]{recipient});
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
					// Some recipient already has this message. The MimeMessage is built once above and
					// AuthService generates one token per dispatch, so re-sending would deliver a
					// byte-identical duplicate — on the password-reset path, a second copy of the same
					// still-valid link — and the loop stops regardless of the outcome reported.
					//
					// Returning true when the intended recipient was among the delivered addresses is the
					// load-bearing part: a false return makes AuthService.dispatchPasswordResetEmail call
					// signupTokenCache.removeToken, leaving the recipient holding a delivered mail whose
					// link is already dead.
					log.error("Email to {} was only partially delivered on attempt {}/{} ({} recipient(s) "
									+ "reached); not retrying, because a retry would duplicate it",
							HttpUtil.sanitizeForLog(msgTo), attempt, attempts, delivered.length, e);
					return wasDelivered(delivered, recipient);
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
	 * Whether {@code recipient} is among the addresses the transport confirmed.
	 *
	 * <p>Compares the parsed addr-spec on both sides. {@code Address.toString()} re-renders — it drops the
	 * angle brackets of {@code <ada@example.com>} and adds them to a quoted local part — so comparing a
	 * rendered address against the caller's raw string reports a delivered message as failed, at which
	 * point {@code MessageService} answers 503 and a retrying caller produces exactly the duplicate this
	 * branch exists to prevent.
	 */
	private static boolean wasDelivered(Address[] delivered, InternetAddress recipient) {
		return Arrays.stream(delivered)
				.filter(InternetAddress.class::isInstance)
				.map(InternetAddress.class::cast)
				.anyMatch(address -> address.getAddress() != null
						&& address.getAddress().equalsIgnoreCase(recipient.getAddress()));
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
	 * True when retrying cannot succeed: a message that will not parse, an address the server rejected
	 * outright, or an authentication failure the server did not report as temporary. Everything else — a
	 * refused connection, a timeout, a 4xx greylisting — stays transient, which is what keeps
	 * {@code PasswordResetResourceIT}'s ECONNREFUSED case running the full attempt count. Retrying a
	 * permanent failure is not merely wasted: repeated failed AUTHs against an expired password are what
	 * providers answer with a temporary account lockout.
	 *
	 * <p>Authentication is the one case that is not decided by exception type alone. Angus raises
	 * {@code AuthenticationFailedException} for a transient {@code 454 4.7.0 Temporary authentication
	 * failure} exactly as for a permanent {@code 535}, so treating the type as permanent would drop mail
	 * on a relay that merely rate-limits AUTH — silently, on the password-reset path. Neither
	 * {@code MailAuthenticationException} nor {@code AuthenticationFailedException} exposes the status
	 * code, so the leading digit of the server's response text is all there is to go on; when it is
	 * absent or unparseable the failure is treated as permanent, keeping the lockout protection.
	 */
	private static boolean isPermanent(MailException e) {
		if (e instanceof MailAuthenticationException) {
			return !reportedAsTransient(e);
		}
		if (e instanceof MailParseException || e instanceof MailPreparationException) {
			return true;
		}
		return causes(e).anyMatch(cause -> cause instanceof AddressException)
				|| transportFailures(e).anyMatch(failure -> failure.getInvalidAddresses() != null
						&& failure.getInvalidAddresses().length > 0);
	}

	/**
	 * Whether any message in the exception chain starts with an SMTP 4xx reply code, which RFC 5321
	 * defines as a transient negative completion. {@code MailAuthenticationException}'s own message is
	 * Spring's fixed "Authentication failed", so the server's text is only reachable through the cause.
	 */
	private static boolean reportedAsTransient(MailException e) {
		return causes(e)
				.map(Throwable::getMessage)
				.filter(Objects::nonNull)
				.anyMatch(message -> TRANSIENT_SMTP_REPLY.matcher(message).find());
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
	 * Builds a substitution map from alternating key/value pairs.
	 *
	 * <p>Not {@code Map.of}, which rejects null values, because {@link #escape} propagates a null input.
	 * No caller actually passes one — {@code sendPasswordChangeConfirmation} defaults a missing display
	 * name to {@code ""}, so the placeholder is substituted away rather than left visible, which is what
	 * {@code EmailSenderTest} asserts. The null tolerance exists so that a future caller which does pass
	 * null gets {@link MailTemplateRenderer#render}'s skip-and-leave-the-placeholder behaviour instead of
	 * an NPE on the signup path, which has no surrounding catch.
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
				log.warn("Password change confirmation email to {} could not be sent",
						HttpUtil.sanitizeForLog(msgTo));
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
