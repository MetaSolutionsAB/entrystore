package org.entrystore.rest.standalone.springboot.configuration;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.entrystore.config.Config;
import org.entrystore.repository.config.Settings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Slf4j
@Configuration
@RequiredArgsConstructor
public class CorsConfig {

	private final Config config;

	@Getter
	private boolean corsEnabled;

	private final List<String> allowedOriginPatterns = new ArrayList<>();
	private final List<String> allowedOriginPatternsWithCredentials = new ArrayList<>();
	private Set<String> allowedHeaders;
	private int maxAge = -1;

	private static final List<String> ALLOWED_METHODS = List.of("HEAD", "GET", "PUT", "POST", "DELETE", "OPTIONS");

	@PostConstruct
	public void init() {
		corsEnabled = "on".equalsIgnoreCase(config.getString(Settings.CORS, "off"));

		if (!corsEnabled) {
			log.info("CORS is disabled");
			return;
		}

		log.info("CORS is enabled");

		String origins = config.getString(Settings.CORS_ORIGINS, "*");
		for (String p : origins.split(",")) {
			String trimmed = p.trim().toLowerCase();
			log.info("CORS allowed origin: {}", trimmed);
			allowedOriginPatterns.add(trimmed);
		}

		String originsAllowCredentials = config.getString(Settings.CORS_ORIGINS_ALLOW_CREDENTIALS, "");
		for (String p : originsAllowCredentials.split(",")) {
			String trimmed = p.trim().toLowerCase();
			if (!trimmed.isEmpty()) {
				log.info("CORS allowed origin (with credentials): {}", trimmed);
				allowedOriginPatternsWithCredentials.add(trimmed);
			}
		}

		if (config.containsKey(Settings.CORS_HEADERS)) {
			String confAllHeaders = config.getString(Settings.CORS_HEADERS);
			allowedHeaders = new HashSet<>();
			for (String h : confAllHeaders.split(",")) {
				allowedHeaders.add(h.trim());
			}
			log.info("CORS allowed/exposed headers: {}", confAllHeaders);
		}

		if (config.containsKey(Settings.CORS_MAX_AGE)) {
			maxAge = config.getInt(Settings.CORS_MAX_AGE, -1);
			log.info("CORS max age: {}", maxAge);
		}
	}

	@Bean
	public CorsConfigurationSource corsConfigurationSource() {
		return request -> {
			if (!corsEnabled) {
				return null;
			}

			String origin = request.getHeader("Origin");
			if (origin == null) {
				return null;
			}

			boolean validOrigin = isAllowedOrigin(origin, allowedOriginPatterns);
			boolean validCredentialOrigin = isAllowedOrigin(origin, allowedOriginPatternsWithCredentials);

			if (!validOrigin && !validCredentialOrigin) {
				return null;
			}

			CorsConfiguration corsConfiguration = new CorsConfiguration();
			corsConfiguration.addAllowedOrigin(origin);
			corsConfiguration.setAllowedMethods(ALLOWED_METHODS);
			corsConfiguration.setAllowCredentials(validCredentialOrigin);

			if (allowedHeaders != null) {
				corsConfiguration.setAllowedHeaders(new ArrayList<>(allowedHeaders));
				corsConfiguration.setExposedHeaders(new ArrayList<>(allowedHeaders));
			}

			if (maxAge > -1) {
				corsConfiguration.setMaxAge((long) maxAge);
			}

			return corsConfiguration;
		};
	}

	private boolean isAllowedOrigin(String origin, List<String> patterns) {
		if (origin == null || patterns == null) {
			return false;
		}
		origin = origin.toLowerCase();
		for (String pattern : patterns) {
			if ("*".equals(pattern) || pattern.equals(origin)) {
				return true;
			} else if (pattern.startsWith("*") && origin.endsWith(pattern.substring(1))) {
				return true;
			} else if (pattern.endsWith("*") && origin.startsWith(pattern.substring(0, pattern.length() - 1))) {
				return true;
			}
		}
		return false;
	}
}
