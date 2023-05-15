/*
 * Copyright (c) 2007-2017 MetaSolutions AB
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

package org.entrystore.rest;

import static java.lang.System.out;

import java.io.IOException;
import java.net.ServerSocket;
import org.entrystore.repository.config.PropertiesConfiguration;
import org.restlet.Application;
import org.restlet.Component;
import org.restlet.Context;
import org.restlet.data.Protocol;
import org.restlet.engine.Engine;
import org.restlet.engine.connector.HttpServerHelper;
import org.restlet.representation.ObjectRepresentation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TestEntryStoreServer extends Application {

	static Logger log = LoggerFactory.getLogger(TestEntryStoreServer.class);

	public static final String KEY = TestEntryStoreServer.class.getCanonicalName();

	private Component component;

	private int getRandomAvailablePort() throws IOException {
		try (ServerSocket serverSocket = new ServerSocket(0)) {
			return serverSocket.getLocalPort();
		}
	}

	public void start() throws Exception {
		out.println("Starting Test EntryStore Server");
		startApplication();
		startEngine();
	}

	protected void startApplication() throws Exception {
		this.component = new Component();
		this.component.getServers().add(Protocol.HTTP, getRandomAvailablePort());
		this.component.getLogService().setResponseLogFormat("{ciua} \"{m} {rp} {rq}\" {S} {ES} {es} {hh} {cig} {fi}");
		Context childContext = component.getContext().createChildContext();
		TestEntryStoreApplication esApp = new TestEntryStoreApplication(
				new PropertiesConfiguration("Test EntryStore"), childContext, component);
		childContext.getAttributes().put(EntryStoreApplication.KEY, esApp);
		this.component.getDefaultHost().attach(esApp);
		this.component.start();
	}

	protected void startEngine() {
		Engine.clearThreadLocalVariables();
		Engine.register();
		Engine.getInstance().getRegisteredServers().add(0, new HttpServerHelper(null));
		ObjectRepresentation.VARIANT_OBJECT_XML_SUPPORTED = true;
		ObjectRepresentation.VARIANT_OBJECT_BINARY_SUPPORTED = true;
	}
	public void stop() throws Exception {
		if (this.component != null) {
			log.info("Shutting down server");
			this.component.stop();
		}
	}
}
