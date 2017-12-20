/*
 * Copyright (c) 2007-2017 MetaSolutions AB
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

package org.entrystore.rest.auth;

import org.entrystore.Entry;
import org.entrystore.config.Config;
import org.entrystore.repository.config.ConfigurationManager;
import org.entrystore.repository.config.Settings;
import org.entrystore.repository.util.NS;
import org.openrdf.model.Graph;
import org.openrdf.model.ValueFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.mail.Message;
import javax.mail.MessagingException;
import javax.mail.PasswordAuthentication;
import javax.mail.Session;
import javax.mail.Transport;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeMessage;
import javax.security.auth.login.Configuration;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Calendar;
import java.util.Date;
import java.util.Properties;

/**
 * @author Hannes Ebner
 */
public class Signup {

	private static Logger log = LoggerFactory.getLogger(Signup.class);

	public static boolean sendRequestForConfirmation(Config config, String recipientName, String recipientEmail, String confirmationLink, boolean resetPassword) {
		String domain = URI.create(confirmationLink).getHost();
		String host = config.getString(Settings.SMTP_HOST);
		int port = config.getInt(Settings.SMTP_PORT, 25);
		boolean ssl = "ssl".equalsIgnoreCase(config.getString(Settings.SMTP_SECURITY));
		boolean starttls = "starttls".equalsIgnoreCase(config.getString(Settings.SMTP_SECURITY));
		final String username = config.getString(Settings.SMTP_USERNAME);
		final String password = config.getString(Settings.SMTP_PASSWORD);
		String from = config.getString(Settings.AUTH_FROM_EMAIL, "support@" + domain);
		String bcc = config.getString(Settings.AUTH_BCC_EMAIL);

		String subject = null;
		if (resetPassword) {
			subject = config.getString(Settings.PASSWORD_RESET_SUBJECT, "Password reset request");
		} else {
			subject = config.getString(Settings.SIGNUP_SUBJECT, "User sign-up request");
		}

		String templatePath = null;
		if (resetPassword) {
			templatePath = config.getString(Settings.PASSWORD_RESET_CONFIRMATION_MESSAGE_TEMPLATE_PATH);
			if (templatePath == null) {
				templatePath = new File(ConfigurationManager.getConfigurationURI("email_pwreset.html")).getAbsolutePath();
			}
		} else {
			templatePath = config.getString(Settings.SIGNUP_CONFIRMATION_MESSAGE_TEMPLATE_PATH);
			if (templatePath == null) {
				templatePath = new File(ConfigurationManager.getConfigurationURI("email_signup.html")).getAbsolutePath();
			}
		}

		if (host == null) {
			log.error("No SMTP host configured");
			return false;
		}

		Session session = null;
		Properties props = new Properties();
		props.put("mail.smtp.host", host);
		props.put("mail.smtp.port", port);

		// SSL/TLS-related settings
		if (ssl) {
			log.info("SSL enabled");
			props.put("mail.smtp.ssl.enable", "true");
			props.put("mail.smtp.socketFactory.port", port);
			props.put("mail.smtp.socketFactory.class", "javax.net.ssl.SSLSocketFactory");
			props.put("mail.smtp.socketFactory.fallback", "false");
		}
		if (starttls) {
			log.info("StartTLS enabled");
			props.put("mail.smtp.starttls.enable", "true");
			props.put("mail.smtp.starttls.required", "true");
		}

		// other options, to be made configurable at some later point
		props.put("mail.smtp.ssl.checkserveridentity", "true"); // default false
		props.put("mail.smtp.connectiontimeout", "30000"); // default infinite
		props.put("mail.smtp.timeout", "30000"); // default infinite
		props.put("mail.smtp.writetimeout", "30000"); // default infinite

		//props.put("mail.debug", "true");

		// Authentication
		if (username != null && password != null) {
			props.put("mail.smtp.auth", "true");
			session = Session.getDefaultInstance(props, new javax.mail.Authenticator() {
				protected PasswordAuthentication getPasswordAuthentication() {
					return new PasswordAuthentication(username, password);
				}
			});
		} else {
			session = Session.getDefaultInstance(props);
		}

		try {
			MimeMessage message = new MimeMessage(session);
			message.setFrom(new InternetAddress(from));
			if (bcc != null) {
				message.addRecipients(Message.RecipientType.BCC, InternetAddress.parse(bcc));
			}
			message.addRecipients(Message.RecipientType.TO, InternetAddress.parse(recipientEmail));
			message.setSubject(subject);

			String templateHTML = null;
			if (templatePath != null) {
				log.debug("Loading email template from " + templatePath);
				templateHTML = readFile(templatePath, Charset.defaultCharset());
			}
			if (templateHTML == null) {
				log.debug("Unable to load email template, falling back to inline creation");
				StringBuilder sb = new StringBuilder();
				sb.append("<html><body style=\"font-family:verdana;font-size:10pt;\"><div><br/>");
				sb.append("<h3>Email address confirmation necessary</h3>");
				if (resetPassword) {
					sb.append("<p>We received a password reset request for:</p>");
				} else {
					sb.append("<p>You signed up with the following information:</p>");
				}
				sb.append("<p>");
				if (recipientName != null) {
					sb.append("Name: __NAME__<br/>");
				}
				sb.append("Email: __EMAIL__");
				sb.append("</p>");
				if (resetPassword) {
					sb.append("<p>To reset your password, you need to follow <a href=\"__CONFIRMATION_LINK__\">this link</a> ");
					sb.append("or copy/paste<br/>the URL below into a web browser.</p>");
				} else {
					sb.append("<p>To complete the sign-up process, you need to follow <a href=\"__CONFIRMATION_LINK__\">this link</a> ");
					sb.append("or copy/paste<br/>the URL below into a web browser to confirm that you own the email address<br/>you ");
					sb.append("used to set up an account.</p>");
				}
				sb.append("<p><pre>__CONFIRMATION_LINK__</pre></p>");
				sb.append("<p>The link is valid for 24 hours.</p><br/>");
				sb.append("<div style=\"border-top:1px solid #e5e5e5;\"><p><small>&copy; __YEAR__ <a href=\"http://metasolutions.se\" style=\"text-decoration:none;\">MetaSolutions AB</a></small></p></div>");
				sb.append("</div></body></html>");
				templateHTML = sb.toString();
			}
			String messageText = templateHTML;
			messageText = messageText.replaceAll("__YEAR__", Integer.toString(Calendar.getInstance().get(Calendar.YEAR)));
			if (confirmationLink != null) {
				messageText = messageText.replaceAll("__CONFIRMATION_LINK__", confirmationLink);
			}
			if (recipientName != null) {
				messageText = messageText.replaceAll("__NAME__", recipientName);
			}
			if (recipientEmail != null) {
				messageText = messageText.replaceAll("__EMAIL__", recipientEmail);
			}
			message.setText(messageText, "utf-8", "html");

			Transport.send(message);
		} catch (MessagingException e) {
			log.error(e.getMessage());
			return false;
		}

		return true;
	}

