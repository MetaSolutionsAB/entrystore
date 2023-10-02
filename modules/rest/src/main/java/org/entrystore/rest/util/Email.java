/*
 * Copyright (c) 2007-2018 MetaSolutions AB
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

package org.entrystore.rest.util;

import com.google.common.base.Charsets;
import com.google.common.html.HtmlEscapers;
import org.apache.commons.io.IOUtils;
import org.entrystore.Entry;
import org.entrystore.User;
import org.entrystore.config.Config;
import org.entrystore.repository.config.ConfigurationManager;
import org.entrystore.repository.config.Settings;
import org.entrystore.repository.util.EntryUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.mail.Message;
import javax.mail.MessagingException;
import javax.mail.PasswordAuthentication;
import javax.mail.Session;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeMessage;
import javax.mail.internet.MimeUtility;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.util.Calendar;
import java.util.Properties;

/**
 * Helper class for sending emails.
 *
 * @author Hannes Ebner
 */
public class Email {

	private static Logger log = LoggerFactory.getLogger(Email.class);

	private static String messageBodySignup;

	private static String messageBodyPasswordReset;

	private static String messageBodyPasswordChanged;

	public static boolean sendMessage(Config config, String msgTo, String msgSubject, String msgBody) {
		return sendMessage(config, msgTo, msgSubject, msgBody, null, null);
	}

	public static boolean sendMessage(Config config, String msgTo, String msgSubject, String msgBody, String msgFrom, String msgReplyTo) {
		if (msgFrom == null || msgFrom.isEmpty()) {
			msgFrom = config.getString(Settings.SMTP_EMAIL_FROM);
			if (msgFrom == null) {
				msgFrom = config.getString(Settings.AUTH_FROM_EMAIL_DEPRECATED); // fallback to deprecated setting
			}
		}
		String msgBcc = config.getString(Settings.SMTP_EMAIL_BCC);
		if (msgBcc == null) {
			msgBcc = config.getString(Settings.AUTH_BCC_EMAIL_DEPRECATED); // fallback to deprecated setting
		}
		if (msgReplyTo == null || msgReplyTo.isEmpty()) {
			msgReplyTo = config.getString(Settings.SMTP_EMAIL_REPLYTO);
		}
		String host = config.getString(Settings.SMTP_HOST);
		int port = config.getInt(Settings.SMTP_PORT, 25);
		boolean ssl = "ssl".equalsIgnoreCase(config.getString(Settings.SMTP_SECURITY));
		boolean starttls = "starttls".equalsIgnoreCase(config.getString(Settings.SMTP_SECURITY));
		final String username = config.getString(Settings.SMTP_USERNAME);
		final String password = config.getString(Settings.SMTP_PASSWORD);

		Properties props = new Properties();

		props.put("mail.transport.protocol", "smtp");
		props.put("mail.smtp.host", host);
		props.put("mail.smtp.port", port);

		// SSL/TLS-related settings
		if (ssl) {
			log.debug("SSL enabled");
			props.put("mail.smtp.ssl.enable", "true");
			props.put("mail.smtp.socketFactory.port", port);
			props.put("mail.smtp.socketFactory.class", "javax.net.ssl.SSLSocketFactory");
			props.put("mail.smtp.socketFactory.fallback", "false");
		}
		if (starttls) {
			log.debug("StartTLS enabled");
			props.put("mail.smtp.starttls.enable", "true");
			props.put("mail.smtp.starttls.required", "true");
		}

		// other options, to be made configurable at some later point
		props.put("mail.smtp.ssl.checkserveridentity", "true"); // default false
		props.put("mail.smtp.connectiontimeout", "5000"); // default infinite
		props.put("mail.smtp.timeout", "5000"); // default infinite
		props.put("mail.smtp.writetimeout", "5000"); // default infinite

		//props.put("mail.debug", "true");

		Session session = null;

		// Authentication
		if (username != null && password != null) {
			props.put("mail.smtp.auth", "true");
			session = Session.getInstance(props, new javax.mail.Authenticator() {
				protected PasswordAuthentication getPasswordAuthentication() {
					return new PasswordAuthentication(username, password);
				}
			});
		} else {
			session = Session.getInstance(props);
		}

		try {
			MimeMessage message = new MimeMessage(session);
			message.setFrom(new InternetAddress(msgFrom));
			if (msgReplyTo != null) {
				message.setReplyTo(InternetAddress.parse(msgReplyTo));
			}
			if (msgBcc != null) {
				message.addRecipients(Message.RecipientType.BCC, InternetAddress.parse(msgBcc));
			}
			message.addRecipients(Message.RecipientType.TO, InternetAddress.parse(msgTo));
			if (msgSubject.toLowerCase().startsWith("=?utf-8?")) {
				message.setHeader("Subject", MimeUtility.fold(9, msgSubject));
			} else {
				message.setSubject(msgSubject, "UTF-8");
			}
			message.setText(msgBody, "UTF-8", "html");

			int failure = 0;
			while (failure < 3) { // we try three times
				try {
					session.getTransport().send(message);
					return true;
				} catch (MessagingException me) {
					log.error(me.getMessage());
					failure++;
				}
			}
		} catch (MessagingException e) {
			log.error(e.getMessage());
			return false;
		}

		return false;
	}

