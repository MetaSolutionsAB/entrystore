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

package org.entrystore.rest.springboot.service;

import org.entrystore.PrincipalManager;
import org.entrystore.User;
import org.entrystore.rest.springboot.model.auth.SessionInfo;
import org.entrystore.rest.springboot.security.ESUserSessionDetails;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.session.SessionInformation;

import java.net.URI;
import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TokenServiceTest {

	private static final URI USER_URI = URI.create("https://example.org/store/_principals/resource/7");

	private final PrincipalManager principalManager = mock(PrincipalManager.class);
	private final AuthService authService = mock(AuthService.class);
	private final TokenService tokenService = new TokenService(principalManager, authService);

	@BeforeEach
	void authenticate() {
		when(principalManager.getAuthenticatedUserURI()).thenReturn(USER_URI);
	}

	@Test
	void getTokens_formLoginSession_reportsTheInfoItsPrincipalCarriesByItsHandle() {
		SessionInfo formInfo = SessionInfo.builder().userName("alice").lastUsedUserAgent("Browser/1").build();
		sessionsOfTheUser(formLogin("form-id", "form-handle", formInfo));

		Map<String, SessionInfo> tokens = tokenService.getTokens(null);

		assertEquals(Map.of("form-handle", formInfo), tokens);
	}

	@Test
	void getTokens_marksOnlyTheRequestingSessionAsCurrent() {
		SessionInfo info = SessionInfo.builder().userName("alice").build();
		sessionsOfTheUser(formLogin("form-id", "form-handle", info), formLogin("other-id", "other-handle", info));

		Map<String, SessionInfo> tokens = tokenService.getTokens("other-id");

		assertTrue(tokens.get("other-handle").current());
		assertFalse(tokens.get("form-handle").current());
	}

	@Test
	void getTokens_requestWithoutSession_marksNoSessionAsCurrent() {
		SessionInfo info = SessionInfo.builder().userName("alice").build();
		sessionsOfTheUser(formLogin("form-id", "form-handle", info));

		Map<String, SessionInfo> tokens = tokenService.getTokens(null);

		assertFalse(tokens.get("form-handle").current());
	}

	@Test
	void getTokens_formLoginSessionWithoutHandle_isLeftOut() {
		SessionInfo formInfo = SessionInfo.builder().userName("alice").build();
		sessionsOfTheUser(formLogin("form-id", null, formInfo));

		Map<String, SessionInfo> tokens = tokenService.getTokens("form-id");

		// never listed under its session id instead
		assertEquals(Map.of(), tokens);
	}

	@Test
	void deleteToken_formLoginHandle_expiresOnlyThatSession() {
		SessionInformation form = formLogin("form-id", "form-handle", SessionInfo.builder().build());
		SessionInformation other = formLogin("other-id", "other-handle", SessionInfo.builder().build());
		sessionsOfTheUser(form, other);

		tokenService.deleteToken("form-handle");

		assertTrue(form.isExpired());
		assertFalse(other.isExpired());
	}

	@Test
	void deleteToken_sessionId_expiresNothing() {
		SessionInformation form = formLogin("form-id", "form-handle", SessionInfo.builder().build());
		sessionsOfTheUser(form);

		tokenService.deleteToken("form-id");

		assertFalse(form.isExpired());
	}

	@Test
	void deleteToken_cookieValue_expiresNothing() {
		SessionInformation form = formLogin("form-id", "form-handle", SessionInfo.builder().build());
		sessionsOfTheUser(form);

		tokenService.deleteToken("form-id.node0");

		assertFalse(form.isExpired());
	}

	@Test
	void deleteToken_blank_expiresNothing() {
		SessionInformation form = formLogin("form-id", "form-handle", SessionInfo.builder().build());
		sessionsOfTheUser(form);

		tokenService.deleteToken(" ");

		assertFalse(form.isExpired());
	}

	@Test
	void deleteToken_handleOfNoSessionOfTheUser_expiresNothing() {
		SessionInformation form = formLogin("form-id", "form-handle", SessionInfo.builder().build());
		sessionsOfTheUser(form);

		tokenService.deleteToken("handle-of-another-user");

		assertFalse(form.isExpired());
	}

	private void sessionsOfTheUser(SessionInformation... sessions) {
		when(authService.getAllUserSessions(USER_URI, false)).thenReturn(List.of(sessions));
	}

	private static SessionInformation formLogin(String sessionId, String handle, SessionInfo sessionInfo) {
		var springUser = org.springframework.security.core.userdetails.User
				.withUsername(USER_URI.toString()).password("N/A").build();
		var details = new ESUserSessionDetails(springUser, mock(User.class), sessionInfo);
		details.setSessionHandle(handle);
		return new SessionInformation(details, sessionId, new Date());
	}
}
