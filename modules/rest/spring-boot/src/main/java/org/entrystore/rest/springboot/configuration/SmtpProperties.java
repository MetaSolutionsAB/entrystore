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

import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.util.Arrays;

/**
 * Bindings for {@code entrystore.smtp.*}, consumed by {@code MailConfiguration} to build the
 * {@code JavaMailSender} and by {@code EmailSender} for the unconfigured-SMTP guard and the default
 * From/Bcc/Reply-To addresses.
 *
 * <p><b>The transport-security key.</b> {@code entrystore.smtp.security} is canonical, but
 * {@code entrystore.properties_example} documented {@code entrystore.smtp.ssl} instead — a key nothing
 * ever read — up to and including 5.6.2, and still does in the Spring Boot module's unreleased copies.
 * A deployment configured from one of those files was therefore sending plaintext SMTP with no warning
 * at all. 5.7.0 through 5.8.0 documented the canonical key correctly ({@code 26fb63af}, April 2025).
 * Both spellings bind; {@code security} wins whenever it resolves, and {@code ssl} is read only as a
 * fallback. {@link #effectiveSecurity()} is the only accessor that answers "what transport security is
 * actually applied" — {@link #security()} and {@link #ssl()} return the raw configured strings and must
 * not be compared against directly.
 *
 * <p>Both keys are parsed leniently, and identically. Neither was validated before 6.1: {@code ssl}
 * was never read at all, and {@code security} — present as {@code Settings.SMTP_SECURITY} at {@code 4.1},
 * the earliest tag in this repository — was compared with {@code equalsIgnoreCase}, so any unrecognised
 * value in <em>either</em> key silently meant plaintext.
 *
 * <p><b>An unrecognised value does not resolve to plaintext.</b> Binding stays lenient, so startup is
 * never aborted over mail configuration — that would turn a mail-only misconfiguration into a total
 * outage of the REST API. But leniency is not the same as failing open: a value this class cannot
 * resolve leaves {@link #securityIsUnresolved()} true, and {@code EmailSender} refuses to send at all
 * rather than sending in the clear under a configuration the operator believes is encrypted. The
 * offending value is reported at ERROR here, and {@code MailConfiguration} warns when a configured host
 * ends up on plaintext without the operator having declared it.
 *
 * <p>Recognised spellings are canonicalised the way Spring Boot's relaxed enum binding canonicalises —
 * letters and digits only, lowercased — so {@code STARTTLS}, {@code start-tls} and the
 * {@code ENTRYSTORE_SMTP_SECURITY=START_TLS} environment-variable form all resolve alike.
 *
 * <p>The timeouts and {@code check-server-identity} are configurable as of 6.1; the defaults here are
 * the values EntryStore 6.0 compiled in, so an existing deployment sees no change.
 */
