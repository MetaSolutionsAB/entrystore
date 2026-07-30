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
 * warning at all. Both spellings bind; {@code security} wins whenever it is set, and {@code ssl} is
 * read only as a fallback. After construction the {@link #security()} component <em>is</em> the
 * effective value, so there is no accessor that can be read to get a stale answer.
 *
 * <p>The two keys are validated differently on purpose. {@code security} is new in 6.1, so an
 * unrecognised value there fails startup — matching the strict-boolean precedent set for
 * {@code entrystore.trust.x-forwarded-for}, and preventing the typo-degrades-to-plaintext failure the
 * old {@code equalsIgnoreCase} comparison allowed. {@code ssl} is bound as a plain {@code String} and
 * parsed leniently, because nothing ever read it: its value was unconstrained in every released
 * version, so a stale {@code entrystore.smtp.ssl=true} left in a properties file must not be able to
 * abort startup. An alias value outside {@code starttls|ssl|off} is reported at ERROR and resolves to
 * {@link SmtpSecurity#OFF} — exactly what that deployment already did, plus a diagnostic.
 *
 * <p>The timeouts and {@code check-server-identity} are configurable as of 6.1; the defaults here are
 * the values EntryStore 6.0 compiled in, so an existing deployment sees no change.
 */
@ConfigurationProperties(prefix = "entrystore.smtp")
public record SmtpProperties(
		String host,
		@DefaultValue("25") int port,
		SmtpSecurity security,
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

	/** Upper bound on send attempts: the loop has no backoff and runs on the request thread. */
	private static final int MAX_SEND_ATTEMPTS_CEILING = 10;

	public SmtpProperties {
		security = resolveSecurity(security, ssl);
		if (email == null) {
			email = new Addresses(null, null, null);
		}
		if (port < 1 || port > 65535) {
			throw new IllegalArgumentException("entrystore.smtp.port must be between 1 and 65535, got " + port);
		}
		if (hasText(username) != hasText(password)) {
			throw new IllegalArgumentException("entrystore.smtp.username and entrystore.smtp.password "
					+ "must both be set or both be unset");
		}
		requirePositive(connectionTimeout, "entrystore.smtp.connection-timeout");
		requirePositive(readTimeout, "entrystore.smtp.read-timeout");
		requirePositive(writeTimeout, "entrystore.smtp.write-timeout");
		if (maxSendAttempts < 1 || maxSendAttempts > MAX_SEND_ATTEMPTS_CEILING) {
			throw new IllegalArgumentException("entrystore.smtp.max-send-attempts must be between 1 and "
					+ MAX_SEND_ATTEMPTS_CEILING + ", got " + maxSendAttempts);
		}
	}

	/**
	 * Folds the canonical key and its deprecated alias into the effective transport security, so the
	 * {@code security} component carries the answer and no caller can read a stale one.
	 */
	private static SmtpSecurity resolveSecurity(SmtpSecurity security, String ssl) {
		SmtpSecurity alias = parseAlias(ssl);
		if (security == null) {
			return alias == null ? SmtpSecurity.OFF : alias;
		}
		if (alias != null && alias != security) {
			log.warn("{}={} and the deprecated {}={} disagree; {} wins. Remove {}.",
					SECURITY_KEY, security, SSL_KEY, ssl, SECURITY_KEY, SSL_KEY);
		}
		return security;
	}

	/**
	 * Parses the deprecated alias, returning null when it is unset. An unrecognised value is reported
	 * and treated as unset rather than throwing: the key was never read before 6.1, so its value was
	 * never constrained, and a stale entry must not be able to abort startup.
	 */
	private static SmtpSecurity parseAlias(String ssl) {
		if (ssl == null || ssl.isBlank()) {
			return null;
		}
		String normalized = ssl.trim().toLowerCase(Locale.ROOT);
		return Arrays.stream(SmtpSecurity.values())
				.filter(candidate -> candidate.name().toLowerCase(Locale.ROOT).equals(normalized))
				.findFirst()
				.orElseGet(() -> {
					log.error("{}='{}' is not one of starttls|ssl|off and has been ignored; transport "
							+ "security is off. Set {} instead.", SSL_KEY, ssl, SECURITY_KEY);
					return null;
				});
	}

	private static void requirePositive(Duration value, String key) {
		if (value == null || value.isZero() || value.isNegative()) {
			throw new IllegalArgumentException(key + " must be positive, got " + value);
		}
	}

	private static boolean hasText(String value) {
		return value != null && !value.isBlank();
	}

	/**
	 * The transport security actually applied. Equivalent to {@link #security()}, which the canonical
	 * constructor has already folded the deprecated alias into; kept as the name call sites read from.
	 */
	public SmtpSecurity effectiveSecurity() {
		return security;
	}

	/** True when the deprecated {@code entrystore.smtp.ssl} key is present, whatever its value. */
	public boolean usesDeprecatedSslKey() {
		return hasText(ssl);
	}

	/**
	 * Both credentials must be present, matching the pre-6.1 condition that decided between an
	 * authenticating and an anonymous session. The canonical constructor rejects a half-set pair, so
	 * this is false only when neither is configured.
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
