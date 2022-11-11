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

package org.entrystore.rest.standalone.springboot;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Properties;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.apache.commons.cli.PatternOptionBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Main class to start EntryStore using Spring Boot.
 *
 * @author Björn Frantzén
 */
@SpringBootApplication
public class EntryStoreApplicationStandaloneSpringBoot {

	private final static Logger log = LoggerFactory.getLogger(EntryStoreApplicationStandaloneSpringBoot.class);

	public static String ENV_CONNECTOR_PARAMS = "ENTRYSTORE_CONNECTOR_PARAMS";
	public static String ENV_CONFIG_URI = "ENTRYSTORE_CONFIG_URI";


	public static void main(String... args) {
		Properties properties = parseArgs(args);
		SpringApplication application = new SpringApplication(EntryStoreApplicationStandaloneSpringBoot.class);
		application.setDefaultProperties(properties);
		application.run(args);
	}

	private static Properties parseArgs(String... args) {
		Properties properties = new Properties();
		Options options = new Options();
		options.addOption(Option.builder("c").
			longOpt("config").
			required(System.getenv(ENV_CONFIG_URI) == null).
			desc("URL of configuration file, may be omitted if environment variable ENTRYSTORE_CONFIG_URI is set").
			hasArg().
			argName("URL").
			optionalArg(false).
			type(PatternOptionBuilder.URL_VALUE).
			build());
		options.addOption(Option.builder("p").
			longOpt("port").
			desc("port to listen on; default: 8181").
			hasArg().
			argName("PORT").
			optionalArg(false).
			type(PatternOptionBuilder.NUMBER_VALUE).
			build());
		options.addOption(Option.builder("l").
			longOpt("log-level").
			desc("log level, one of: ALL, TRACE, DEBUG, INFO, WARN, ERROR, FATAL, OFF; default: INFO").
			hasArg().
			argName("LEVEL").
			optionalArg(false).
			build());
		options.addOption(Option.builder().
			longOpt("connector-params").
			desc("comma separated list of parameters to be used for the server connector, " +
				 "the environment variable ENTRYSTORE_CONNECTOR_PARAMS may be used instead. " +
				 "Example for Jetty: \"threadPool.minThreads=50,threadPool.maxThreads=250\"; " +
				 "see the JavaDoc of JettyServerHelper for available parameters").
			hasArg().
			argName("SETTINGS").
			optionalArg(false).
			build());
		options.addOption(Option.builder().
			longOpt("debug").
			desc("debug Spring Boot options").
			optionalArg(false).
			build());
		options.addOption(Option.builder("h").longOpt("help").desc("display this help").build());

		CommandLine cl = null;
		try {
			cl = new DefaultParser().parse(options, args, true);
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
		try {
			properties.setProperty("server.port", strPort);
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
				config = new URI(System.getenv(ENV_CONFIG_URI));
			}
		} catch (URISyntaxException e) {
			System.err.println("Invalid configuration URL: " + e.getMessage() + "\n");
			printHelp(options);
			System.exit(1);
		}

		String logLevel = cl.getOptionValue("log-level", "INFO");
		properties.setProperty("logging.level.root", logLevel);

		String conParams;
		if (cl.hasOption("connector-params")) {
			conParams = cl.getOptionValue("connector-params");
		} else {
			conParams = System.getenv(ENV_CONNECTOR_PARAMS);
		}
		if (conParams != null) {
			for (String param : conParams.split(",")) {
				if (param.length() > 0) {
					String[] kv = param.split("=");
					if (kv.length != 2) {
						System.err.println("Invalid connector parameter: " + param);
						System.exit(1);
					}
				}
			}
			//TODO: These are not used
			properties.setProperty("entrystore.connector-params", conParams);
		}
		return properties;
	}

	private static void printHelp(Options options) {
		HelpFormatter formatter = new HelpFormatter();
		formatter.setWidth(100);
		formatter.setLeftPadding(2);
		formatter.printHelp("entrystore", options,true);
	}
}
