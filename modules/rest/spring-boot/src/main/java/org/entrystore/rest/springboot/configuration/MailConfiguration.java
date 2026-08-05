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

import lombok.extern.slf4j.Slf4j;
import org.entrystore.rest.springboot.configuration.SmtpProperties.SmtpSecurity;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Properties;

/**
 * Builds the {@link JavaMailSender} from {@link SmtpProperties}.
 *
 * <p>Configured by hand rather than through {@code spring.mail.*} and Boot's
 * {@code MailSenderAutoConfiguration}, because the {@code entrystore.smtp.*} key names are
 * contractual — documented in {@code entrystore.properties_example}, live in operator deployments and
 * in {@code entrystore-it.properties} — and cannot be renamed. Bridging them onto {@code spring.mail.*}
 * would need an {@code EnvironmentPostProcessor} rewriting keys invisibly, duplicating the source of
 * truth in {@code /actuator/configprops}, and it still could not express the deprecated-alias fallback
 * or the "authenticate only when credentials exist" rule below. The autoconfiguration is inert either
 * way: its SMTP branch is conditional on {@code spring.mail.host}, which EntryStore never sets.
 *
 * <p>The bean is deliberately declared as the {@link JavaMailSender} interface rather than
 * {@link JavaMailSenderImpl}. Boot's {@code MailSenderValidatorAutoConfiguration} is
 * {@code @ConditionalOnSingleCandidate(JavaMailSenderImpl.class)}, so declaring the interface keeps
 * {@code spring.mail.test-connection} from ever opening an SMTP connection during startup. Narrowing
 * the return type would silently activate that.
 */
@Slf4j
@Configuration
public class MailConfiguration {

	@Bean
	JavaMailSender javaMailSender(SmtpProperties smtp) {
		JavaMailSenderImpl sender = new JavaMailSenderImpl();
		sender.setProtocol("smtp");
		sender.setDefaultEncoding(StandardCharsets.UTF_8.name());
		// Host and port live on the sender, which is the single source of truth: JavaMailSenderImpl
		// passes both to Transport.connect unconditionally. They are deliberately NOT mirrored into the
		// Jakarta Mail properties, so there is no second copy to drift. Note that Jakarta Mail defaults
		// a null host to localhost — what actually stops an unconfigured deployment from relaying via a
		// local MTA is EmailSender's isConfigured() guard, not the absence of the property.
		if (smtp.isConfigured()) {
			sender.setHost(smtp.host());
		}
		// Always explicit: JavaMailSenderImpl defaults to -1 ("protocol default"), where EntryStore 6.0
		// defaulted to 25.
		sender.setPort(smtp.port());

		Properties props = sender.getJavaMailProperties();
		if (smtp.hasCredentials()) {
			sender.setUsername(smtp.username());
			sender.setPassword(smtp.password());
			// JavaMailSenderImpl never writes mail.smtp.auth itself. Setting it does not force
			// authentication — Jakarta Mail attempts AUTH whenever credentials are present and the
			// server advertises it — but it makes the connect fail fast if a credential is missing,
			// which is what EntryStore 6.0's Session did.
			props.put("mail.smtp.auth", "true");
		}
		props.put("mail.smtp.ssl.checkserveridentity", Boolean.toString(smtp.checkServerIdentity()));
		props.put("mail.smtp.connectiontimeout", Long.toString(smtp.connectionTimeout().toMillis()));
		props.put("mail.smtp.timeout", Long.toString(smtp.readTimeout().toMillis()));
		props.put("mail.smtp.writetimeout", Long.toString(smtp.writeTimeout().toMillis()));
		props.putAll(transportSecurityProperties(smtp.effectiveSecurity(), smtp.port()));

		logConfiguration(smtp);
		return sender;
	}

	/**
	 * A switch <em>expression</em> rather than a statement: Java requires an expression to be
	 * exhaustive, so adding an {@link SmtpSecurity} constant without handling it here fails compilation.
	 * The arrow-form statement this replaced compiled fine and wrote no TLS properties at all, which
	 * would have been a silent downgrade to plaintext.
	 */
	private static Map<String, String> transportSecurityProperties(SmtpSecurity security, int port) {
		return switch (security) {
			case SSL -> Map.of(
					"mail.smtp.ssl.enable", "true",
					"mail.smtp.socketFactory.port", Integer.toString(port),
					"mail.smtp.socketFactory.class", "javax.net.ssl.SSLSocketFactory",
					// Without this a failed TLS handshake silently retries in the clear.
					"mail.smtp.socketFactory.fallback", "false");
			case STARTTLS -> Map.of(
					"mail.smtp.starttls.enable", "true",
					// Without this a server that does not offer STARTTLS is used in the clear.
					"mail.smtp.starttls.required", "true");
			// Plaintext: no TLS properties, as when neither key is configured.
			case OFF -> Map.of();
		};
	}

	private static void logConfiguration(SmtpProperties smtp) {
		if (!smtp.isConfigured()) {
			log.warn("SMTP is not configured (entrystore.smtp.host is unset); no email will be sent");
			return;
		}

		SmtpSecurity security = smtp.effectiveSecurity();
		log.info("SMTP configured: host={} port={} security={} authenticated={}",
				smtp.host(), smtp.port(), security, smtp.hasCredentials());
		if (smtp.usesDeprecatedSslKey()) {
			log.warn("entrystore.smtp.ssl is deprecated; rename it to entrystore.smtp.security");
		}
		if (smtp.securityIsUnresolved()) {
			// The value problem itself is already reported by SmtpProperties at bind time. What belongs
			// here is the consequence, which only this class knows: a host is configured, so this
			// deployment does intend to send mail, and no mail will go out until the value is corrected.
			log.error("SMTP transport security could not be resolved, so no mail will be sent to {} at all "
					+ "— sending unencrypted under a configuration meant to be encrypted is not an option. "
					+ "Correct entrystore.smtp.security, or set it to off to send in the clear deliberately.",
					smtp.host());
		} else if (security == SmtpSecurity.OFF && !smtp.plaintextIsDeclared()) {
			// Fires for plaintext the operator never asked for — neither key set — and not for
			// entrystore.smtp.security=off, which is what makes the advice below true. An unclearable
			// warning is a warning that gets filtered, and filtering this logger also loses the
			// deprecated-key, cleartext-credentials and check-server-identity warnings around it.
			log.warn("SMTP transport security is off, so mail — including password-reset links — crosses "
					+ "the network unencrypted. Set entrystore.smtp.security=starttls unless the MTA is "
					+ "on loopback, or =off explicitly to declare plaintext and silence this.");
		}
		if (security == SmtpSecurity.OFF && smtp.hasCredentials()) {
			// Deliberately independent of whether plaintext was declared, and deliberately not silenceable
			// by declaring it: entrystore.smtp.security=off says the operator accepts unencrypted mail, not
			// that they accept SMTP AUTH LOGIN putting the relay password itself on the wire in the clear.
			log.warn("SMTP credentials are configured with transport security off, so the SMTP AUTH exchange "
					+ "sends entrystore.smtp.username and entrystore.smtp.password across the network in "
					+ "the clear. Use entrystore.smtp.security=starttls, or drop the credentials if the "
					+ "relay does not need them.");
		}
		if (!smtp.checkServerIdentity() && security != SmtpSecurity.OFF) {
			log.warn("entrystore.smtp.check-server-identity=false disables TLS certificate hostname "
					+ "verification, which leaves the connection open to an active man-in-the-middle");
		}
	}
}
