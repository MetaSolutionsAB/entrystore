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

import org.entrystore.User;
import org.entrystore.rest.springboot.filter.ReloadUserPropertiesFilter;
import org.entrystore.rest.springboot.model.auth.SessionInfo;
import org.entrystore.rest.springboot.service.auth.LoginAttemptService;
import org.entrystore.rest.springboot.util.ErrorResponseWriter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;

class FormLoginAuthenticationSuccessHandlerTest {

	private final AuthTokenCookies authTokenCookies = spy(AuthTokenCookiesTest.authTokenCookies("3600", true));
	private final FormLoginAuthenticationSuccessHandler handler = new FormLoginAuthenticationSuccessHandler(
			mock(LoginAttemptService.class), authTokenCookies, new ErrorResponseWriter(JsonMapper.builder().build()));
	private MockHttpServletRequest request;
	private Authentication authentication;

	@BeforeEach
	void authenticate() {
		request = new MockHttpServletRequest("POST", "/auth/cookie");
		request.setParameter("auth_username", "Alice");
		var springUser = new org.springframework.security.core.userdetails.User(
				"https://example.org/store/_principals/resource/7", "",
				List.of(new SimpleGrantedAuthority("ROLE_USER")));
		var principal = new ESUserSessionDetails(springUser, mock(User.class),
				SessionInfo.builder().userName("alice").build());
		authentication = new UsernamePasswordAuthenticationToken(principal, "", principal.getAuthorities());
		SecurityContextHolder.getContext().setAuthentication(authentication);
	}

	@AfterEach
	void clearContext() {
		SecurityContextHolder.clearContext();
	}

	@Test
	void login_recordsTheSessionInfo() throws Exception {
		var response = new MockHttpServletResponse();

		handler.onAuthenticationSuccess(request, response, authentication);

		assertEquals(200, response.getStatus());
		var principal = (ESUserSessionDetails) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
		assertEquals("alice", principal.getSessionInfo().userName());
		assertNotNull(principal.getSessionInfo().loginTime());
		assertNotNull(principal.getSessionInfo().loginExpiration());
		var reloadClock = request.getSession().getAttribute(ReloadUserPropertiesFilter.LAST_RELOAD_ATTRIBUTE);
		assertNotNull(assertInstanceOf(AtomicReference.class, reloadClock).get());
	}

	@Test
	void sessionInfoThatCannotBeRecorded_failsTheLoginAndEndsTheSession() throws Exception {
		doThrow(new IllegalStateException("broken")).when(authTokenCookies).expiry(any());
		var session = (MockHttpSession) request.getSession();
		var response = new MockHttpServletResponse();

		handler.onAuthenticationSuccess(request, response, authentication);

		assertEquals(500, response.getStatus());
		assertNull(SecurityContextHolder.getContext().getAuthentication());
		assertTrue(session.isInvalid());
		List<String> setCookies = response.getHeaders("Set-Cookie");
		assertTrue(setCookies.stream().anyMatch(c -> c.startsWith("auth_token=;") && c.contains("Path=/store/")
				&& c.contains("Max-Age=0")), setCookies.toString());
	}
}
