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
import org.apache.logging.log4j.Level;
import org.entrystore.rest.springboot.configuration.OidcCustomConfiguration;
import org.entrystore.rest.springboot.filter.CacheControlFilter;
import org.entrystore.rest.springboot.model.auth.AuthState;
import org.entrystore.rest.springboot.service.OidcAuthService;
import org.entrystore.rest.springboot.service.auth.OidcAuthStateCache;
import org.entrystore.rest.springboot.util.CapturingAppender;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * An OIDC callback Spring rejects must land on the {@code failureurl} the caller gave at login start, read from the
 * whitelist-validated {@code state}-keyed cache, never from the request.
 */
class OidcLoginFailureHandlerTest {

	private static final String WHITELISTED_FAILURE_URL = "https://app.example.org/login-failed";
	private static final String STATE = "oauth2-state";

	private final OidcCustomConfiguration oidcConfiguration = new OidcCustomConfiguration(true, null,
			List.of("app.example.org"), Map.of(), null, new OidcCustomConfiguration.RedirectUrl("/auth/failed"));
	private final OidcAuthStateCache authStateCache = new OidcAuthStateCache(Ticker.systemTicker());
	private final OidcLoginFailureHandler handler =
			new OidcLoginFailureHandler(new OidcAuthService(oidcConfiguration), authStateCache, oidcConfiguration);

	@Test
	void idpError_redirectsToTheCallersFailureUrl_andLogsAtWarn() throws Exception {
		authStateCache.storeAuthState(STATE, new AuthState(null, WHITELISTED_FAILURE_URL));
		var response = new MockHttpServletResponse();

		try (var appender = CapturingAppender.attachTo(SsoLoginFailureHandler.class)) {
			handler.onAuthenticationFailure(callback(STATE), response, accessDenied());

			assertEquals(List.of(
					"OIDC authentication failed at '/store/login/oauth2/code/keycloak': [access_denied] User denied"),
					appender.messagesAt(Level.WARN).toList(), appender::toString);
		}
		assertEquals(WHITELISTED_FAILURE_URL, response.getRedirectedUrl());
		assertEquals(CacheControlFilter.CACHE_CONTROL_CREDENTIALS, response.getHeader(HttpHeaders.CACHE_CONTROL));
	}

	// The resolver drops non-whitelisted URLs before storing, so this guards a whitelist change after the store.
	@Test
	void cachedFailureUrlNoLongerWhitelisted_fallsBackToTheDefault() throws Exception {
		authStateCache.storeAuthState(STATE, new AuthState(null, "https://evil.example.com/phishing"));
		var response = new MockHttpServletResponse();

		try (var appender = CapturingAppender.attachTo(OidcLoginFailureHandler.class)) {
			handler.onAuthenticationFailure(callback(STATE), response, accessDenied());

			assertEquals(
					List.of("Dropping non-whitelisted OIDC failure redirect URL: https://evil.example.com/phishing"),
					appender.messagesAt(Level.INFO).toList(), appender::toString);
		}
		assertEquals("/store/auth/failed", response.getRedirectedUrl());
		assertEquals(CacheControlFilter.CACHE_CONTROL_CREDENTIALS, response.getHeader(HttpHeaders.CACHE_CONTROL));
	}

	// The callback is an anonymous GET: a failureurl on it must never be followed, even a whitelisted one.
	@Test
	void failureUrlOnTheCallbackRequest_isIgnored() throws Exception {
		var request = callback("never-issued");
		request.setParameter("failureurl", WHITELISTED_FAILURE_URL);
		var response = new MockHttpServletResponse();

		handler.onAuthenticationFailure(request, response, accessDenied());

		assertEquals("/store/auth/failed", response.getRedirectedUrl());
	}

	@Test
	void loginStartedWithOnlyASuccessUrl_redirectsToTheDefault() throws Exception {
		authStateCache.storeAuthState(STATE, new AuthState("https://app.example.org/welcome", null));
		var response = new MockHttpServletResponse();

		handler.onAuthenticationFailure(callback(STATE), response, accessDenied());

		assertEquals("/store/auth/failed", response.getRedirectedUrl());
	}

	// An expired login (request lifetime passed) has no entry left.
	@Test
	void unknownState_redirectsToTheDefault() throws Exception {
		var response = new MockHttpServletResponse();

		handler.onAuthenticationFailure(callback("expired-state"), response, accessDenied());

		assertEquals("/store/auth/failed", response.getRedirectedUrl());
	}

	@Test
	void missingState_redirectsToTheDefault() throws Exception {
		var response = new MockHttpServletResponse();

		handler.onAuthenticationFailure(callback(null), response, accessDenied());

		assertEquals("/store/auth/failed", response.getRedirectedUrl());
	}

	private static MockHttpServletRequest callback(String state) {
		var request = new MockHttpServletRequest("GET", "/store/login/oauth2/code/keycloak");
		request.setContextPath("/store");
		if (state != null) {
			request.setParameter("state", state);
		}
		return request;
	}

	// As Spring's OIDC provider throws it for an error response from the IdP.
	private static OAuth2AuthenticationException accessDenied() {
		var error = new OAuth2Error("access_denied", "User denied", null);
		return new OAuth2AuthenticationException(error, error.toString());
	}
}
