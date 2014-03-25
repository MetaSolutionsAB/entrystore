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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

	private static Logger log = LoggerFactory.getLogger(CORSUtil.class);

	private static Map<Config, CORSUtil> instances = new HashMap<>();

	private List<String> allowedOriginPatterns;

	private String allowedHeaders;

	private int maxAge = -1;

	private CORSUtil(Config config) {
		String origins = config.getString(Settings.CORS_ORIGINS, "*");
		allowedOriginPatterns = new ArrayList<String>();
		List<String> patterns = Arrays.asList(origins.split(","));
		for (String p : patterns) {
			log.info("CORS allowed origins: " + origins);
			allowedOriginPatterns.add(p.trim().toLowerCase());
		}
		if (config.containsKey(Settings.CORS_HEADERS)) {
			allowedHeaders = config.getString(Settings.CORS_HEADERS);
			log.info("CORS allowed headers: " + allowedHeaders);
		}
		if (config.containsKey(Settings.CORS_MAX_AGE)) {
			maxAge = config.getInt(Settings.CORS_MAX_AGE, -1);
			log.info("CORS max age: " + maxAge);
		}
	}

	public static CORSUtil getInstance(Config config) {
		if (!instances.containsKey(config)) {
			instances.put(config, new CORSUtil(config));
		}
		return instances.get(config);
	}

	public boolean isValidOrigin(String origin) {
		if (allowedOriginPatterns == null || origin == null) {
			return false;
		}
		origin = origin.toLowerCase();
		for (String pattern : allowedOriginPatterns) {
			if ("*".equals(pattern)) {
				return true;
			} else if (pattern.equals(origin)) {
				return true;
			} if (pattern.startsWith("*")) {
				pattern = pattern.substring(1);
				if (origin.endsWith(pattern)) {
					return true;
				}
			} else if (pattern.endsWith("*")) {
				pattern = pattern.substring(0, pattern.length() - 1);
				if (origin.startsWith(pattern)) {
					return true;
				}
			}
		}
		return false;
	}

	public String getAllowedHeaders() {
		return allowedHeaders;
	}

	public int getMaxAge() {
		return maxAge;
	}

}