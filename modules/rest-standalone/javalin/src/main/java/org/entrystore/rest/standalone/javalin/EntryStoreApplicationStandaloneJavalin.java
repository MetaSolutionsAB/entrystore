package org.entrystore.rest.standalone.javalin;

import org.entrystore.rest.standalone.javalin.server.WebServer;

import java.io.IOException;

public class EntryStoreApplicationStandaloneJavalin {

	public static void main(String[] args) throws IOException {
		new WebServer();
	}
}
