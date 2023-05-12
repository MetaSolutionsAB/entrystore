/**
 * Copyright 2005-2020 Talend
 *
 * The contents of this file are subject to the terms of one of the following open source licenses: Apache 2.0 or or EPL
 * 1.0 (the "Licenses"). You can select the license that you prefer but you may not use this file except in compliance
 * with one of these Licenses.
 *
 * You can obtain a copy of the Apache 2.0 license at http://www.opensource.org/licenses/apache-2.0
 *
 * You can obtain a copy of the EPL 1.0 license at http://www.opensource.org/licenses/eclipse-1.0
 *
 * See the Licenses for the specific language governing permissions and limitations under the Licenses.
 *
 * Alternatively, you can obtain a royalty free commercial license with less limitations, transferable or
 * non-transferable, directly at https://restlet.talend.com/
 *
 * Restlet is a registered trademark of Talend S.A.
 */

package org.entrystore.rest;

import java.io.IOException;
import java.net.ServerSocket;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestInfo;
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
		System.out.println("Setting up test " + getClass().getName() + "#" + testInfo.getDisplayName());
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
		Engine.getInstance().getRegisteredClients()
				.add(0, new org.restlet.ext.httpclient.HttpClientHelper(null));

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

	@AfterEach
	protected void afterEach() throws Exception {
		tearDownEngine();
	}

	protected void tearDownEngine() {
		Engine.clearThreadLocalVariables();
	}

	protected int getPort() {
		return port;
	}
}
