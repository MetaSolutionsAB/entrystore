/*
 * Copyright (c) 2007-2024 MetaSolutions AB
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

package org.entrystore.impl.converters;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.net.MalformedURLException;
import java.net.URL;

/**
 * @author Hannes Ebner
 */
public class ConverterUtil {

	private static final Logger log = LoggerFactory.getLogger(ConverterUtil.class);

	public static URL findResource(String resource) {
		URL resourceURL = Thread.currentThread().getContextClassLoader().getResource(resource);

		if (resourceURL == null) {
			String classPath = System.getProperty("java.class.path");
			String[] pathElements = classPath.split(File.pathSeparator);
			for (String element : pathElements)	{
				File newFile = new File(element, resource);
				if (newFile.exists()) {
					try {
						resourceURL = newFile.toURI().toURL();
					} catch (MalformedURLException e) {
						log.error(e.getMessage());
					}
				}
			}
		}

		return resourceURL;
	}

}
