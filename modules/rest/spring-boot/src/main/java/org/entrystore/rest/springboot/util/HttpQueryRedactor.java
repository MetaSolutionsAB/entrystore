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

package org.entrystore.rest.springboot.util;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Replaces values of known sensitive parameters in an HTTP query string with
 * {@value #REDACTED} so the string is safe to write to application logs and to
 * embed in the Atom/RSS self-links produced by {@code SearchService}.
 * <p>
 * Parameter-name comparison is case-insensitive and percent-decoded: a request
 * like {@code ?%63onfirm=…} matches the sensitive name {@code confirm} even
 * though the raw substring before the first {@code =} reads {@code %63onfirm}.
 * The on-the-wire name is preserved in the output so operators see exactly
 * what the client sent, only the value is replaced. Values are never decoded
 * — base64- or percent-encoded tokens never round-trip through a decoder
 * during redaction.
 * <p>
 * Pair separators recognised: {@code &} (RFC 3986) and {@code ;} (legacy HTML
 * 4 / RFC 1866). The output normalises to {@code &} regardless of the input
 * separator, which is acceptable for the log/feed-link sinks. Malformed
 * pairs are passed through unchanged — best-effort, never throws.
 */
public final class HttpQueryRedactor {

	static final String REDACTED = "***";

	/**
	 * Names whose values are always replaced. Stored lowercase for the
	 * case-insensitive comparison via {@link String#toLowerCase(Locale)} with
	 * {@link Locale#ROOT}. Covers EntryStore's actual auth surface
	 * (password-reset and signup confirmation tokens, form-login password,
	 * SAML 2.0 {@code RelayState}, CAS {@code ticket}) plus defence-in-depth
	 * entries for credential-bearing names from related frameworks
	 * ({@code token}, {@code secret}, {@code code}, {@code state},
	 * {@code api_key}, {@code apikey}) that are not currently accepted as
	 * query parameters by any EntryStore endpoint but would slip into logs
	 * the moment one were added.
	 */
	private static final Set<String> SENSITIVE_NAMES = Set.of(
			"confirm",          // password-reset + signup confirmation tokens
			"token",            // generic
			"password",         // never accepted as a query param today
			"auth_password",    // form-login parameter name
			"secret",
			"code",
			"state",
			"relaystate",       // SAML 2.0 relay-state correlation token
			"ticket",           // CAS service ticket
			"api_key",
			"apikey"
	);

	private HttpQueryRedactor() {
	}

	public static String redact(String rawQuery) {
		if (rawQuery == null || rawQuery.isEmpty()) {
			return rawQuery;
		}
		// split on either separator with limit=-1 so trailing/empty segments are preserved
		// (round-trip "a=1&" through join('&') reproduces the trailing separator).
		return Arrays.stream(rawQuery.split("[&;]", -1))
				.map(HttpQueryRedactor::redactPair)
				.collect(Collectors.joining("&"));
	}

	private static String redactPair(String pair) {
		int eq = pair.indexOf('=');
		if (eq < 0) {
			// No '=' — not a name=value pair, pass through.
			return pair;
		}
		String name = pair.substring(0, eq);
		if (SENSITIVE_NAMES.contains(decodeName(name).toLowerCase(Locale.ROOT))) {
			return name + "=" + REDACTED;
		}
		return pair;
	}

	private static String decodeName(String rawName) {
		try {
			return URLDecoder.decode(rawName, StandardCharsets.UTF_8);
		} catch (IllegalArgumentException e) {
			// Malformed %XX escape — fall back to raw name so the redactor never throws.
			return rawName;
		}
	}
}
