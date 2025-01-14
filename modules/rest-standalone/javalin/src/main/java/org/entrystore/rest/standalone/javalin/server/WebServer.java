package org.entrystore.rest.standalone.javalin.server;

import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import io.javalin.Javalin;
import io.javalin.http.Context;
import lombok.extern.slf4j.Slf4j;
import org.entrystore.rest.standalone.javalin.model.EntryType;
import org.entrystore.rest.standalone.javalin.model.api.EntryCreateRequest;
import org.entrystore.rest.standalone.javalin.model.api.EntryResponse;
import org.entrystore.rest.standalone.javalin.model.api.StatusResponse;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

@Slf4j
public class WebServer {

	final XmlMapper xmlMapper = new XmlMapper();

	public WebServer() {

		var app = Javalin.create(/* Javalin config */)
			.get("/management/status", this::handleStatusResponseBasedOnAcceptHeader)
			.get("/entries/{entry-id}", this::handleEntityResponse)
			.post("/entries", this::handleCreateNewEntryRequest)
			.start(8080);

		// HTTP exceptions
		app.exception(InvalidFormatException.class, (e, ctx) -> {
			// handles InvalidFormatException
			ctx.status(400);
			ctx.result("Exception: " + e.getMessage());
		});

		app.exception(Exception.class, (e, ctx) -> {
			// handle general exceptions here
			// will not trigger if more specific exception-mapper found
			log.error("Generic exception: {}", e.getMessage(), e);
			ctx.status(500);
			ctx.result("Server Side Error");
		});
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
			// Otherwise respond with JSON
			ctx.json(responseModel);
		}
	}

	private void handleEntityResponse(Context ctx) {

		// get the path param
		// here I'm just testing ID as Integer - checking if 400 is thrown when non-digit value is passed
		String entryId = ctx.pathParamAsClass("entry-id", Integer.class).get().toString();

		EntryResponse responseModel = new EntryResponse(entryId, EntryType.LOCAL);
		ctx.json(responseModel);
	}

	private void handleCreateNewEntryRequest(Context ctx) {

		EntryCreateRequest entryRequest = ctx.bodyAsClass(EntryCreateRequest.class);
		// store the entry

		// return stored entry data
		EntryResponse responseModel = new EntryResponse(entryRequest.getEntryId(), entryRequest.getType());
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
