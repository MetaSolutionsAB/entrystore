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

package org.entrystore.rest.springboot.controller;

import org.apache.logging.log4j.Level;
import org.entrystore.rest.springboot.configuration.OidcCustomConfiguration;
import org.entrystore.rest.springboot.service.OidcAuthService;
import org.entrystore.rest.springboot.util.CapturingAppender;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

/**
 * GET /auth/oidc passes only whitelisted redirect URLs on to the authorization endpoint.
 */
class AuthControllerOidcLoginTest {

	private static final String WHITELISTED_FAILURE_URL = "https://app.example.org/login-failed";
	private static final String WHITELISTED_SUCCESS_URL = "https://app.example.org/welcome";

	@Test
	void whitelistedRedirectUrls_arePassedToTheAuthorizationEndpoint() {
		var redirectAttributes = new RedirectAttributesModelMap();

		try (var appender = CapturingAppender.attachTo(AuthController.class)) {
			String view = controller().startOidcLogin(null, "keycloak", WHITELISTED_SUCCESS_URL,
					WHITELISTED_FAILURE_URL, redirectAttributes);

			assertEquals("redirect:/oauth2/authorization/{providerId}", view);
			assertEquals(List.of(), appender.messagesAt(Level.INFO).toList(), appender::toString);
		}
		assertEquals(Map.of("providerId", "keycloak", "successurl", WHITELISTED_SUCCESS_URL,
				"failureurl", WHITELISTED_FAILURE_URL), redirectAttributes);
	}

	// OidcAuthorizationRequestResolver never sees a URL dropped here, so the drop has to be logged here; the URL is
	// the caller's, so a line break in it must not start a forged log line.
	@Test
	void nonWhitelistedRedirectUrls_areDropped_andLoggedAtInfo() {
		var redirectAttributes = new RedirectAttributesModelMap();

		try (var appender = CapturingAppender.attachTo(AuthController.class)) {
			controller().startOidcLogin(null, "keycloak", "https://evil.example.com/welcome\r\nforged",
					"https://evil.example.com/failed", redirectAttributes);

			assertEquals(List.of(
					"Dropping non-whitelisted OIDC success redirect URL: https://evil.example.com/welcome??forged",
					"Dropping non-whitelisted OIDC failure redirect URL: https://evil.example.com/failed"),
					appender.messagesAt(Level.INFO).toList(), appender::toString);
		}
		assertEquals(Map.of("providerId", "keycloak"), redirectAttributes);
	}

	private AuthController controller() {
		var oidcConfiguration = new OidcCustomConfiguration(true, null, List.of("app.example.org"), Map.of(), null,
				null);
		ClientRegistrationRepository registrations =
				id -> "keycloak".equals(id) ? mock(ClientRegistration.class) : null;
		var controller = new AuthController(null, null, null, null, new OidcAuthService(oidcConfiguration), null,
				Optional.empty(), Optional.of(registrations), Optional.empty(), null);
		ReflectionTestUtils.setField(controller, "isOidcAuthEnabled", true);
		return controller;
	}
}
