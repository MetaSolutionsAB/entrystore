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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.web.cors.CorsConfiguration;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Pins the origin-matching contract that moved from a hand-rolled prefix/suffix matcher onto
 * Spring's {@code allowedOriginPatterns}. The origin set mirrors {@code entrystore-it.properties}
 * so these cases and {@code CorsIT} stay in step.
 */
class EntryStoreCorsConfigurationSourceTest {

	private static final String ORIGINS = "http://example.com,http://other.com,*.test.example.com,http://prefix.*";
	private static final String CREDENTIAL_ORIGINS = "http://localhost:3000";

	@ParameterizedTest(name = "{0} -> allowed={1}, credentials={2}")
	@CsvSource({
			"http://example.com,          true,  false", // exact match on the plain list
			"http://other.com,            true,  false", // second exact match
			"http://app.test.example.com, true,  false", // *.suffix pattern
			"http://prefix.anything.com,  true,  false", // prefix.* pattern
			"http://localhost:3000,       true,  true",  // on the credentials list only
			"http://EXAMPLE.com,          true,  false", // matching is case-insensitive, as before the move
			"http://disallowed.org,       false, false", // on neither list — no CORS handling at all
			"http://example.com.evil.net, false, false", // exact origin must not match a longer host
			"http://app.test.example.com.evil.net, false, false", // nor may the *.suffix pattern
	})
	void getCorsConfiguration_matchesConfiguredOrigins(String origin, boolean allowed, boolean credentials) {
		CorsConfiguration config = resolve(properties(ORIGINS, CREDENTIAL_ORIGINS), origin);

		if (!allowed) {
			assertNull(config, "disallowed origin must yield no configuration, so the request passes through untouched");
			return;
		}
		assertNotNull(config);
		assertEquals(origin, config.checkOrigin(origin), "the request origin must be echoed back, never '*'");
		assertEquals(credentials, Boolean.TRUE.equals(config.getAllowCredentials()));
	}

	@Test
	void getCorsConfiguration_bareWildcardOrigin_echoesRequestOrigin() {
		// A literal '*' in allowedOrigins would make checkOrigin throw once credentials are allowed;
		// as a pattern it matches and still echoes the concrete origin.
		CorsConfiguration config = resolve(properties("*", CREDENTIAL_ORIGINS), "http://anything.example");

		assertNotNull(config);
		assertEquals("http://anything.example", config.checkOrigin("http://anything.example"));
	}

	@Test
	void getCorsConfiguration_originOnBothLists_keepsAllowCredentials() {
		// The credentials list is consulted first on purpose. No other case here has an origin on both
		// lists, so nothing else would catch a swap of the two checkOrigin branches.
		CorsProperties bothLists = properties("http://example.com,http://localhost:3000", CREDENTIAL_ORIGINS);

		CorsConfiguration config = resolve(bothLists, "http://localhost:3000");

		assertNotNull(config);
		assertEquals(Boolean.TRUE, config.getAllowCredentials());
	}

	@Test
	void getCorsConfiguration_wildcardPlainList_stillAllowsCredentialsForCredentialOrigin() {
		// entrystore.cors.origins defaults to '*', which puts every credentialed origin on both lists —
		// the deployment shape where the precedence above actually decides the outcome.
		CorsConfiguration config = resolve(properties("*", CREDENTIAL_ORIGINS), "http://localhost:3000");

		assertNotNull(config);
		assertEquals(Boolean.TRUE, config.getAllowCredentials());
	}

	@Test
	void getCorsConfiguration_mixedCaseOrigin_pinsItOnACopyWithoutTouchingTheSharedPolicy() {
		// A mixed-case origin matches only after normalising, so its raw form is pinned onto a copy —
		// that is what makes DefaultCorsProcessor's second checkOrigin agree with the match here.
		// Pinning the shared startup policy instead would leak one request's origin into the next.
		var source = new EntryStoreCorsConfigurationSource(properties(ORIGINS, CREDENTIAL_ORIGINS));

		CorsConfiguration mixedCase = source.getCorsConfiguration(requestWithOrigin("http://EXAMPLE.com"));
		CorsConfiguration lowerCase = source.getCorsConfiguration(requestWithOrigin("http://other.com"));

		assertEquals(List.of("http://EXAMPLE.com"), mixedCase.getAllowedOrigins());
		assertNull(lowerCase.getAllowedOrigins(), "an already-lower-case origin matches the shared policy as-is");
		assertEquals("http://other.com", lowerCase.checkOrigin("http://other.com"));
	}

	@Test
	void getCorsConfiguration_interiorWildcardOnCredentialsList_isDroppedAndMatchesNothing() {
		// 'https://*.example.com' matched nothing under the old matcher (only '*', exact, '*suffix'
		// and 'prefix*' were honoured), but Spring treats every '*' as a wildcard — honouring it here
		// would grant Allow-Credentials: true to every https://<label>.example.com on upgrade. It is
		// dropped at startup instead, so it keeps matching nothing.
		CorsConfiguration config = resolve(properties(ORIGINS, "https://*.example.com"), "https://a.example.com");

		assertNull(config);
	}

	@Test
	void getCorsConfiguration_leadingAndTrailingStarPattern_isDroppedAndMatchesNothing() {
		// '*example.com*' is not a documented form either, and under Spring's pattern support it would
		// match http://example.com.evil.net. Its leading star slipped past the previous
		// interior-wildcard warning, which only looked at indexOf('*') > 0.
		CorsConfiguration config = resolve(properties("*example.com*", CREDENTIAL_ORIGINS), "http://example.com.evil.net");

		assertNull(config);
	}

	@Test
	void getCorsConfiguration_noOriginHeader_returnsNull() {
		assertNull(resolve(properties(ORIGINS, CREDENTIAL_ORIGINS), null));
	}

	@Test
	void getCorsConfiguration_corsDisabled_returnsNullForOtherwiseAllowedOrigin() {
		CorsProperties disabled = new CorsProperties("off", ORIGINS, CREDENTIAL_ORIGINS, "X-Custom-Header", 7200);

		assertNull(resolve(disabled, "http://example.com"));
	}

	@Test
	void getCorsConfiguration_headersAndMaxAge_appliedToBothAllowedAndExposed() {
		CorsConfiguration config = resolve(properties(ORIGINS, CREDENTIAL_ORIGINS), "http://example.com");

		assertNotNull(config);
		assertEquals(List.of("X-Custom-Header"), config.getAllowedHeaders());
		assertEquals(List.of("X-Custom-Header"), config.getExposedHeaders());
		assertEquals(Long.valueOf(7200L), config.getMaxAge());
		assertTrue(config.getAllowedMethods().containsAll(List.of("GET", "PUT", "POST", "DELETE", "OPTIONS", "HEAD")));
	}

	@Test
	void getCorsConfiguration_noCredentialOriginsConfigured_neverAllowsCredentials() {
		CorsConfiguration config = resolve(properties(ORIGINS, ""), "http://example.com");

		assertNotNull(config);
		assertEquals(Boolean.FALSE, config.getAllowCredentials());
	}

	private static CorsProperties properties(String origins, String credentialOrigins) {
		return new CorsProperties("on", origins, credentialOrigins, "X-Custom-Header", 7200);
	}

	private static CorsConfiguration resolve(CorsProperties properties, String origin) {
		return new EntryStoreCorsConfigurationSource(properties).getCorsConfiguration(requestWithOrigin(origin));
	}

	private static HttpServletRequest requestWithOrigin(String origin) {
		HttpServletRequest request = mock(HttpServletRequest.class);
		when(request.getHeader("Origin")).thenReturn(origin);
		return request;
	}
}
