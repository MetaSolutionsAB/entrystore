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
import java.util.Map;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.apache.commons.cli.PatternOptionBuilder;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.config.Configurator;
import org.entrystore.rest.EntryStoreApplication;
import org.entrystore.rest.standalone.EntryStoreApplicationStandalone;
import org.restlet.Context;
import org.restlet.Server;
import org.restlet.data.Protocol;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.PropertySource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

/**
 * Main class to start EntryStore using Spring Boot.
 *
 * @author Björn Frantzén
 */
@ConfigurationProperties("entrystore")
@Component
public class EntryStoreApplicationProperties {

	private Map<String, String> connectorParams;
	private URI config;

	public URI getConfig() {
		return config;
	}

	public void setConfig(URI config) {
		this.config = config;
	}

	public Map<String, String> getConnectorParams() {
		return connectorParams;
	}

	public void setConnectorParams(Map<String, String> connectorParams) {
		this.connectorParams = connectorParams;
	}
}