@ConfigurationProperties(prefix = "entrystore.smtp")
public record SmtpProperties(
		String host,
		@DefaultValue("25") int port,
		String security,
		String ssl,
		String username,
		String password,
		@DefaultValue("true") boolean checkServerIdentity,
		@DefaultValue("5s") Duration connectionTimeout,
		@DefaultValue("5s") Duration readTimeout,
		@DefaultValue("5s") Duration writeTimeout,
		@DefaultValue("3") int maxSendAttempts,
		Addresses email
) {

	private static final Logger log = LoggerFactory.getLogger(SmtpProperties.class);

	private static final String SECURITY_KEY = "entrystore.smtp.security";
	private static final String SSL_KEY = "entrystore.smtp.ssl";
	private static final String USERNAME_KEY = "entrystore.smtp.username";
	private static final String PASSWORD_KEY = "entrystore.smtp.password";

	/** Upper bound on send attempts: the loop has no backoff and can run on a request thread. */
	private static final int MAX_SEND_ATTEMPTS_CEILING = 10;

	/**
	 * Worst-case per-send blocking above which the timeout / attempt-count combination is reported.
	 * The compiled-in 6.0 defaults come to 135s (see {@link #worstCaseSendDuration}), so a deployment
	 * that changes nothing stays silent.
	 */
	private static final Duration BLOCKING_BUDGET_WARN_THRESHOLD = Duration.ofSeconds(180);

	/**
	 * Blocking reads one SMTP conversation performs, used to scale {@code read-timeout} in the
	 * worst-case estimate. {@code mail.smtp.timeout} bounds an individual read, not the exchange: the
	 * greeting, EHLO, MAIL FROM, RCPT TO, DATA, the end-of-data dot and QUIT each block on their own
	 * response, so charging the read timeout once per attempt understated the figure several-fold.
	 */
	private static final int BLOCKING_READS_PER_SEND = 7;

	private static final String LEGAL_SECURITY_VALUES = "starttls|ssl|off";

	public SmtpProperties {
		if (email == null) {
			email = new Addresses(null, null, null);
		}
		if (port < 1 || port > 65535) {
			throw new IllegalArgumentException("entrystore.smtp.port must be between 1 and 65535, got " + port);
		}
		if (hasText(username) != hasText(password)) {
			// Report and carry on rather than abort, matching the pre-6.1 behaviour: a half-set pair
			// still delivered mail anonymously. A missing credential fails closed on its own, so there
			// is nothing to gain from taking the whole application down over it.
			//
			// The components are deliberately left as bound. hasCredentials() already requires both, so
			// nothing reaches the sender either way, and keeping them means /actuator/configprops still
			// shows the key as bound-and-discarded rather than as unbound — which would point an
			// operator at a binding problem instead of at the empty value they actually set.
			log.error("{} and {} must both be set; only one is, so SMTP authentication is disabled",
					USERNAME_KEY, PASSWORD_KEY);
		}
		requirePositive(connectionTimeout, "entrystore.smtp.connection-timeout");
		requirePositive(readTimeout, "entrystore.smtp.read-timeout");
		requirePositive(writeTimeout, "entrystore.smtp.write-timeout");
		if (maxSendAttempts < 1 || maxSendAttempts > MAX_SEND_ATTEMPTS_CEILING) {
			throw new IllegalArgumentException("entrystore.smtp.max-send-attempts must be between 1 and "
					+ MAX_SEND_ATTEMPTS_CEILING + ", got " + maxSendAttempts);
		}
		// Reported once here, at bind time, so effectiveSecurity() can stay pure and be called freely.
		reportTransportSecurity(host, security, ssl);
		reportBlockingBudget(connectionTimeout, readTimeout, writeTimeout, maxSendAttempts);
	}

	/**
	 * What the two transport-security keys resolve to, and which key decided it. The single source of
	 * the precedence rule: {@link #effectiveSecurity()}, {@link #securityIsUnresolved()} and
	 * {@link #reportTransportSecurity} all read it, so the rule cannot drift between the diagnostics
	 * and the value actually applied.
	 *
	 * @param value      the resolved setting, {@link SmtpSecurity#OFF} when neither key is set and when
	 *                   neither resolves
	 * @param unresolved true when a key was set but no key resolved, i.e. plaintext was never declared
	 */
	private record Resolution(SmtpSecurity value, boolean unresolved) {}

	private static Resolution resolve(@Nullable String security, @Nullable String ssl) {
		SmtpSecurity canonical = parseSecurity(security);
		if (canonical != null) {
			return new Resolution(canonical, false);
		}
		SmtpSecurity alias = parseSecurity(ssl);
		if (alias != null) {
			return new Resolution(alias, false);
		}
		return new Resolution(SmtpSecurity.OFF, hasText(security) || hasText(ssl));
	}

	/**
	 * Reports what was configured for the two transport-security keys. Only value problems are
	 * reported here; the deprecation of {@code entrystore.smtp.ssl} and the "configured host is on
	 * plaintext" warning belong to {@code MailConfiguration}, which knows whether the resolved value
	 * was declared or merely defaulted.
	 *
	 * <p>Silent when no host is configured: a stale value in a deployment that never sends mail would
	 * otherwise log ERROR on every boot, which ERROR-based alerting cannot tell apart from a live relay
	 * whose transport security is broken.
	 */
	private static void reportTransportSecurity(@Nullable String host, @Nullable String security,
												@Nullable String ssl) {
		if (!hasText(host)) {
			return;
		}
		SmtpSecurity canonical = parseSecurity(security);
		SmtpSecurity alias = parseSecurity(ssl);
		if (hasText(security) && canonical == null) {
			log.error("{}='{}' is not one of {} and cannot be applied; no mail will be sent while it stays "
							+ "set, rather than being sent unencrypted.",
					SECURITY_KEY, security, LEGAL_SECURITY_VALUES);
		}
		// Only reported when the canonical key did not already decide the outcome, so the migration
		// path the documentation prescribes — add `security` next to a stale `ssl` — stays quiet.
		if (canonical == null && hasText(ssl) && alias == null) {
			log.error("{}='{}' is not one of {} and cannot be applied; no mail will be sent while it stays "
					+ "set. Set {} instead.", SSL_KEY, ssl, LEGAL_SECURITY_VALUES, SECURITY_KEY);
		}
		if (canonical != null && alias != null && alias != canonical) {
			log.warn("{}={} and the deprecated {}={} disagree; {} wins. Remove {}.",
					SECURITY_KEY, security, SSL_KEY, ssl, SECURITY_KEY, SSL_KEY);
		}
	}

	/**
	 * Warns when the timeouts and attempt count multiply out to a long block. Each key is individually
	 * bounded, but their product is not: {@code connection-timeout=60s} with {@code max-send-attempts=10}
	 * passes every other check and still blocks for minutes.
	 *
	 * <p>Reported rather than rejected. Aborting the bind would make a mail-only misconfiguration a
	 * startup outage, which is the failure mode the credential handling above deliberately avoids.
	 */
	private static void reportBlockingBudget(Duration connectionTimeout, Duration readTimeout,
											 Duration writeTimeout, int maxSendAttempts) {
		Duration worstCase = worstCaseSendDuration(connectionTimeout, readTimeout, writeTimeout, maxSendAttempts);
		if (worstCase.compareTo(BLOCKING_BUDGET_WARN_THRESHOLD) > 0) {
			log.warn("entrystore.smtp.max-send-attempts={} combined with the configured timeouts "
							+ "({}/{}/{} connect/read/write) lets a single send block for at least {}s — the "
							+ "read timeout bounds each of the {} or so reads in one SMTP exchange, not the "
							+ "exchange — plus DNS resolution, which no timeout covers. Signup, POST /message "
							+ "and both password-change confirmations send on the request thread; password-reset "
							+ "mail occupies a passwordResetExecutor slot for the same duration.",
					maxSendAttempts, connectionTimeout, readTimeout, writeTimeout, worstCase.toSeconds(),
					BLOCKING_READS_PER_SEND);
		}
	}

	/**
	 * Parses either transport-security key, returning null when it is unset or unrecognised. Callers
	 * distinguish the two cases with {@link #hasText}; the diagnostics are emitted once at bind time by
	 * {@link #reportTransportSecurity}, so this stays side-effect-free.
	 *
	 * <p>Canonicalised the way Spring Boot's {@code LenientObjectToEnumConverterFactory} canonicalises,
	 * so every spelling that reached this enum while it was bound as an enum still resolves. Matching
	 * the constant name exactly would silently demote {@code ENTRYSTORE_SMTP_SECURITY=START_TLS} — the
	 * screaming-snake form relaxed binding accepts for every other key — to plaintext.
	 */
	private static @Nullable SmtpSecurity parseSecurity(@Nullable String value) {
		if (!hasText(value)) {
			return null;
		}
		String normalized = canonicalize(value);
		return Arrays.stream(SmtpSecurity.values())
				.filter(candidate -> canonicalize(candidate.name()).equals(normalized))
				.findFirst()
				.orElse(null);
	}

	private static String canonicalize(String value) {
		StringBuilder canonical = new StringBuilder(value.length());
		value.codePoints()
				.filter(Character::isLetterOrDigit)
				.map(Character::toLowerCase)
				.forEach(canonical::appendCodePoint);
		return canonical.toString();
	}

	private static void requirePositive(Duration value, String key) {
		if (value == null || value.isZero() || value.isNegative()) {
			throw new IllegalArgumentException(key + " must be positive, got " + value);
		}
	}

	private static boolean hasText(String value) {
		return value != null && !value.isBlank();
	}

	private static Duration worstCaseSendDuration(Duration connectionTimeout, Duration readTimeout,
												  Duration writeTimeout, int maxSendAttempts) {
		return connectionTimeout
				.plus(readTimeout.multipliedBy(BLOCKING_READS_PER_SEND))
				.plus(writeTimeout)
				.multipliedBy(maxSendAttempts);
	}

	/**
	 * The transport security actually applied: the canonical {@code entrystore.smtp.security} whenever
	 * it resolves, otherwise the deprecated {@code entrystore.smtp.ssl}, otherwise
	 * {@link SmtpSecurity#OFF}. This — not {@link #security()} or {@link #ssl()}, which return the raw
	 * configured strings — is what every call site must read.
	 *
	 * <p>{@link SmtpSecurity#OFF} here does not by itself mean "plaintext is fine": pair it with
	 * {@link #securityIsUnresolved()}, which separates a declared or defaulted plaintext relay from a
	 * value that could not be applied at all.
	 */
	public SmtpSecurity effectiveSecurity() {
		return resolve(security, ssl).value();
	}

	/**
	 * True when a transport-security key is set but neither key resolves, so plaintext was never
	 * declared — only arrived at by failing to understand the configuration. {@code EmailSender}
	 * refuses to send in that state: an operator who wrote {@code security=tls} believes the connection
	 * is encrypted, and quietly relaying password-reset links in the clear is the worse outcome.
	 */
	public boolean securityIsUnresolved() {
		return resolve(security, ssl).unresolved();
	}

	/**
	 * True when plaintext is what the operator asked for, as opposed to what was left unset. This is
	 * what lets {@code MailConfiguration}'s "transport security is off" warning be silenced by
	 * {@code entrystore.smtp.security=off}, exactly as that warning advertises.
	 */
	public boolean plaintextIsDeclared() {
		Resolution resolution = resolve(security, ssl);
		return resolution.value() == SmtpSecurity.OFF && !resolution.unresolved()
				&& (hasText(security) || hasText(ssl));
	}

	/**
	 * Worst-case blocking for one {@code sendMessage} call, excluding DNS resolution — which
	 * {@code mail.smtp.connectiontimeout} does not cover, so the real worst case is higher still.
	 * Package-private: this exists for the startup diagnostic and the test that pins its arithmetic,
	 * not as an API.
	 */
	Duration worstCaseSendDuration() {
		return worstCaseSendDuration(connectionTimeout, readTimeout, writeTimeout, maxSendAttempts);
	}

	/** True when the deprecated {@code entrystore.smtp.ssl} key is present, whatever its value. */
	public boolean usesDeprecatedSslKey() {
		return hasText(ssl);
	}

	/**
	 * Both credentials must be present, matching the pre-6.1 condition that decided between an
	 * authenticating and an anonymous session. A half-set pair is reported at bind time and left bound;
	 * requiring both here is what keeps it from reaching the sender, so this is false whenever
	 * authentication is not fully configured.
	 */
	public boolean hasCredentials() {
		return hasText(username) && hasText(password);
	}

	/** False when no SMTP host is configured, in which case no send is attempted at all. */
	public boolean isConfigured() {
		return hasText(host);
	}

	/**
	 * Bindings for {@code entrystore.smtp.email.*}. {@code from} and {@code bcc} fall back to the
	 * deprecated {@code entrystore.auth.email.*} keys — see {@code DeprecatedEmailAddressProperties}.
	 *
	 * <p>Deliberately carries no address-format invariant, unlike the fail-fast checks above for port and
	 * the timeouts. The deprecated fallback means the effective value is only known once both sources have
	 * been resolved, and rejecting a malformed address here would abort startup over a mail-only
	 * misconfiguration. {@code EmailSender}'s constructor validates each resolved address instead, logging
	 * an ERROR that names the key it actually came from.
	 */
	public record Addresses(String from, String bcc, String replyTo) {}

	public enum SmtpSecurity {
		/** Plaintext SMTP. */
		OFF,
		/** Upgrade an established plaintext connection, requiring the upgrade to succeed. */
		STARTTLS,
		/** Implicit TLS from connect (SMTPS). */
		SSL
	}
}
