/*
 * Copyright (c) 2007-2021 MetaSolutions AB
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

package org.entrystore.rest.standalone;

import org.apache.log4j.BasicConfigurator;
import org.apache.log4j.Level;
import org.apache.log4j.Logger;
import org.entrystore.rest.EntryStoreApplication;
import org.restlet.Application;
import org.restlet.Component;
import org.restlet.Context;
import org.restlet.data.Protocol;

import java.io.File;
import java.net.URI;

/**
 * Class to provide common functionality for other standalone wrappers.
 *
 * Should not be used directly.
 *
 * @author Hannes Ebner
 */
public abstract class EntryStoreApplicationStandalone extends Application {

	public static void main(String[] args) {
		int port = 8181;

		if ((args.length < 1 && System.getenv(EntryStoreApplication.ENV_CONFIG_URI) == null) || args.length > 3) {
			out("EntryStore standalone");
			out("https://entrystore.org");
			out("");
			out("Usage: entrystore [listening port] [log level] [path to configuration file]");
			out("");
			out("Path to configuration file may be omitted if environment variable ENTRYSTORE_CONFIG_URI is set to a URI.");
			out("Default listening port is " + port + ".");
			out("Log level may be one of: ALL, DEBUG, INFO, WARN, ERROR, FATAL, OFF, TRACE");
			out("");
			System.exit(0);
		}

		URI config = null;
		if (args.length > 0) {
			try {
				port = Integer.valueOf(args[0]);
			} catch (NumberFormatException nfe) {
				out("Invalid listening port, must be an integer");
			}
		}

		if (args.length >= 2) {
			configureLogging(args[1]);
		}

		if (args.length >= 3) {
			String configStr = args[2];
			config = new File(configStr).toURI();
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

	private static void configureLogging(String logLevel) {
		BasicConfigurator.configure();
		Level l = Level.INFO;
		if (logLevel != null) {
			l = Level.toLevel(logLevel, Level.INFO);
		}
		Logger.getRootLogger().setLevel(l);
		out("Log level set to " + l);
	}

}