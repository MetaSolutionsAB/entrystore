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

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;

/**
 * Immutable view of the {@code entrystore.cors*} configuration, consumed by
 * {@link EntryStoreCorsConfigurationSource} (for the actual policy), by {@code SecurityConfig} (to
 * decide whether to install the CORS filter at all) and by {@code StatusService} (to report it).
 *
 * <p>Bound through {@code @Value} rather than {@code @ConfigurationProperties} because the legacy
 * key layout cannot be expressed as a record: {@code entrystore.cors} is simultaneously a scalar and
 * the prefix of {@code entrystore.cors.origins}, which is itself simultaneously a scalar and the
 * prefix of {@code entrystore.cors.origins.allow-credentials}. A record component can be one or the
 * other, never both, and no component name canonicalises to {@code origins.allow-credentials}.
 *
 * <p>The canonical constructor is written out in full so the {@code @Value} annotations sit on its
 * parameters only. Placing them on the record components instead would propagate them to the backing
 * fields as well (they are applicable to {@code FIELD}), and
 * {@code AutowiredAnnotationBeanPostProcessor} — which skips annotated record accessors but not
 * annotated record fields — would then attempt field injection into a record's final field and fail
 * at startup.
 */
@Component
public record CorsProperties(
		String mode,
		String origins,
		String originsAllowCredentials,
		String headers,
		int maxAge) {

	public CorsProperties(
			@Value("${entrystore.cors:off}") String mode,
			@Value("${entrystore.cors.origins:*}") String origins,
			@Value("${entrystore.cors.origins.allow-credentials:}") String originsAllowCredentials,
			@Value("${entrystore.cors.headers:}") String headers,
			@Value("${entrystore.cors.max-age:-1}") int maxAge) {
		this.mode = mode;
		this.origins = origins;
		this.originsAllowCredentials = originsAllowCredentials;
		this.headers = headers;
		this.maxAge = maxAge;
	}

	public boolean enabled() {
		return "on".equalsIgnoreCase(mode);
	}

	/**
	 * Origin patterns permitted without credentials. Lower-cased so a pattern configured in mixed
	 * case still matches the lower-case origins browsers send.
	 */
	public List<String> originPatterns() {
		return splitAndNormalize(origins);
	}

	/**
	 * Origin patterns additionally permitted to send credentials. Empty unless configured — an empty
	 * list means no origin gets {@code Access-Control-Allow-Credentials: true}.
	 */
	public List<String> credentialOriginPatterns() {
		return splitAndNormalize(originsAllowCredentials);
	}

	/**
	 * Headers to both allow on requests and expose on responses. Empty means "not configured", in
	 * which case neither header list is set and Spring's defaults apply.
	 */
	public List<String> headerList() {
		return Arrays.stream(headers.split(","))
				.map(String::trim)
				.filter(value -> !value.isEmpty())
				.toList();
	}

	private static List<String> splitAndNormalize(String value) {
		return Arrays.stream(value.split(","))
				.map(pattern -> pattern.trim().toLowerCase())
				.filter(pattern -> !pattern.isEmpty())
				.toList();
	}
}
