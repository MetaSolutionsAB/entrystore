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

package org.entrystore.rest.resources;

import org.entrystore.repository.config.Config;
import org.entrystore.repository.config.Settings;
import org.entrystore.repository.security.AuthorizationException;
import org.entrystore.rest.auth.CookieVerifier;
import org.restlet.data.Form;
import org.restlet.data.MediaType;
import org.restlet.data.Status;
import org.restlet.ext.openid.RedirectAuthenticator;
import org.restlet.representation.Representation;
import org.restlet.representation.StringRepresentation;
import org.restlet.resource.Get;
import org.restlet.resource.Post;
import org.restlet.resource.ResourceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.mail.Message;
import javax.mail.MessagingException;
import javax.mail.PasswordAuthentication;
import javax.mail.Session;
import javax.mail.Transport;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeMessage;
import java.io.File;
import java.io.IOException;
import java.io.StringReader;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.Security;
import java.util.Properties;


/**
 * Resource to handle manual signups.
 * 
 * @author Hannes Ebner
 */
public class SignupResource extends BaseResource {
	
	private static Logger log = LoggerFactory.getLogger(SignupResource.class);

	@Get
	public Representation represent() throws ResourceException {
		if (!parameters.containsKey("confirm")) {
			getResponse().setStatus(Status.CLIENT_ERROR_UNAUTHORIZED);
			return null;
		}

		// TODO lookup confirmation-hash in Map

		// TODO iterate through Map and clean from values older than 24 hours (make this configurable in properties file)

		// TODO redirect to urlFailure or create user and redirect to urlSuccess

		try {
			// FIXME
			getResponse().redirectTemporary(URLDecoder.decode(parameters.get("redirectOnSuccess"), "UTF-8"));
			return null;
		} catch (UnsupportedEncodingException e) {
			log.warn("Unable to decode URL: " + e.getMessage());
		}

		getResponse().setStatus(Status.CLIENT_ERROR_BAD_REQUEST);
		return new StringRepresentation("User confirmation failed");
	}

	@Post
	public void acceptRepresentation(Representation r) {
		Form form = new Form(getRequest().getEntity());

		String firstName = form.getFirstValue("firstname", true);
		String lastName = form.getFirstValue("lastname", true);
		String email = form.getFirstValue("email", true);
		String password = form.getFirstValue("password", true);
		String reCaptcha = form.getFirstValue("recaptcha", true);

		if (firstName == null || lastName == null || email == null || password == null) {
			getResponse().setStatus(Status.CLIENT_ERROR_BAD_REQUEST, "One or more parameters are missing");
			return;
		}

		if (getRM().getConfiguration().getBoolean(Settings.SIGNUP_RECAPTCHA, false)) {
			if (reCaptcha == null) {
				getResponse().setStatus(Status.CLIENT_ERROR_BAD_REQUEST, "One or more parameters are missing");
				return;
			}

			// TODO check reCaptcha, return bad request if wrong
		}

		// TODO create confirmation ID (SHA-hash) and add to HashMap
		// (key: hash, value: e-mail, date, urlSuccess, urlFailure)

		sendRequestForConfirmation(email, "confToken"); // FIXME

		getResponse().setEntity(new StringRepresentation("HTML HERE"));
	}

	private boolean sendRequestForConfirmation(String recipient, String confirmationToken) {
		String domain = getRM().getRepositoryURL().getHost();
		String confirmationLink = getRM().getRepositoryURL().toExternalForm() + "auth/signup?confirm=" + confirmationToken;

		Config config = getRM().getConfiguration();
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

	static String readFile(String path, Charset encoding) {
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