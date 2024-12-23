package org.entrystore.rest.standalone.javalin.server;

import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import io.javalin.Javalin;
import io.javalin.http.Context;
import org.entrystore.rest.standalone.javalin.model.api.EntryResponse;
import org.entrystore.rest.standalone.javalin.model.api.StatusResponse;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

public class WebServer {

	final XmlMapper xmlMapper = new XmlMapper();

	public WebServer() {

		Javalin.create(/* Javalin config */)
			.get("/management/status", this::handleStatusResponseBasedOnAcceptHeader)
			.get("/entries/{entry-id}", this::handleEntityResponse)
			.start(8080);
	}

	private void handleStatusResponseBasedOnAcceptHeader(Context ctx) throws Exception {
		Properties props = loadProperties();
		StatusResponse responseModel = new StatusResponse(props.getProperty("app.version"), "online");

		// Check the Accept header
		String acceptHeader = ctx.header("Accept");

		if (acceptHeader != null && acceptHeader.contains("application/xml")) {
			// Respond with XML
			ctx.contentType("application/xml");
			ctx.result(convertObjectToXml(responseModel));
		} else {
			// Default to JSON
			ctx.json(responseModel);
		}
	}

	private void handleEntityResponse(Context ctx) {

		// get the path param
		// here I'm just testing ID as Integer - checking if 400 is thrown when non-digit value is passed
		String entryId = ctx.pathParamAsClass("entry-id", Integer.class).get().toString();

		EntryResponse responseModel = new EntryResponse(entryId, "a type");
		ctx.json(responseModel);
	}

	public String convertObjectToXml(Object object) throws Exception {
		return xmlMapper.writeValueAsString(object);
	}

	private Properties loadProperties() throws IOException {
		try (final InputStream in = getClass().getClassLoader().getResourceAsStream("application.properties")) {
			final Properties props = new Properties();
			props.load(in);
			return props;
		}
	}
}
