/*
 * Copyright (c) 2007-2014 MetaSolutions AB
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

import org.apache.log4j.Level;
import org.apache.log4j.Logger;
import org.restlet.Application;
import org.restlet.Component;
import org.restlet.Context;
import org.restlet.data.Protocol;

import java.io.File;
import java.net.URI;

/**
 * Main class to start EntryStore standalone outside a container.
 *
 * @author Hannes Ebner
 */
public class EntryStoreApplicationStandalone extends Application {

	public static void main(String[] args) {
		if (args.length < 1 || args.length > 2) {
			out("EntryStore REST standalone");
			out("http://entrystore.org");
			out("");
			out("Usage: entrystore /path/to/entrystore.properties [listening port]");
			out("");
			out("Default listening port is 8181.");
			out("");
			System.exit(0);
		}

		String configStr = args[0];
		URI config = new File(configStr).toURI();

		int port = 8181;
		if (args.length == 2) {
			try {
				port = Integer.valueOf(args[1]);
			} catch (NumberFormatException nfe) {
				out("Invalid listening port, must be an integer");
			}
		}

		Component component = new Component();
		component.getServers().add(Protocol.HTTP, port);
		component.getClients().add(Protocol.FILE);
		component.getClients().add(Protocol.HTTP);
		component.getClients().add(Protocol.HTTPS);
		Context childContext = component.getContext().createChildContext();
		EntryStoreApplication esApp = new EntryStoreApplication(config, childContext);
		childContext.getAttributes().put(EntryStoreApplication.KEY, esApp);
		component.getDefaultHost().attach(esApp);

		try {
			component.start();
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	private static void out(String s) {
		System.out.println(s);
	}

}
