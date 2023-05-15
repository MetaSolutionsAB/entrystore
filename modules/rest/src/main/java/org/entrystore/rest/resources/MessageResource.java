package org.entrystore.rest.resources;

import static org.entrystore.rest.util.MessageTransportType.EMAIL;
import static org.restlet.data.Status.CLIENT_ERROR_BAD_REQUEST;
import static org.restlet.data.Status.CLIENT_ERROR_FORBIDDEN;
import static org.restlet.data.Status.SERVER_ERROR_INTERNAL;

import java.io.IOException;
import org.entrystore.rest.util.Email;
import org.entrystore.rest.util.MessageTransportType;
import org.json.JSONException;
import org.json.JSONObject;
import org.restlet.ext.json.JsonRepresentation;
import org.restlet.representation.Representation;
import org.restlet.resource.Post;
import org.restlet.resource.ResourceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MessageResource extends BaseResource {

	private static final Logger log = LoggerFactory.getLogger(MessageResource.class);

	@Post
	public void sendMessage(Representation representation) throws ResourceException {
		if (getPM().currentUserIsGuest()) {
			unauthorizedPOST();
			return;
		}

		JsonRepresentation jsonRepresentation;
		try {
			jsonRepresentation = new JsonRepresentation(representation);
		} catch (IOException e) {
			log.info("Caught exception while creating JsonRepresentation", e);
			getResponse().setStatus(SERVER_ERROR_INTERNAL);
			return;
		}

		MessageTransportType transportType;
		String subject;
		String to;
		String body;
		try {
			JSONObject json = jsonRepresentation.getJsonObject();

			transportType = json.has("transport") ? json.getEnum(MessageTransportType.class, "transport") : EMAIL;
			subject = json.getString("subject");
			to = json.getString("to");
			body = json.getString("body");

			if (getPM().getPrincipalEntry(to) == null) {
				log.info("User tried to send message to unknown email address [{}]", to);
				getResponse().setStatus(CLIENT_ERROR_FORBIDDEN);
				return;
			}
		} catch (JSONException e) {
			log.debug("Error in json from client", e);
			getResponse().setStatus(CLIENT_ERROR_BAD_REQUEST);
			return;
		}

		if (transportType == EMAIL) {
			Email.sendMessage(getRM().getConfiguration(), to, subject, body);
		}
	}
}
