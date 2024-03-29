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

package org.entrystore.rest.util;

import org.entrystore.config.Config;
import org.entrystore.repository.config.Settings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Helper class for CORS handling.
 *
 * @author Hannes Ebner
 */
public class CORSUtil {

	private static final Logger log = LoggerFactory.getLogger(CORSUtil.class);

	private static final Map<Config, CORSUtil> instances = new HashMap<>();

	private final List<String> allowedOriginPatterns;

	private final List<String> allowedOriginPatternsWithCredentials;

	private Set<String> allowedHeaders;

	private int maxAge = -1;

	private CORSUtil(Config config) {
		String origins = config.getString(Settings.CORS_ORIGINS, "*");
		allowedOriginPatterns = new ArrayList<>();
		String[] patterns = origins.split(",");
		for (String p : patterns) {
			log.info("CORS allowed origin: {}", p);
			allowedOriginPatterns.add(p.trim().toLowerCase());
		}

		String originsAllowCredentials = config.getString(Settings.CORS_ORIGINS_ALLOW_CREDENTIALS, "");
		allowedOriginPatternsWithCredentials = new ArrayList<>();
		patterns = originsAllowCredentials.split(",");
		for (String p : patterns) {
			log.info("CORS allowed origin (with credentials): {}", p);
			allowedOriginPatternsWithCredentials.add(p.trim().toLowerCase());
		}

		if (config.containsKey(Settings.CORS_HEADERS)) {
			String confAllHeaders = config.getString(Settings.CORS_HEADERS);
			allowedHeaders = new HashSet<>(Arrays.asList(confAllHeaders.split(",")));
			log.info("CORS allowed/exposed headers: " + confAllHeaders);
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
		return isAllowedOrigin(origin, allowedOriginPatterns);
	}

	public boolean isValidOriginWithCredentials(String origin) {
		return isAllowedOrigin(origin, allowedOriginPatternsWithCredentials);
	}

	private boolean isAllowedOrigin(String origin, List<String> patterns) {
		if (origin == null || patterns == null) {
			return false;
		}
		origin = origin.toLowerCase();
		for (String pattern : patterns) {
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

	public Set<String> getAllowedHeaders() {
		return allowedHeaders;
	}

	public int getMaxAge() {
		return maxAge;
	}

}