	public static boolean sendSignupConfirmation(Config config, String recipientName, String recipientEmail, String confirmationLink) {
		String subject = config.getString(Settings.SIGNUP_SUBJECT, "User sign-up request");

		String templatePath = config.getString(Settings.SIGNUP_CONFIRMATION_MESSAGE_TEMPLATE_PATH);
		if (messageBodySignup == null) {
			if (templatePath == null) {
				templatePath = new File(ConfigurationManager.getConfigurationURI("email_signup.html")).getAbsolutePath();
			}
			if (templatePath != null) {
				messageBodySignup = loadTemplate(templatePath);
			}
		}

		if (messageBodySignup == null) {
			log.error("Unable to load email template for sign-up confirmation");
			return false;
		}

		String messageText = messageBodySignup.replaceAll("__YEAR__", Integer.toString(Calendar.getInstance().get(Calendar.YEAR)));
		messageText = messageText.replaceAll("__DOMAIN__", URI.create(config.getString(Settings.BASE_URL)).getHost());
		if (confirmationLink != null) {
			messageText = messageText.replaceAll("__CONFIRMATION_LINK__", confirmationLink);
		}
		if (recipientName != null) {
			// we escape the name because it could contain content (e.g. HTML) that cause trouble if displayed in e-mails
			messageText = messageText.replaceAll("__NAME__", HtmlEscapers.htmlEscaper().escape(recipientName));
		}
		if (recipientEmail != null) {
			// we escape the name because it could contain content (e.g. HTML) that cause trouble if displayed in e-mails
			messageText = messageText.replaceAll("__EMAIL__", HtmlEscapers.htmlEscaper().escape(recipientEmail));
		}

		return sendMessage(config, recipientEmail, subject, messageText);
	}

	public static boolean sendPasswordResetConfirmation(Config config, String recipientEmail, String confirmationLink) {
		String subject = config.getString(Settings.PASSWORD_RESET_SUBJECT, "Password reset request");

		if (messageBodyPasswordReset == null) {
			String templatePath = config.getString(Settings.PASSWORD_RESET_CONFIRMATION_MESSAGE_TEMPLATE_PATH);
			if (templatePath == null) {
				templatePath = new File(ConfigurationManager.getConfigurationURI("email_pwreset.html")).getAbsolutePath();
			}
			if (templatePath != null) {
				messageBodyPasswordReset = loadTemplate(templatePath);
			}
		}

		if (messageBodyPasswordReset == null) {
			log.error("Unable to load email template for sign-up confirmation");
			return false;
		}

		String messageText = messageBodyPasswordReset.replaceAll("__YEAR__", Integer.toString(Calendar.getInstance().get(Calendar.YEAR)));
		messageText = messageText.replaceAll("__DOMAIN__", URI.create(config.getString(Settings.BASE_URL)).getHost());
		if (confirmationLink != null) {
			messageText = messageText.replaceAll("__CONFIRMATION_LINK__", confirmationLink);
		}
		if (recipientEmail != null) {
			messageText = messageText.replaceAll("__EMAIL__", HtmlEscapers.htmlEscaper().escape(recipientEmail));
		}

		return sendMessage(config, recipientEmail, subject, messageText);
	}

	public static boolean sendPasswordChangeConfirmation(Config config, Entry userEntry) {
		String msgTo = ((User) userEntry.getResource()).getName();
		if (!msgTo.contains("@")) {
			msgTo = EntryUtil.getEmail(userEntry);
		}

		if (msgTo == null || !msgTo.contains("@")) {
			log.warn("Unable to send email, invalid email address of recipient: " + msgTo);
			return false;
		}

		if (messageBodyPasswordChanged == null) {
			String templatePath = config.getString(Settings.PASSWORD_CHANGE_CONFIRMATION_MESSAGE_TEMPLATE_PATH);
			if (templatePath == null) {
				templatePath = new File(ConfigurationManager.getConfigurationURI("email_pwchange.html")).getAbsolutePath();
			}
			if (templatePath != null) {
				messageBodyPasswordChanged = loadTemplate(templatePath);
			}
		}

		if (messageBodyPasswordChanged == null) {
			log.error("Unable to load email template for password change confirmation");
			return false;
		}

		String messageText = messageBodyPasswordChanged.replaceAll("__YEAR__", Integer.toString(Calendar.getInstance().get(Calendar.YEAR)));
		messageText = messageText.replaceAll("__DOMAIN__", URI.create(config.getString(Settings.BASE_URL)).getHost());
		String msgSubject = config.getString(Settings.PASSWORD_CHANGE_SUBJECT, "Your password has been changed");
		String recipientName = EntryUtil.getName(userEntry);
		if (recipientName == null) {
			recipientName = "";
		}
		messageText = messageText.replaceAll("__NAME__", HtmlEscapers.htmlEscaper().escape(recipientName));

		return sendMessage(config, msgTo, msgSubject, messageText);
	}

	private static String loadTemplate(String url) {
		if (url == null) {
			return null;
		}
		log.debug("Loading template from " + url);
		InputStream is = null;
		try {
			if (url.startsWith("http://") || url.startsWith("https://")) {
				is = new URL(url).openStream();
			} else {
				is = Files.newInputStream(new File(url).toPath());
			}
			return IOUtils.toString(is, Charsets.UTF_8);
		} catch (IOException e) {
			log.error(e.getMessage());
		} finally {
			if (is != null) {
				try {
					is.close();
				} catch (IOException e) {
					log.warn(e.getMessage());
				}
			}
		}

		return null;
	}

}