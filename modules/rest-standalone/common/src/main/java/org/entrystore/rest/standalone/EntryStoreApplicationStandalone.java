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
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.config.Configurator;
import org.entrystore.rest.EntryStoreApplication;
import org.restlet.Application;
import org.restlet.Component;
import org.restlet.Context;
import org.restlet.Server;
import org.restlet.data.Protocol;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.HashMap;
import java.util.Map;

/**
 * Class to provide common functionality for other standalone wrappers.
 * Should not be used directly.
 *
 * @author Hannes Ebner
 */
public abstract class EntryStoreApplicationStandalone extends Application {

	private final static org.slf4j.Logger log = LoggerFactory.getLogger(EntryStoreApplicationStandalone.class);

	public static String ENV_CONFIG_PROPERTIES = "ENTRYSTORE_CONFIG_PROPERTIES";

	public static String ENV_CONNECTOR_PARAMS = "ENTRYSTORE_CONNECTOR_PARAMS";

	public static void main(String[] args) {
		System.setProperty("org.restlet.engine.loggerFacadeClass", "org.restlet.ext.slf4j.Slf4jLoggerFacade");

		CommandLineParser parser = new DefaultParser();
		Options options = new Options();
		options.addOption(Option.builder("c").
			longOpt("config").
			required(System.getenv(EntryStoreApplication.ENV_CONFIG_URI) == null).
			desc("URL of configuration file, may be omitted if environment variable ENTRYSTORE_CONFIG_URI is set").
			hasArg().
			argName("URL").
			type(PatternOptionBuilder.URL_VALUE).
			build());
		options.addOption(Option.builder("p").
			longOpt("port").
			desc("port to listen on; default: 8181").
			hasArg().
			argName("PORT").
			type(PatternOptionBuilder.NUMBER_VALUE).
			build());
		options.addOption(Option.builder("l").
			longOpt("log-level").
			desc("log level for root level (excludes Solr and Jetty), one of: ALL, TRACE, DEBUG, INFO, WARN, ERROR, FATAL, OFF; default: INFO").
			hasArg().
			argName("LEVEL").
			build());
		options.addOption(Option.builder().
			longOpt("log-level-all").
			desc("log level for all levels (including Solr and Jetty), overrides \"--log-level\",  one of: ALL, TRACE, DEBUG, INFO, WARN, ERROR, FATAL, OFF; default: INFO").
			hasArg().
			argName("LEVEL").
			build());
		options.addOption(Option.builder().
			longOpt("config-properties").
			desc("comma separated list of configuration properties (key/value pairs) to be used on top of the regular configuration file, " +
				"overrides existing properties in configuration, the environment variable ENTRYSTORE_CONFIG_PROPERTIES may be used instead.\n" +
				"Example for changing base URI: \"entrystore.baseurl.folder=http://localhost:8585/store/\"").
			hasArg().
			argName("PROPERTIES").
			build());
		options.addOption(Option.builder().
			longOpt("connector-params").
			desc("comma separated list of parameters (key/value pairs) to be used for the server connector, " +
				"the environment variable ENTRYSTORE_CONNECTOR_PARAMS may be used instead.\n" +
				"Example for Jetty: \"threadPool.minThreads=50,threadPool.maxThreads=250\"; " +
				"see the JavaDoc of JettyServerHelper for available parameters").
			hasArg().
			argName("SETTINGS").
			build());
		options.addOption(Option.builder("h").longOpt("help").desc("display this help").build());

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

		configureLogging(cl.getOptionValue("log-level", "INFO"), cl.getOptionValue("log-level-all"));

		Component component = new Component();
		Server server = component.getServers().add(Protocol.HTTP, port);

		String conParams;
		if (cl.hasOption("connector-params")) {
			conParams = cl.getOptionValue("connector-params");
		} else {
			conParams = System.getenv(ENV_CONNECTOR_PARAMS);
		}
		if (conParams != null) {
			for (String param : conParams.split(",")) {
				if (!param.isEmpty()) {
					String[] kv = param.split("=");
					if (kv.length == 2) {
						log.debug("Adding connector parameter: {}={}", kv[0], kv[1]);
						server.getContext().getParameters().add(kv[0].trim(), kv[1].trim());
					} else {
						log.error("Invalid connector parameter: {}", param);
						System.exit(1);
					}
				}
			}
		}

		String configParams;
		if (cl.hasOption("config-properties")) {
			configParams = cl.getOptionValue("config-properties");
		} else {
			configParams = System.getenv(ENV_CONFIG_PROPERTIES);
		}
		Map<String, String> configOverride = new HashMap<>();
		if (configParams != null) {
			for (String param : configParams.split(",")) {
				if (!param.isEmpty()) {
					String[] kv = param.split("=");
					if (kv.length == 2) {
						log.debug("Adding config parameter: {}={}", kv[0], kv[1]);
						configOverride.put(kv[0].trim(), kv[1].trim());
					} else {
						log.error("Invalid config parameter: {}", param);
						System.exit(1);
					}
				}
			}
		}

		component.getLogService().setResponseLogFormat("{ciua} \"{m} {rp} {rq}\" {S} {ES} {es} {hh} {cig} {fi}");
		component.getClients().add(Protocol.FILE);
		component.getClients().add(Protocol.HTTP);
		component.getClients().add(Protocol.HTTPS);
		server.getContext().getParameters().add("useForwardedForHeader", "true");
		Context childContext = component.getContext().createChildContext();
		EntryStoreApplication esApp = new EntryStoreApplication(config, configOverride, childContext, component);
		childContext.getAttributes().put(EntryStoreApplication.KEY, esApp);
		component.getDefaultHost().attach(esApp);

		try {
			component.start();
		} catch (Exception e) {
			log.error(e.getMessage(), e);
		}
	}

	private static void printHelp(Options options) {
		HelpFormatter formatter = new HelpFormatter();
		formatter.setWidth(100);
		formatter.setLeftPadding(2);
		formatter.printHelp("entrystore", options,true);
	}

	private static void out(String s) {
		System.out.println(s);
	}

	private static void configureLogging(String rootLevel, String allLevels) {
		Level lRoot = Level.toLevel(rootLevel, Level.INFO);
		Configurator.setRootLevel(lRoot);
		out("Root log level set to " + lRoot);

		if (allLevels != null) {
			Level lAll = Level.toLevel(allLevels, Level.INFO);
			Configurator.setAllLevels(LogManager.getRootLogger().getName(), lAll);
		}
	}

}
