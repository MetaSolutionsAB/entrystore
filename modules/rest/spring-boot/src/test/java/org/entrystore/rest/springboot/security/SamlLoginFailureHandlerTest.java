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
import org.entrystore.rest.springboot.configuration.SamlCustomConfiguration;
import org.entrystore.rest.springboot.filter.CacheControlFilter;
import org.entrystore.rest.springboot.model.auth.AuthState;
import org.entrystore.rest.springboot.service.SamlAuthService;
import org.entrystore.rest.springboot.service.auth.SamlAuthStateCache;
import org.entrystore.rest.springboot.util.CapturingAppender;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.saml2.core.Saml2Error;
import org.springframework.security.saml2.core.Saml2ErrorCodes;
import org.springframework.security.saml2.provider.service.authentication.Saml2AuthenticationException;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A SAML response Spring rejects must land on the {@code failureurl} the caller gave at login start (5.x contract),
 * read from the whitelist-validated relay-state cache, never from the request.
 */
class SamlLoginFailureHandlerTest {

	private static final String WHITELISTED_FAILURE_URL = "https://app.example.org/login-failed";
	private static final String RELAY_STATE = "relay-state-token";

	private final SamlCustomConfiguration samlConfiguration = new SamlCustomConfiguration(true, null,
			List.of("app.example.org"), Map.of(), null, new SamlCustomConfiguration.RedirectUrl("/auth/failed"), null);
	private final SamlAuthStateCache authStateCache = new SamlAuthStateCache(samlConfiguration, Ticker.systemTicker());
	private final SamlLoginFailureHandler handler =
			new SamlLoginFailureHandler(new SamlAuthService(samlConfiguration), authStateCache, samlConfiguration);

	@Test
	void rejectedResponse_redirectsToTheCallersFailureUrl_andLogsAtWarn() throws Exception {
		authStateCache.storeAuthState(RELAY_STATE, new AuthState(null, WHITELISTED_FAILURE_URL));
		handler.setRedirectStrategy(new CacheAwareRedirectStrategy());
		var response = new MockHttpServletResponse();

		try (var appender = CapturingAppender.attachTo(SsoLoginFailureHandler.class)) {
			handler.onAuthenticationFailure(acsPost(RELAY_STATE), response, invalidSignature());

			assertEquals(List.of("SAML authentication failed at '/store/login/saml2/sso/keycloak': Invalid signature"),
					appender.messagesAt(Level.WARN).toList(), appender::toString);
		}
		assertEquals(WHITELISTED_FAILURE_URL, response.getRedirectedUrl());
		assertEquals(CacheControlFilter.CACHE_CONTROL_AUTHENTICATED, response.getHeader(HttpHeaders.CACHE_CONTROL));
	}

	// The resolver drops non-whitelisted URLs before storing, so this guards a whitelist change after the store.
	@Test
	void cachedFailureUrlNoLongerWhitelisted_fallsBackToTheDefault() throws Exception {
		authStateCache.storeAuthState(RELAY_STATE, new AuthState(null, "https://evil.example.com/phishing"));
		var response = new MockHttpServletResponse();

		handler.onAuthenticationFailure(acsPost(RELAY_STATE), response, invalidSignature());

		assertEquals("/store/auth/failed", response.getRedirectedUrl());
	}

	// An expired login (request-lifetime passed) has no entry, like a response that carries no RelayState.
	@Test
	void unknownOrMissingRelayState_redirectsToTheDefault() throws Exception {
		var unknown = new MockHttpServletResponse();
		var missing = new MockHttpServletResponse();

		handler.onAuthenticationFailure(acsPost("expired-token"), unknown, invalidSignature());
		handler.onAuthenticationFailure(acsPost(null), missing, invalidSignature());

		assertEquals("/store/auth/failed", unknown.getRedirectedUrl());
		assertEquals("/store/auth/failed", missing.getRedirectedUrl());
	}

	private static MockHttpServletRequest acsPost(String relayState) {
		var request = new MockHttpServletRequest("POST", "/store/login/saml2/sso/keycloak");
		request.setContextPath("/store");
		if (relayState != null) {
			request.setParameter("RelayState", relayState);
		}
		return request;
	}

	private static Saml2AuthenticationException invalidSignature() {
		return new Saml2AuthenticationException(new Saml2Error(Saml2ErrorCodes.INVALID_SIGNATURE, "Invalid signature"));
	}
}
