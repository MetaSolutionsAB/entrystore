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
import java.util.Locale;

/**
 * Resolves the CORS policy per request from two policies fixed at startup: one for origins allowed
 * to send credentials, one for the rest. Both are built once in the constructor and only ever read,
 * so nothing here mutates after the context is up.
 *
 * <p>Origin matching is delegated to {@link CorsConfiguration#checkOrigin}, driven by
 * {@link CorsConfiguration#setAllowedOriginPatterns} — that supports the {@code *.suffix},
 * {@code prefix.*} and bare {@code *} forms EntryStore documents. {@code setAllowedOrigins} would
 * not work for the startup policies: {@code checkOrigin} rejects a literal {@code *} there when
 * credentials are allowed, whereas patterns are exempt and still echo the request origin rather than
 * emitting {@code *}.
 *
 * <p>Matching is case-insensitive, as it was before the policy moved onto Spring's pattern support:
 * the configured patterns are lower-cased by {@link CorsProperties} and the request origin is
 * lower-cased here. Normalising the origin alone would not be enough, because
 * {@code DefaultCorsProcessor} re-runs {@code checkOrigin} with the <em>unmodified</em> header and
 * rejects with 403 when that second check fails. An origin that needed normalising to match therefore
 * gets a copy of the policy carrying the raw header in {@code allowedOrigins}, which is compared with
 * {@code equalsIgnoreCase}. Origins that are already lower case — everything a browser sends — match
 * the shared policy directly and get it back untouched.
 *
 * <p>Returning {@code null} for an origin that matches neither list means a <b>simple</b> request
 * passes through unchanged, without any {@code Access-Control-*} headers, leaving it to the browser
 * to block the response. A <b>preflight</b> from such an origin is still answered with <b>403</b>
 * ({@code Invalid CORS request}) by {@code DefaultCorsProcessor}, which rejects any preflight for
 * which no configuration is resolved.
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

	private record Policies(CorsConfiguration credential, CorsConfiguration standard) {
	}

	/** Null when CORS is disabled — there is then no policy to hand out at all. */
	private final @Nullable Policies policies;

	public EntryStoreCorsConfigurationSource(CorsProperties properties) {
		if (!properties.enabled()) {
			log.info("CORS is disabled");
			this.policies = null;
			return;
		}

		log.info("CORS is enabled");
		List<String> originPatterns = properties.originPatterns();
		List<String> credentialOriginPatterns = properties.credentialOriginPatterns();
		originPatterns.forEach(pattern -> log.info("CORS allowed origin: {}", pattern));
		credentialOriginPatterns.forEach(pattern -> log.info("CORS allowed origin (with credentials): {}", pattern));
		warnAboutInteriorWildcards(originPatterns);
		warnAboutInteriorWildcards(credentialOriginPatterns);

		List<String> headers = properties.headerList();
		if (!headers.isEmpty()) {
			log.info("CORS allowed/exposed headers: {}", headers);
		}
		if (properties.maxAge() > -1) {
			log.info("CORS max age: {}", properties.maxAge());
		}

		this.policies = new Policies(
				buildPolicy(credentialOriginPatterns, true, headers, properties.maxAge()),
				buildPolicy(originPatterns, false, headers, properties.maxAge()));
	}

	@Override
	public @Nullable CorsConfiguration getCorsConfiguration(HttpServletRequest request) {
		String origin = request.getHeader(HttpHeaders.ORIGIN);
		if (policies == null || origin == null) {
			return null;
		}

		String normalizedOrigin = origin.toLowerCase(Locale.ROOT);
		// Credentials first: an origin on both lists must keep getting Allow-Credentials: true.
		CorsConfiguration matched = null;
		if (policies.credential().checkOrigin(normalizedOrigin) != null) {
			matched = policies.credential();
		} else if (policies.standard().checkOrigin(normalizedOrigin) != null) {
			matched = policies.standard();
		}
		if (matched == null) {
			return null;
		}
		if (origin.equals(normalizedOrigin)) {
			return matched;
		}

		// The origin matched only once lower-cased, but DefaultCorsProcessor re-runs checkOrigin with
		// the unmodified header and rejects with 403 if that second check fails. Hand back a copy
		// carrying the raw origin in allowedOrigins, which is compared with equalsIgnoreCase.
		// Assigning rather than adding matters: the copy constructor shares list references, so
		// addAllowedOrigin would append to the startup policy's own list.
		CorsConfiguration perRequest = new CorsConfiguration(matched);
		perRequest.setAllowedOrigins(List.of(origin));
		return perRequest;
	}

	private static void warnAboutInteriorWildcards(List<String> originPatterns) {
		originPatterns.stream()
				.filter(pattern -> {
					int star = pattern.indexOf('*');
					return star > 0 && star < pattern.length() - 1;
				})
				.forEach(pattern -> log.warn("CORS origin pattern '{}' has an interior wildcard and matches every "
						+ "origin the surrounding text allows; the documented forms are '*', '*suffix' and 'prefix*'",
						pattern));
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
