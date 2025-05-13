package org.entrystore.rest.resources;

import org.entrystore.rest.util.Email;
import org.json.JSONObject;
import org.restlet.representation.Representation;
import org.restlet.resource.Post;
import org.restlet.resource.ResourceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

import static org.restlet.data.Status.CLIENT_ERROR_BAD_REQUEST;
import static org.restlet.data.Status.CLIENT_ERROR_FORBIDDEN;

public class MessageResource extends BaseResource {

	private static final Logger log = LoggerFactory.getLogger(MessageResource.class);

	@Post
	public void sendMessage(Representation representation) throws ResourceException {
		if (getPM().currentUserIsGuest()) {
			unauthorizedPOST();
			return;
		}

		String transportType;
		String subject;
		String to;
		String body;
		String replyTo;
		try {
			JSONObject json = new JSONObject(representation.getText());
			transportType = json.getString("transport");
			subject = json.getString("subject");
			to = json.getString("to");
			body = json.getString("body");

			if (getPM().getPrincipalEntry(to) == null) {
				log.info("User tried to send message to unknown email address [{}]", to);
				getResponse().setStatus(CLIENT_ERROR_FORBIDDEN);
				return;
			}

			replyTo = getPM().getPrincipalName(getPM().getAuthenticatedUserURI());
			if (replyTo != null && !replyTo.contains("@")) {
				replyTo = null; // in case the sender does not have a proper e-mail address set
			}
		} catch (IOException e) {
			log.debug("Error when parsing request", e);
			getResponse().setStatus(CLIENT_ERROR_BAD_REQUEST);
			return;
		}

		if ("email".equalsIgnoreCase(transportType)) {
			Email.sendMessage(getRM().getConfiguration(), to, subject, body, null, replyTo);
		} else {
			getResponse().setStatus(CLIENT_ERROR_BAD_REQUEST);
		}
	}

}