	public static void setFoafMetadata(Entry entry, org.restlet.security.User userInfo) {
		Graph graph = entry.getLocalMetadata().getGraph();
		ValueFactory vf = graph.getValueFactory();
		org.openrdf.model.URI resourceURI = vf.createURI(entry.getResourceURI().toString());
		String fullname = null;
		if (userInfo.getFirstName() != null) {
			fullname = userInfo.getFirstName();
			graph.add(vf.createStatement(resourceURI, vf.createURI(NS.foaf, "givenName"), vf.createLiteral(userInfo.getFirstName())));
		}
		if (userInfo.getLastName() != null) {
			if (fullname != null) {
				fullname = fullname + " " + userInfo.getLastName();
			} else {
				fullname = userInfo.getLastName();
			}
			graph.add(vf.createStatement(resourceURI, vf.createURI(NS.foaf, "familyName"), vf.createLiteral(userInfo.getLastName())));
		}
		if (fullname != null) {
			graph.add(vf.createStatement(resourceURI, vf.createURI(NS.foaf, "name"), vf.createLiteral(fullname)));
		}
		if (userInfo.getEmail() != null) {
			graph.add(vf.createStatement(resourceURI, vf.createURI(NS.foaf, "mbox"), vf.createURI("mailto:", userInfo.getEmail())));
		}

		entry.getLocalMetadata().setGraph(graph);
	}

	private static String readFile(String path, Charset encoding) {
		if (path == null || !new File(path).exists()) {
			return null;
		}
		byte[] encoded = new byte[0];
		try {
			encoded = Files.readAllBytes(Paths.get(path));
		} catch (IOException e) {
			log.error(e.getMessage());
			return null;
		}
		return encoding.decode(ByteBuffer.wrap(encoded)).toString();
	}

}