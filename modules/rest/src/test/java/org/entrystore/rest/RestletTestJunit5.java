package org.entrystore.rest;

import static java.lang.System.out;

import java.io.IOException;
import java.net.ServerSocket;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.config.Configurator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestInfo;
import org.restlet.Component;
import org.restlet.Context;
import org.restlet.Server;
import org.restlet.data.Protocol;
import org.restlet.engine.Engine;
import org.restlet.representation.ObjectRepresentation;

/**
 * Marker class. All Restlet tests should be derived from this class.
 *
 */
public abstract class RestletTestJunit5 {

	public int DEFAULT_PORT = 1337;
	private final int port = getTestPort();
//	private Component component;

	private int getTestPort() {
		try (ServerSocket serverSocket = new ServerSocket(0)) {
			return serverSocket.getLocalPort();
		} catch (IOException e) {
			return DEFAULT_PORT;
		}
	}

	@BeforeEach
	void beforeEach(TestInfo testInfo) {
		out.println("Setting up test " + getClass().getName() + "#" + testInfo.getDisplayName());
		setupApplication();
		setUpEngine();
	}

	protected void setUpEngine() {
		Engine.clearThreadLocalVariables();

		// Restore a clean engine
		Engine.register();

		// Prefer the internal connectors
		Engine.getInstance()
				.getRegisteredServers()
				.add(0, new org.restlet.engine.connector.HttpServerHelper(null));
		// FIXME turn on the internal connector.
//		Engine.getInstance().getRegisteredClients()
//				.add(0, new org.restlet.ext.httpclient.HttpClientHelper(null));

		// Enable object serialization
		ObjectRepresentation.VARIANT_OBJECT_XML_SUPPORTED = true;
		ObjectRepresentation.VARIANT_OBJECT_BINARY_SUPPORTED = true;

//			this.component = new Component();
//			this.component.getServers().add(Protocol.HTTP, TEST_PORT);
//
//			component.getDefaultHost().attach("/api", new ApplicationContextTestCase.WebApiApplication());
//			component.getInternalRouter().attach("/internal", new ApplicationContextTestCase.InternalApplication());
//
//			component.start();
	}

	protected void setupApplication() {
		Component component = new Component();
		Server server = component.getServers().add(Protocol.HTTP, port);

		component.getLogService().setResponseLogFormat("{ciua} \"{m} {rp} {rq}\" {S} {ES} {es} {hh} {cig} {fi}");
//		component.getClients().add(Protocol.FILE);
//		component.getClients().add(Protocol.HTTP);
//		component.getClients().add(Protocol.HTTPS);
		server.getContext().getParameters().add("useForwardedForHeader", "true");
		Context childContext = component.getContext().createChildContext();
		EntryStoreApplication esApp = new EntryStoreApplication(null, childContext, component);
//		EntryStoreApplication esApp = new EntryStoreApplication(config, childContext, component);
		childContext.getAttributes().put(EntryStoreApplication.KEY, esApp);
		component.getDefaultHost().attach(esApp);

		try {
			component.start();
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	private static void configureLogging(String logLevel) {
		Level l = Level.toLevel(logLevel, Level.INFO);
		Configurator.setRootLevel(l);
		out.println("Log level set to " + l);
	}


	@AfterEach
	protected void afterEach() throws Exception {
		tearDownEngine();
	}

	@AfterEach
	protected void tearDownApplication() throws Exception {
	}

	protected void tearDownEngine() {
		Engine.clearThreadLocalVariables();
	}

	protected int getPort() {
		return port;
	}
}
