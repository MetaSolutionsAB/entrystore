/*
 * Copyright (c) 2007-2026 MetaSolutions AB
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

package org.entrystore.rest.springboot.configuration;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;

import java.util.List;

/**
 * Resolves the CORS policy per request from two policies fixed at startup: one for origins allowed
 * to send credentials, one for the rest. Both are built once in the constructor and only ever read,
 * so nothing here mutates after the context is up.
 *
 * <p>Origin matching is delegated to {@link CorsConfiguration#checkOrigin}, driven by
 * {@link CorsConfiguration#setAllowedOriginPatterns} — that supports the {@code *.suffix},
 * {@code prefix.*} and bare {@code *} forms EntryStore documents. {@code setAllowedOrigins} would
 * not work: {@code checkOrigin} rejects a literal {@code *} there when credentials are allowed,
 * whereas patterns are exempt and still echo the request origin rather than emitting {@code *}.
 *
 * <p>Returning {@code null} for an origin that matches neither list is deliberate and observable:
 * with a configuration present but no matching origin, {@code DefaultCorsProcessor} answers
 * <b>403</b>. EntryStore instead lets the request through unchanged, without any
 * {@code Access-Control-*} headers, and leaves it to the browser to block the response.
 *
 * <p>The bean name is load-bearing and must stay {@code corsConfigurationSource}: Spring Security's
 * {@code CorsConfigurer} resolves the source by that exact name, and when no such bean exists it
 * silently falls back to the Spring MVC {@code HandlerMappingIntrospector} — which only knows about
 * {@code @CrossOrigin} and {@code addCorsMappings}, so every CORS header would just disappear.
 */
@Slf4j
@Component("corsConfigurationSource")
public class EntryStoreCorsConfigurationSource implements CorsConfigurationSource {

	private static final List<String> ALLOWED_METHODS = List.of("HEAD", "GET", "PUT", "POST", "DELETE", "OPTIONS");

	private final boolean enabled;
	private final CorsConfiguration credentialPolicy;
	private final CorsConfiguration standardPolicy;

	public EntryStoreCorsConfigurationSource(CorsProperties properties) {
		this.enabled = properties.enabled();
		if (!enabled) {
			log.info("CORS is disabled");
			this.credentialPolicy = new CorsConfiguration();
			this.standardPolicy = new CorsConfiguration();
			return;
		}

		log.info("CORS is enabled");
		List<String> originPatterns = properties.originPatterns();
		List<String> credentialOriginPatterns = properties.credentialOriginPatterns();
		originPatterns.forEach(pattern -> log.info("CORS allowed origin: {}", pattern));
		credentialOriginPatterns.forEach(pattern -> log.info("CORS allowed origin (with credentials): {}", pattern));

		List<String> headers = properties.headerList();
		if (!headers.isEmpty()) {
			log.info("CORS allowed/exposed headers: {}", headers);
		}
		if (properties.maxAge() > -1) {
			log.info("CORS max age: {}", properties.maxAge());
		}

		this.credentialPolicy = buildPolicy(credentialOriginPatterns, true, headers, properties.maxAge());
		this.standardPolicy = buildPolicy(originPatterns, false, headers, properties.maxAge());
	}

	@Override
	public @Nullable CorsConfiguration getCorsConfiguration(HttpServletRequest request) {
		String origin = request.getHeader(HttpHeaders.ORIGIN);
		if (!enabled || origin == null) {
			return null;
		}
		// Credentials first: an origin on both lists must keep getting Allow-Credentials: true.
		if (credentialPolicy.checkOrigin(origin) != null) {
			return credentialPolicy;
		}
		if (standardPolicy.checkOrigin(origin) != null) {
			return standardPolicy;
		}
		return null;
	}

	private static CorsConfiguration buildPolicy(List<String> originPatterns, boolean allowCredentials,
												 List<String> headers, int maxAge) {
		CorsConfiguration policy = new CorsConfiguration();
		if (!originPatterns.isEmpty()) {
			policy.setAllowedOriginPatterns(originPatterns);
		}
		policy.setAllowedMethods(ALLOWED_METHODS);
		policy.setAllowCredentials(allowCredentials);
		if (!headers.isEmpty()) {
			policy.setAllowedHeaders(headers);
			policy.setExposedHeaders(headers);
		}
		if (maxAge > -1) {
			policy.setMaxAge((long) maxAge);
		}
		return policy;
	}
}
