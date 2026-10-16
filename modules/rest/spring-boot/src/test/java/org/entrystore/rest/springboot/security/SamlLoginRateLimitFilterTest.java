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

package org.entrystore.rest.springboot.security;

import com.github.benmanes.caffeine.cache.Ticker;
import org.entrystore.rest.springboot.configuration.SamlCustomConfiguration;
import org.entrystore.rest.springboot.filter.CacheControlFilter;
import org.entrystore.rest.springboot.service.SamlAuthService;
import org.entrystore.rest.springboot.service.auth.SamlLoginRateLimiter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Each client may start one login here; the second start must be redirected before Spring writes the request caches.
 */
class SamlLoginRateLimitFilterTest {

	private static final String WHITELISTED_FAILURE_URL = "https://app.example.org/login-failed";
	private static final String LOGIN_START = "/saml2/authenticate/keycloak";

	private final SamlCustomConfiguration samlConfiguration = new SamlCustomConfiguration(true, null,
			List.of("app.example.org"), Map.of(), null, new SamlCustomConfiguration.RedirectUrl("/auth/failed"), null);
	private final SamlLoginRateLimitFilter filter = new SamlLoginRateLimitFilter(
			new SamlLoginRateLimiter(1, Duration.ofHours(1), Ticker.systemTicker()), new SamlAuthService(samlConfiguration));

	@Test
	void loginStartWithinTheLimitContinues() throws Exception {
		var chain = new MockFilterChain();
		var response = new MockHttpServletResponse();

		filter.doFilter(request(LOGIN_START, null), response, chain);

		assertNotNull(chain.getRequest());
		assertNull(response.getRedirectedUrl());
	}

	@Test
	void loginStartOverTheLimitRedirectsToTheWhitelistedFailureUrl() throws Exception {
		filter.doFilter(request(LOGIN_START, null), new MockHttpServletResponse(), new MockFilterChain());
		var chain = new MockFilterChain();
		var response = new MockHttpServletResponse();

		filter.doFilter(request(LOGIN_START, "failureurl=" + encode(WHITELISTED_FAILURE_URL)), response, chain);

		assertNull(chain.getRequest(), "the request caches must not be written");
		assertEquals(WHITELISTED_FAILURE_URL, response.getRedirectedUrl());
		assertEquals(CacheControlFilter.CACHE_CONTROL_CREDENTIALS, response.getHeader(HttpHeaders.CACHE_CONTROL));
	}

	@Test
	void loginStartOverTheLimitWithAFailureUrlOffTheWhitelistRedirectsToTheDefault() throws Exception {
		filter.doFilter(request(LOGIN_START, null), new MockHttpServletResponse(), new MockFilterChain());
		var response = new MockHttpServletResponse();

		filter.doFilter(request(LOGIN_START, "failureurl=" + encode("https://evil.example.com/phishing")), response,
				new MockFilterChain());

		assertEquals("/auth/failed", response.getRedirectedUrl());
	}

	// A body parameter is never read: on a multipart POST that would make Jetty spool the upload first.
	@Test
	void overTheLimitNoParameterIsReadFromAMultipartBody() throws Exception {
		filter.doFilter(request(LOGIN_START, null), new MockHttpServletResponse(), new MockFilterChain());
		var overLimit = new MockHttpServletRequest("POST", LOGIN_START) {
			@Override
			public String getParameter(String name) {
				throw new AssertionError("parameter '" + name + "' read from a multipart request");
			}
		};
		overLimit.setContentType("multipart/form-data; boundary=x");
		overLimit.setQueryString("failureurl=" + encode(WHITELISTED_FAILURE_URL));
		overLimit.setRemoteAddr("192.0.2.1");
		var response = new MockHttpServletResponse();

		filter.doFilter(overLimit, response, new MockFilterChain());

		assertEquals(WHITELISTED_FAILURE_URL, response.getRedirectedUrl());
	}

	@Test
	void queryFormOfTheLoginStartIsCountedToo() throws Exception {
		filter.doFilter(request("/saml2/authenticate", "registrationId=keycloak"), new MockHttpServletResponse(),
				new MockFilterChain());
		var chain = new MockFilterChain();

		filter.doFilter(request(LOGIN_START, null), new MockHttpServletResponse(), chain);

		assertNull(chain.getRequest());
	}

	@Test
	void anotherClientHasItsOwnBudget() throws Exception {
		filter.doFilter(request(LOGIN_START, null), new MockHttpServletResponse(), new MockFilterChain());
		var otherClient = request(LOGIN_START, null);
		otherClient.setRemoteAddr("192.0.2.2");
		var chain = new MockFilterChain();

		filter.doFilter(otherClient, new MockHttpServletResponse(), chain);

		assertNotNull(chain.getRequest());
	}

	@ParameterizedTest(name = "{0} is not counted")
	@ValueSource(strings = {"/auth/saml", "/login/saml2/sso/keycloak", "/saml2/service-provider-metadata/keycloak",
			"/saml2/authenticatex"})
	void otherPathsAreNotCounted(String path) throws Exception {
		filter.doFilter(request(path, null), new MockHttpServletResponse(), new MockFilterChain());
		var chain = new MockFilterChain();

		filter.doFilter(request(path, null), new MockHttpServletResponse(), chain);

		assertNotNull(chain.getRequest());
	}

	private static MockHttpServletRequest request(String path, String query) {
		var request = new MockHttpServletRequest("GET", path);
		request.setQueryString(query);
		request.setRemoteAddr("192.0.2.1");
		return request;
	}

	private static String encode(String value) {
		return URLEncoder.encode(value, StandardCharsets.UTF_8);
	}
}
