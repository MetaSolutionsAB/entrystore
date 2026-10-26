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
import org.entrystore.rest.springboot.configuration.SamlCustomConfiguration;
import org.entrystore.rest.springboot.security.RefreshableRelyingPartyRegistrationRepository;
import org.entrystore.rest.springboot.service.SamlAuthService;
import org.entrystore.rest.springboot.util.CapturingAppender;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * GET /auth/saml: when the chosen IdP's metadata is unavailable the login cannot start, so the caller goes to the
 * failure URL as for any other failed SAML login; otherwise only whitelisted redirect URLs are passed on.
 */
class AuthControllerSamlLoginTest {

	private static final String WHITELISTED_FAILURE_URL = "https://app.example.org/login-failed";
	private static final String WHITELISTED_SUCCESS_URL = "https://app.example.org/welcome";

	private final RefreshableRelyingPartyRegistrationRepository registrations =
			mock(RefreshableRelyingPartyRegistrationRepository.class);

	@Test
	void unavailableIdp_redirectsToTheRequestedFailureUrl_whenWhitelisted() {
		when(registrations.isConfiguredButUnavailable("keycloak")).thenReturn(true);
		var redirectAttributes = new RedirectAttributesModelMap();

		String view = controller().startSamlLogin(null, "keycloak", WHITELISTED_SUCCESS_URL, WHITELISTED_FAILURE_URL,
				redirectAttributes);

		assertEquals("redirect:" + WHITELISTED_FAILURE_URL, view);
		assertTrue(redirectAttributes.isEmpty(), "no login parameters may be appended to the failure URL");
	}

	@Test
	void unavailableIdp_redirectsToTheDefaultFailureUrl_whenTheRequestedOneIsNotWhitelisted() {
		when(registrations.isConfiguredButUnavailable("keycloak")).thenReturn(true);

		try (var appender = CapturingAppender.attachTo(AuthController.class)) {
			String view = controller().startSamlLogin(null, "keycloak", null, "https://evil.example.com/",
					new RedirectAttributesModelMap());

			assertEquals("redirect:/auth/failed", view);
			assertEquals(List.of("Dropping non-whitelisted SAML failure redirect URL: https://evil.example.com/"),
					appender.messagesAt(Level.INFO).toList(), appender::toString);
		}
	}

	@Test
	void availableIdp_startsTheLoginAtTheIdp() {
		var redirectAttributes = new RedirectAttributesModelMap();

		try (var appender = CapturingAppender.attachTo(AuthController.class)) {
			String view = controller().startSamlLogin(null, "keycloak", WHITELISTED_SUCCESS_URL,
					WHITELISTED_FAILURE_URL, redirectAttributes);

			assertEquals("redirect:/saml2/authenticate/{idpId}", view);
			assertEquals(List.of(), appender.messagesAt(Level.INFO).toList(), appender::toString);
		}
		assertEquals(Map.of("idpId", "keycloak", "successurl", WHITELISTED_SUCCESS_URL,
				"failureurl", WHITELISTED_FAILURE_URL), redirectAttributes);
	}

	@Test
	void availableIdp_withoutRedirectUrls_passesOnlyTheIdp_andLogsNothing() {
		var redirectAttributes = new RedirectAttributesModelMap();

		try (var appender = CapturingAppender.attachTo(AuthController.class)) {
			controller().startSamlLogin(null, "keycloak", null, null, redirectAttributes);

			assertEquals(List.of(), appender.messagesAt(Level.INFO).toList(), appender::toString);
		}
		assertEquals(Map.of("idpId", "keycloak"), redirectAttributes);
	}

	// SamlRelayStateResolver never sees a URL dropped here, so the drop has to be logged here; the URL is the
	// caller's, so a line break in it must not start a forged log line.
	@Test
	void availableIdp_dropsNonWhitelistedRedirectUrls_andLogsAtInfo() {
		var redirectAttributes = new RedirectAttributesModelMap();

		try (var appender = CapturingAppender.attachTo(AuthController.class)) {
			controller().startSamlLogin(null, "keycloak", "https://evil.example.com/welcome\r\nforged",
					"https://evil.example.com/failed", redirectAttributes);

			assertEquals(List.of(
					"Dropping non-whitelisted SAML success redirect URL: https://evil.example.com/welcome??forged",
					"Dropping non-whitelisted SAML failure redirect URL: https://evil.example.com/failed"),
					appender.messagesAt(Level.INFO).toList(), appender::toString);
		}
		assertEquals(Map.of("idpId", "keycloak"), redirectAttributes);
	}

	private AuthController controller() {
		var samlConfiguration = new SamlCustomConfiguration(true, "keycloak", List.of("app.example.org"), Map.of(),
				null, new SamlCustomConfiguration.RedirectUrl("/auth/failed"), null);
		var controller = new AuthController(null, null, null, new SamlAuthService(samlConfiguration), null, null,
				Optional.empty(), Optional.empty(), Optional.of(registrations), null);
		ReflectionTestUtils.setField(controller, "isSamlAuthEnabled", true);
		return controller;
	}
}
