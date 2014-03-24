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

package org.entrystore.rest.util;

import org.entrystore.config.Config;
import org.entrystore.repository.config.Settings;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Helper class for CORS handling.
 *
 * @author Hannes Ebner
 */
public class CORSUtil {

	private static Map<Config, CORSUtil> instances = new HashMap<>();

	private List<String> allowedPatterns;

	private String exposeHeaders;

	private CORSUtil(Config config) {
		String origins = config.getString(Settings.CORS_ORIGINS, "*");
		allowedPatterns = new ArrayList<String>();
		List<String> patterns = Arrays.asList(origins.split(","));
		for (String p : patterns) {
			allowedPatterns.add(p.trim().toLowerCase().replace("*", "\\w*"));
		}
		if (config.containsKey(Settings.CORS_HEADERS)) {
			exposeHeaders = config.getString(Settings.CORS_HEADERS);
		}
	}

	public static CORSUtil getInstance(Config config) {
		if (!instances.containsKey(config)) {
			instances.put(config, new CORSUtil(config));
		}
		return instances.get(config);
	}

	public boolean isValidOrigin(String origin) {
		if (allowedPatterns == null || origin == null) {
			return false;
		}
		for (String pattern : allowedPatterns) {
			if (pattern.matches(origin.toLowerCase())) {
				return true;
			}
		}
		return false;
	}

	public String getExposeHeaders() {
		return exposeHeaders;
	}

}