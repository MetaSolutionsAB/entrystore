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

package org.entrystore.rest.springboot.filter;

import org.entrystore.User;
import org.entrystore.rest.springboot.model.auth.SessionInfo;
import org.entrystore.rest.springboot.security.AuthTokenCookies;
import org.entrystore.rest.springboot.security.ESUserDetailsService;
import org.entrystore.rest.springboot.security.ESUserSessionDetails;
import org.entrystore.rest.springboot.util.ErrorResponseWriter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.session.SessionInformation;
import org.springframework.security.core.session.SessionRegistryImpl;

import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ReloadUserPropertiesFilterTest {

	private static final String USERNAME = "https://example.org/store/_principals/resource/7";

	private final ESUserDetailsService userDetailsService = mock(ESUserDetailsService.class);
	private final SessionRegistryImpl sessionRegistry = new SessionRegistryImpl();
	private final AuthTokenCookies authTokenCookies = mock(AuthTokenCookies.class);
	private final ReloadUserPropertiesFilter filter = new ReloadUserPropertiesFilter(userDetailsService,
			sessionRegistry, new ErrorResponseWriter(JsonMapper.builder().build()), authTokenCookies);
	private MockHttpServletRequest request;
	private String sessionId;

	@BeforeEach
	void logIn() {
		lenient().when(authTokenCookies.expiry(any())).thenReturn(Instant.now().plusSeconds(3600));
		ESUserSessionDetails loggedIn = sessionDetails();
		request = new MockHttpServletRequest();
		sessionId = request.getSession().getId();
		sessionRegistry.registerNewSession(sessionId, loggedIn);
		SecurityContextHolder.getContext().setAuthentication(
				new UsernamePasswordAuthenticationToken(loggedIn, "", loggedIn.getAuthorities()));
	}

	@AfterEach
	void clearContext() {
		SecurityContextHolder.clearContext();
	}

	@Test
	void request_updatesTheSessionInfoOfTheRegisteredSession() throws Exception {
		SessionInformation registered = sessionRegistry.getSessionInformation(sessionId);
		ESUserSessionDetails reloaded = sessionDetails();
		when(userDetailsService.loadUserByUsername(USERNAME)).thenReturn(reloaded);
		var chain = new MockFilterChain();

		filter.doFilter(request, new MockHttpServletResponse(), chain);

		assertSame(registered, sessionRegistry.getSessionInformation(sessionId));
		var registeredUser = (ESUserSessionDetails) registered.getPrincipal();
		assertSame(reloaded.getSessionInfo(), registeredUser.getSessionInfo());
		assertNotNull(registeredUser.getSessionInfo().lastAccessTime());
		assertFalse(registered.isExpired());
		assertNotNull(chain.getRequest());
	}

	@Test
	void sessionRevokedWhileTheRequestRuns_staysRevoked() throws Exception {
		// e.g. an admin disables and re-enables the user, or a concurrent request deletes the token
		when(userDetailsService.loadUserByUsername(USERNAME)).thenAnswer(invocation -> {
			sessionRegistry.getSessionInformation(sessionId).expireNow();
			return sessionDetails();
		});

		filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

		assertTrue(sessionRegistry.getSessionInformation(sessionId).isExpired());
	}

	@Test
	void unregisteredSession_isRegistered() throws Exception {
		sessionRegistry.removeSessionInformation(sessionId);
		ESUserSessionDetails reloaded = sessionDetails();
		when(userDetailsService.loadUserByUsername(USERNAME)).thenReturn(reloaded);

		filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

		assertSame(reloaded, sessionRegistry.getSessionInformation(sessionId).getPrincipal());
	}

	private static ESUserSessionDetails sessionDetails() {
		var springUser = new org.springframework.security.core.userdetails.User(
				USERNAME, "", List.of(new SimpleGrantedAuthority("ROLE_USER")));
		return new ESUserSessionDetails(springUser, mock(User.class), SessionInfo.builder().userName("alice").build());
	}
}
