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

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.apache.commons.cli.PatternOptionBuilder;
import org.apache.log4j.BasicConfigurator;
import org.apache.log4j.Level;
import org.apache.log4j.Logger;
import org.entrystore.rest.EntryStoreApplication;
import org.restlet.Application;
import org.restlet.Component;
import org.restlet.Context;
import org.restlet.data.Protocol;

import java.net.URI;
import java.net.URISyntaxException;

/**
 * Class to provide common functionality for other standalone wrappers.
 *
 * Should not be used directly.
 *
 * @author Hannes Ebner
 */
public abstract class EntryStoreApplicationStandalone extends Application {

	public static void main(String[] args) {
		CommandLineParser parser = new DefaultParser();
		Options options = new Options();
		options.addOption(Option.builder("c").
				longOpt("config").
				required(System.getenv(EntryStoreApplication.ENV_CONFIG_URI) == null).
				desc("URL of configuration file, may be omitted if environment variable ENTRYSTORE_CONFIG_URI is set").
				hasArg().
				argName("URL").
				optionalArg(false).
				type(PatternOptionBuilder.URL_VALUE).
				build());
		options.addOption(Option.builder("p").
				longOpt("port").
				desc("port to listen on\ndefault: 8181").
				hasArg().
				argName("PORT").
				optionalArg(false).
				type(PatternOptionBuilder.NUMBER_VALUE).
				build());
		options.addOption(Option.builder().
				longOpt("log-level").
				desc("log level, one of: ALL, TRACE, DEBUG, INFO, WARN, ERROR, FATAL, OFF\ndefault: INFO").
				hasArg().
				argName("LEVEL").
				optionalArg(false).
				build());
		options.addOption(Option.builder("h").longOpt("help").desc("display help").build());

		CommandLine cl = null;
		try {
			cl = parser.parse(options, args);
		} catch (ParseException e) {
			System.err.println(e.getMessage() + "\n");
			printHelp(options);
			System.exit(1);
		}

		if (cl.hasOption("help")) {
			printHelp(options);
			System.exit(0);
		}

		String strPort = cl.getOptionValue("p", "8181");
		int port = 8181;
		try {
			port = Integer.parseInt(strPort);
		} catch (NumberFormatException nfe) {
			System.err.println("Invalid port number, must be integer: " + strPort + "\n");
			printHelp(options);
			System.exit(1);
		}

		URI config = null;
		try {
			if (cl.hasOption("c")) {
				config = new URI(cl.getOptionValue("c"));
			} else {
				config = new URI(System.getenv(EntryStoreApplication.ENV_CONFIG_URI));
			}
		} catch (URISyntaxException e) {
			System.err.println("Invalid configuration URL: " + e.getMessage() + "\n");
			printHelp(options);
			System.exit(1);
		}

		configureLogging(cl.getOptionValue("log-level", "INFO"));

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

	private static void printHelp(Options options) {
		HelpFormatter formatter = new HelpFormatter();
		formatter.setWidth(90);
		formatter.setLeftPadding(2);
		formatter.printHelp("entrystore", options,true);
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