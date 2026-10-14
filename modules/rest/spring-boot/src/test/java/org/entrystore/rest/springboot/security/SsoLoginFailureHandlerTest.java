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

import org.apache.logging.log4j.Level;
import org.entrystore.rest.springboot.util.CapturingAppender;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.saml2.core.Saml2Error;
import org.springframework.security.saml2.core.Saml2ErrorCodes;
import org.springframework.security.saml2.provider.service.authentication.Saml2AuthenticationException;
import org.springframework.security.web.WebAttributes;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class SsoLoginFailureHandlerTest {

	// An expired authentication request fails the login; at DEBUG, as Spring logs it, nobody would see why.
	@Test
	void failure_isLoggedAtWarnAndRedirectsToTheDefaultFailureUrl() throws Exception {
		var request = new MockHttpServletRequest("POST", "/store/login/saml2/sso/keycloak");
		request.setContextPath("/store");
		var response = new MockHttpServletResponse();
		var exception = new Saml2AuthenticationException(
				new Saml2Error(Saml2ErrorCodes.INVALID_IN_RESPONSE_TO, "No saved authentication request"));

		try (var appender = CapturingAppender.attachTo(SsoLoginFailureHandler.class)) {
			new SsoLoginFailureHandler("SAML", "/auth/user").onAuthenticationFailure(request, response, exception);

			assertEquals(List.of(
					"SAML authentication failed at '/store/login/saml2/sso/keycloak': No saved authentication request"),
					appender.messagesAt(Level.WARN).toList(), appender::toString);
		}
		assertEquals("/store/auth/user", response.getRedirectedUrl());
	}

	// Anonymous junk POSTs to the ACS endpoint must not write a stack trace at WARN each.
	@Test
	void failure_stackTraceIsLoggedAtDebugOnly() throws Exception {
		var request = new MockHttpServletRequest("POST", "/login/saml2/sso/keycloak");
		var exception = new Saml2AuthenticationException(
				new Saml2Error(Saml2ErrorCodes.INVALID_SIGNATURE, "Invalid signature"));

		try (var appender = CapturingAppender.attachTo(SsoLoginFailureHandler.class)) {
			new SsoLoginFailureHandler("SAML", "/auth/user")
					.onAuthenticationFailure(request, new MockHttpServletResponse(), exception);

			assertEquals(List.of(), appender.thrownAt(Level.WARN).toList(), appender::toString);
			assertEquals(List.of(exception), appender.thrownAt(Level.DEBUG).toList(), appender::toString);
		}
	}

	// Spring quotes response values such as the issuer; an anonymous ACS POST must not forge a log line with them.
	@Test
	void failureMessage_withLineBreaks_isLoggedOnOneLine() throws Exception {
		var request = new MockHttpServletRequest("POST", "/login/saml2/sso/keycloak");
		var exception = new Saml2AuthenticationException(new Saml2Error(Saml2ErrorCodes.INVALID_ISSUER,
				"Invalid issuer [x]\n2026-10-05 INFO Admin login succeeded"));

		try (var appender = CapturingAppender.attachTo(SsoLoginFailureHandler.class)) {
			new SsoLoginFailureHandler("SAML", "/auth/user")
					.onAuthenticationFailure(request, new MockHttpServletResponse(), exception);

			assertEquals(List.of(
					"SAML authentication failed at '/login/saml2/sso/keycloak': "
							+ "Invalid issuer [x]?2026-10-05 INFO Admin login succeeded"),
					appender.messagesAt(Level.WARN).toList(), appender::toString);
		}
	}

	// The CAS and OIDC callbacks are anonymous; a session started on failure would set an auth_token cookie.
	@Test
	void failure_withoutSession_startsNoSession() throws Exception {
		var request = new MockHttpServletRequest("GET", "/auth/cas");
		var response = new MockHttpServletResponse();

		new SsoLoginFailureHandler("CAS", "/auth/login").onAuthenticationFailure(request, response, noTicket());

		assertNull(request.getSession(false));
		assertEquals("/auth/login", response.getRedirectedUrl());
	}

	// A failed login in a browser that is already logged in keeps its session, which still receives the exception.
	@Test
	void failure_withSession_keepsTheExceptionInTheSession() throws Exception {
		var request = new MockHttpServletRequest("GET", "/auth/cas");
		var session = new MockHttpSession();
		request.setSession(session);
		var exception = noTicket();

		new SsoLoginFailureHandler("CAS", "/auth/login")
				.onAuthenticationFailure(request, new MockHttpServletResponse(), exception);

		assertSame(exception, session.getAttribute(WebAttributes.AUTHENTICATION_EXCEPTION));
	}

	private static BadCredentialsException noTicket() {
		return new BadCredentialsException("Failed to provide a CAS service ticket to validate");
	}
}
