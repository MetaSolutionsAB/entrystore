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
import java.util.Locale;

/**
 * Bindings for {@code entrystore.smtp.*}, consumed by {@code MailConfiguration} to build the
 * {@code JavaMailSender} and by {@code EmailSender} for the unconfigured-SMTP guard and the default
 * From/Bcc/Reply-To addresses.
 *
 * <p><b>The transport-security key.</b> {@code entrystore.smtp.security} is canonical, but every
 * EntryStore release up to 6.0 documented {@code entrystore.smtp.ssl} instead — a key nothing ever
 * read. A deployment that followed the documentation was therefore sending plaintext SMTP with no
 * warning at all. Both spellings bind; {@code security} wins whenever it resolves, and {@code ssl} is
 * read only as a fallback. {@link #effectiveSecurity()} is the only accessor that answers "what
 * transport security is actually applied" — {@link #security()} and {@link #ssl()} return the raw
 * configured strings and must not be compared against directly.
 *
 * <p>Both keys are parsed leniently, and identically. Neither was validated before 6.1: {@code ssl}
 * was never read at all, and {@code security} — defined since 4.8 as {@code Settings.SMTP_SECURITY} —
 * was compared with {@code equalsIgnoreCase}, so any unrecognised value in <em>either</em> key
 * silently meant plaintext. Resolving an unrecognised value to {@link SmtpSecurity#OFF} therefore
 * preserves exactly what such a deployment already did, and adds the diagnostic it never had: the
 * offending value is reported at ERROR here, and {@code MailConfiguration} warns whenever a
 * configured host ends up on plaintext. Failing startup instead would turn a mail-only
 * misconfiguration into a total outage of the REST API.
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
	 * The compiled-in 6.0 defaults come to 45s, so a deployment that changes nothing stays silent.
	 */
	private static final Duration BLOCKING_BUDGET_WARN_THRESHOLD = Duration.ofSeconds(60);

	private static final String LEGAL_SECURITY_VALUES = "starttls|ssl|off";

	public SmtpProperties {
		if (email == null) {
			email = new Addresses(null, null, null);
		}
		if (port < 1 || port > 65535) {
			throw new IllegalArgumentException("entrystore.smtp.port must be between 1 and 65535, got " + port);
		}
		if (hasText(username) != hasText(password)) {
			// Degrade to an anonymous session rather than abort, matching the pre-6.1 behaviour: a
			// half-set pair still delivered mail. A missing credential fails closed on its own, so
			// there is nothing to gain from taking the whole application down over it.
			log.error("{} and {} must both be set; only one is, so SMTP authentication is disabled",
					USERNAME_KEY, PASSWORD_KEY);
			username = null;
			password = null;
		}
		requirePositive(connectionTimeout, "entrystore.smtp.connection-timeout");
		requirePositive(readTimeout, "entrystore.smtp.read-timeout");
		requirePositive(writeTimeout, "entrystore.smtp.write-timeout");
		if (maxSendAttempts < 1 || maxSendAttempts > MAX_SEND_ATTEMPTS_CEILING) {
			throw new IllegalArgumentException("entrystore.smtp.max-send-attempts must be between 1 and "
					+ MAX_SEND_ATTEMPTS_CEILING + ", got " + maxSendAttempts);
		}
		// Reported once here, at bind time, so effectiveSecurity() can stay pure and be called freely.
		reportTransportSecurity(security, ssl);
		reportBlockingBudget(connectionTimeout, readTimeout, writeTimeout, maxSendAttempts);
	}

	/**
	 * Reports what was configured for the two transport-security keys. Only value problems are
	 * reported here; the deprecation of {@code entrystore.smtp.ssl} and the "configured host is on
	 * plaintext" warning belong to {@code MailConfiguration}, which knows whether a host is set at all.
	 */
	private static void reportTransportSecurity(@Nullable String security, @Nullable String ssl) {
		SmtpSecurity canonical = parseSecurity(security);
		SmtpSecurity alias = parseSecurity(ssl);
		if (hasText(security) && canonical == null) {
			log.error("{}='{}' is not one of {} and has been ignored; transport security falls back to {} "
					+ "or off.", SECURITY_KEY, security, LEGAL_SECURITY_VALUES, SSL_KEY);
		}
		// Only reported when the canonical key did not already decide the outcome, so the migration
		// path the documentation prescribes — add `security` next to a stale `ssl` — stays quiet.
		if (canonical == null && hasText(ssl) && alias == null) {
			log.error("{}='{}' is not one of {} and has been ignored; transport security is off. Set {} "
					+ "instead.", SSL_KEY, ssl, LEGAL_SECURITY_VALUES, SECURITY_KEY);
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
	 */
	private static void reportBlockingBudget(Duration connectionTimeout, Duration readTimeout,
											 Duration writeTimeout, int maxSendAttempts) {
		Duration worstCase = worstCaseSendDuration(connectionTimeout, readTimeout, writeTimeout, maxSendAttempts);
		if (worstCase.compareTo(BLOCKING_BUDGET_WARN_THRESHOLD) > 0) {
			log.warn("entrystore.smtp.max-send-attempts={} combined with the configured timeouts "
							+ "({}/{}/{} connect/read/write) lets a single send block for up to {}s, plus DNS "
							+ "resolution. Signup and POST /message send on the request thread; password-reset "
							+ "mail occupies a passwordResetExecutor slot for the same duration.",
					maxSendAttempts, connectionTimeout, readTimeout, writeTimeout, worstCase.toSeconds());
		}
	}

	/**
	 * Parses either transport-security key, returning null when it is unset or unrecognised. Callers
	 * distinguish the two cases with {@link #hasText}; the diagnostics are emitted once at bind time by
	 * {@link #reportTransportSecurity}, so this stays side-effect-free.
	 */
	private static @Nullable SmtpSecurity parseSecurity(@Nullable String value) {
		if (!hasText(value)) {
			return null;
		}
		String normalized = value.trim().toLowerCase(Locale.ROOT);
		return Arrays.stream(SmtpSecurity.values())
				.filter(candidate -> candidate.name().toLowerCase(Locale.ROOT).equals(normalized))
				.findFirst()
				.orElse(null);
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
		return connectionTimeout.plus(readTimeout).plus(writeTimeout).multipliedBy(maxSendAttempts);
	}

	/**
	 * The transport security actually applied: the canonical {@code entrystore.smtp.security} whenever
	 * it resolves, otherwise the deprecated {@code entrystore.smtp.ssl}, otherwise
	 * {@link SmtpSecurity#OFF}. This — not {@link #security()} or {@link #ssl()}, which return the raw
	 * configured strings — is what every call site must read.
	 */
	public SmtpSecurity effectiveSecurity() {
		SmtpSecurity canonical = parseSecurity(security);
		if (canonical != null) {
			return canonical;
		}
		SmtpSecurity alias = parseSecurity(ssl);
		return alias != null ? alias : SmtpSecurity.OFF;
	}

	/** Worst-case blocking for one {@code sendMessage} call, excluding DNS resolution. */
	public Duration worstCaseSendDuration() {
		return worstCaseSendDuration(connectionTimeout, readTimeout, writeTimeout, maxSendAttempts);
	}

	/** True when the deprecated {@code entrystore.smtp.ssl} key is present, whatever its value. */
	public boolean usesDeprecatedSslKey() {
		return hasText(ssl);
	}

	/**
	 * Both credentials must be present, matching the pre-6.1 condition that decided between an
	 * authenticating and an anonymous session. The canonical constructor nulls out a half-set pair after
	 * reporting it, so this is false whenever authentication is not fully configured.
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
