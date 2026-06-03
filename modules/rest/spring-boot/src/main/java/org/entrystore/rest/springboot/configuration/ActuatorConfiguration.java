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

import org.springframework.boot.actuate.endpoint.SanitizingFunction;
import org.springframework.boot.actuate.web.exchanges.HttpExchangeRepository;
import org.springframework.boot.actuate.web.exchanges.InMemoryHttpExchangeRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Actuator wiring that Spring Boot intentionally leaves to the application.
 *
 * <p>The {@code httpexchanges} endpoint records nothing — and is not even created — unless an
 * {@link HttpExchangeRepository} bean is present. Spring Boot stopped auto-registering one because
 * captured exchanges can hold sensitive request/response data. Registering an in-memory ring buffer
 * (default capacity 100) makes the admin-only endpoint return the most recent exchanges. Recording
 * runs on every request, so it can be switched off with
 * {@code management.httpexchanges.recording.enabled=false} (on by default). Which fields are captured
 * is controlled by {@code management.httpexchanges.recording.include} in {@code application.yaml};
 * Cookie/Authorization headers and the session id are deliberately excluded there so the endpoint
 * cannot surface live {@code auth_token} sessions.
 *
 * <p>The {@code env} endpoint runs with {@code show-values=WHEN_AUTHORIZED}, so it reveals property
 * values to authorized admins. Modern Spring Boot no longer masks credentials automatically and the
 * old {@code keys-to-sanitize} property is gone, so all masking here is supplied by two
 * {@link SanitizingFunction} beans: {@link #environmentValueSanitizer()} masks a value when its key
 * is credential-shaped, and {@link #urlCredentialSanitizer()} strips {@code user:pass@} userinfo from
 * any string value containing a URL. The URL pass runs on every property; when the key is already
 * credential-shaped the value has been fully masked by the first sanitizer, so it is only observable
 * for non-credential-shaped keys such as a store or Solr URL with embedded credentials.
 */
@Configuration
public class ActuatorConfiguration {

	// Matches "user:pass@", bare "user@", or ":pass@" userinfo between a scheme's "://" and the host.
	private static final Pattern URL_USERINFO = Pattern.compile("://[^/@\\s:]*(?::[^/@\\s]*)?@");

	// Recording runs on every request; allow operators to switch it off (and with it the endpoint)
	// via management.httpexchanges.recording.enabled=false. On by default so the endpoint works out
	// of the box; the same flag also gates Spring Boot's own HttpExchangesFilter.
	@Bean
	@ConditionalOnProperty(prefix = "management.httpexchanges.recording", name = "enabled", matchIfMissing = true)
	public HttpExchangeRepository httpExchangeRepository() {
		return new InMemoryHttpExchangeRepository();
	}

	@Bean
	public SanitizingFunction environmentValueSanitizer() {
		return SanitizingFunction.sanitizeValue().ifKeyMatches(ActuatorConfiguration::isSensitiveEnvironmentKey);
	}

	@Bean
	public SanitizingFunction urlCredentialSanitizer() {
		return data -> {
			if (data.getValue() instanceof String value) {
				String redacted = redactUrlUserInfo(value);
				if (!redacted.equals(value)) {
					return data.withValue(redacted);
				}
			}
			return data;
		};
	}

	/**
	 * Keys whose {@code env} values must stay masked even when values are shown to admins. Covers the
	 * conventional credential suffixes, any key containing {@code credentials}, and the one case the
	 * conventional shapes miss: {@code entrystore.auth.adminpw} (the admin-password override, whose key
	 * ends in {@code adminpw}, not {@code password}).
	 *
	 * <p>The JVM-argument carriers ({@code sun.java.command}, {@code JAVA_TOOL_OPTIONS}) are deliberately
	 * NOT masked: on an admin-only endpoint they are shareable operational/tuning data, and the only
	 * secret they could expose is one an operator chose to pass on the command line — masking them would
	 * hide the very tuning info {@code /env} exists to surface.
	 */
	static boolean isSensitiveEnvironmentKey(String key) {
		String lowerKey = key.toLowerCase(Locale.ROOT);
		return lowerKey.endsWith("password")
				|| lowerKey.endsWith("secret")
				|| lowerKey.endsWith("token")
				|| lowerKey.endsWith("key")
				|| lowerKey.endsWith("adminpw")
				|| lowerKey.contains("credentials");
	}

	/**
	 * Replaces {@code user:pass@} or bare {@code user@} userinfo in any URL within {@code value} with
	 * {@code ******@}, leaving the rest of the URL visible. Connection URLs (an HTTP/SPARQL store or Solr URL) can
	 * embed backend credentials in the value even when the property key is not credential-shaped, so
	 * key-based masking alone would expose them under {@code /env}.
	 */
	static String redactUrlUserInfo(String value) {
		return URL_USERINFO.matcher(value).replaceAll("://******@");
	}
}
