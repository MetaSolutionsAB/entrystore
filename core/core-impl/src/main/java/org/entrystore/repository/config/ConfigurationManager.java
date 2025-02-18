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

package org.entrystore.repository.config;

import lombok.Getter;
import org.entrystore.config.Config;
import org.entrystore.config.Configurations;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;

/**
 * ConfigurationManager is loading, saving, and returning a configuration.
 *
 * @author Hannes Ebner
 * @version $Id$
 */
@Getter
public class ConfigurationManager {

	static Logger log = LoggerFactory.getLogger(ConfigurationManager.class);

	/**
	 * Instance of the configuration object.
	 */
	private Config configuration;
	public static String CONFIG_FILE = "entrystore.properties";

	/* Private methods */

	/**
	 * Constructor to be called indirectly by initialize(). Checks whether a
	 * configuration file exists and loads it respectively creates a new
	 * configuration.
	 *
	 * @throws IOException
	 */
	public ConfigurationManager(URI configURI) throws IOException {
		if (configURI == null) {
			throw new IllegalArgumentException("Configuration URI must not be null");
		}

		try {
			log.info("Loading configuration from: {}", configURI);
			// We don't call configFile.toURL() because it doesn't escape spaces etc.
			loadConfiguration(configURI.toURL());
		} catch (MalformedURLException e) {
			log.error(e.getMessage());
		}
	}

	private void initMainConfig() {
		configuration = Configurations.synchronizedConfig(new PropertiesConfiguration("EntryStore Configuration"));
	}

	/**
	 * Loads an already existing configuration.
	 *
	 * @throws IOException
	 */
	private void loadConfiguration(URL configURL) throws IOException {
		initMainConfig();
		configuration.load(configURL);
	}

	/* Public methods */

	/**
	 * This class is implemented as Singleton, so we want to avoid having
	 * multiple instances of the same object by cloning.
	 *
	 * @see java.lang.Object#clone()
	 */
	public Object clone() throws CloneNotSupportedException {
		throw new CloneNotSupportedException(this.getClass() + " is a Singleton.");
	}

	public static URI getConfigurationURI(String fileName) {
		URL resURL = Thread.currentThread().getContextClassLoader().getResource(fileName);
		try {
			if (resURL != null) {
				return resURL.toURI();
			}
		} catch (URISyntaxException e) {
			log.error(e.getMessage());
		}

		String classPath = System.getProperty("java.class.path");
		String[] pathElements = classPath.split(File.pathSeparator);
		for (String element : pathElements) {
			File newFile = new File(element, fileName);
			if (newFile.exists()) {
				return newFile.toURI();
			}
		}
		log.error("Unable to find {} in classpath", fileName);
		return null;
	}

	public static URI getConfigurationURI() {
		return getConfigurationURI(CONFIG_FILE);
	}

}
