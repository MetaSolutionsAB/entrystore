package org.entrystore.rest.standalone.javalin.server;

import io.javalin.Javalin;
import org.entrystore.rest.standalone.javalin.model.api.StatusResponse;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

public class WebServer {

	public WebServer() throws IOException {

		Properties props = loadProperties();

		Javalin.create(/*config*/)
			.get("/management/status", ctx ->
				ctx.json(new StatusResponse(props.getProperty("app.version"), "")))
			.start(8080);
	}

	private Properties loadProperties() throws IOException {
		try (final InputStream in = getClass().getClassLoader().getResourceAsStream("application.properties")) {
			final Properties props = new Properties();
			props.load(in);
			return props;
		}
	}
}
