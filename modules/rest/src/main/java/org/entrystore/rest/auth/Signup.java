/*
 * Copyright (c) 2007-2014
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

import org.entrystore.repository.Entry;
import org.entrystore.repository.RepositoryManager;
import org.entrystore.repository.config.Config;
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
import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

/**
 * @author Hannes Ebner
 */
public class Signup {

	private static Logger log = LoggerFactory.getLogger(Signup.class);

	private static Signup instance;

	private Map<String, ConfirmationInfo> tokenCache;

	private Signup() {
		tokenCache = Collections.synchronizedMap(new HashMap<String, ConfirmationInfo>());
	}

	public synchronized static Signup getInstance() {
		if (instance == null) {
			instance = new Signup();
		}
		return instance;
	}

	public Map<String, ConfirmationInfo> getTokenCache() {
		cleanup();
		return tokenCache;
	}

	private synchronized void cleanup() {
		for (Map.Entry<String, ConfirmationInfo> e : tokenCache.entrySet()) {
			if (e.getValue().expirationDate.before(new Date())) {
				tokenCache.remove(e.getKey());
			}
		}
	}

	public boolean sendRequestForConfirmation(Config config, String recipient, String confirmationLink) {
		String domain = URI.create(confirmationLink).getHost();
		String host = config.getString(Settings.SMTP_HOST);
		int port = config.getInt(Settings.SMTP_PORT, 25);
		boolean ssl = config.getBoolean(Settings.SMTP_SSL, false);
		final String username = config.getString(Settings.SMTP_USERNAME);
		final String password = config.getString(Settings.SMTP_PASSWORD);
		String from = config.getString(config.getString(Settings.SIGNUP_FROM_EMAIL), "signup@" + domain);
		String subject = config.getString(Settings.SIGNUP_SUBJECT, "Confirm your e-mail address to complete signup at " + domain);
		String templatePath = config.getString(Settings.SIGNUP_CONFIRMATION_MESSAGE_TEMPLATE_PATH);

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
			props.put("mail.smtp.ssl.enable", "true");
			props.put("mail.smtp.socketFactory.class", "javax.net.ssl.SSLSocketFactory");
			props.put("mail.smtp.socketFactory.fallback", "false");
		}

		// other options, to be made configurable at some later point
		props.put("mail.smtp.starttls.enable", "true");
		props.put("mail.smtp.starttls.required", "false"); // default false
		props.put("mail.smtp.ssl.checkserveridentity", "true"); // default false
		props.put("mail.smtp.connectiontimeout", "30000"); // default infinite
		props.put("mail.smtp.timeout", "30000"); // default infinite
		props.put("mail.smtp.writetimeout", "30000"); // default infinite

		// Security.addProvider(new com.sun.net.ssl.internal.ssl.Provider()); // why?

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
			message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(recipient));
			message.setSubject(subject);

			String templateHTML = readFile(templatePath, Charset.defaultCharset());
			if (templateHTML != null) {
				message.setText(templateHTML, "utf-8", "html");
			} else {
				message.setText("To confirm your e-mail address and complete the signup procedure please visit <a href=\"" + confirmationLink + "\">this URL</a>", "utf-8", "html");
			}

			Transport.send(message);
		} catch (MessagingException e) {
			log.error(e.getMessage());
			return false;
		}

		return true;
	}

	public void setFoafMetadata(Entry entry, org.restlet.security.User userInfo) {
		Graph graph = entry.getLocalMetadata().getGraph();
		ValueFactory vf = graph.getValueFactory();
		org.openrdf.model.URI resourceURI = vf.createURI(entry.getResourceURI().toString());
		String fullname = null;
		if (userInfo.getFirstName() != null) {
			fullname = userInfo.getFirstName();
			graph.add(vf.createStatement(resourceURI, vf.createURI(NS.foaf, "givenName"), vf.createLiteral(userInfo.getFirstName())));
			graph.add(vf.createStatement(resourceURI, vf.createURI(NS.foaf, "firstName"), vf.createLiteral(userInfo.getFirstName())));
		}
		if (userInfo.getLastName() != null) {
			if (fullname != null) {
				fullname = fullname + " " + userInfo.getLastName();
			} else {
				fullname = userInfo.getLastName();
			}
			graph.add(vf.createStatement(resourceURI, vf.createURI(NS.foaf, "familyName"), vf.createLiteral(userInfo.getLastName())));
			graph.add(vf.createStatement(resourceURI, vf.createURI(NS.foaf, "lastName"), vf.createLiteral(userInfo.getLastName())));
		}
		if (fullname != null) {
			graph.add(vf.createStatement(resourceURI, vf.createURI(NS.foaf, "name"), vf.createLiteral(fullname)));
		}
		if (userInfo.getEmail() != null) {
			graph.add(vf.createStatement(resourceURI, vf.createURI(NS.foaf, "mbox"), vf.createURI("mailto:", userInfo.getEmail())));
		}

		entry.getLocalMetadata().setGraph(graph);
	}

	private String readFile(String path, Charset encoding) {
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