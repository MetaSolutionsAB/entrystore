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
import org.entrystore.rest.springboot.security.RecordedSession;
import org.entrystore.rest.springboot.security.SsoSessionInfos;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.session.SessionInformation;
import org.springframework.security.saml2.provider.service.authentication.DefaultSaml2AuthenticatedPrincipal;

import java.net.URI;
import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TokenServiceTest {

	private static final URI USER_URI = URI.create("https://example.org/store/_principals/resource/7");

	private final PrincipalManager principalManager = mock(PrincipalManager.class);
	private final AuthService authService = mock(AuthService.class);
	private final SsoSessionInfos ssoSessionInfos = mock(SsoSessionInfos.class);
	private final TokenService tokenService = new TokenService(principalManager, authService, ssoSessionInfos);
	private final User user = mock(User.class);

	@BeforeEach
	void authenticate() {
		when(principalManager.getAuthenticatedUserURI()).thenReturn(USER_URI);
		when(principalManager.getUser(USER_URI)).thenReturn(user);
	}

	@Test
	void getTokens_formLoginSession_reportsTheInfoItsPrincipalCarriesByItsHandle() {
		SessionInfo formInfo = SessionInfo.builder().userName("alice").lastUsedUserAgent("Browser/1").build();
		when(authService.getUserSessions(user)).thenReturn(List.of(formLogin("form-id", "form-handle", formInfo)));

		Map<String, SessionInfo> tokens = tokenService.getTokens(null);

		assertEquals(Map.of("form-handle", formInfo), tokens);
	}

	@Test
	void getTokens_ssoSession_reportsTheInfoRecordedForItsSessionIdByItsHandle() {
		SessionInfo samlInfo = SessionInfo.builder().userName("alice").lastUsedUserAgent("Browser/2").build();
		when(authService.getUserSessions(user)).thenReturn(List.of(samlLogin("saml-id")));
		when(ssoSessionInfos.get("saml-id")).thenReturn(new RecordedSession("saml-handle", samlInfo));

		Map<String, SessionInfo> tokens = tokenService.getTokens(null);

		assertEquals(Map.of("saml-handle", samlInfo), tokens);
	}

	@Test
	void getTokens_marksOnlyTheRequestingSessionAsCurrent() {
		SessionInfo info = SessionInfo.builder().userName("alice").build();
		when(authService.getUserSessions(user)).thenReturn(List.of(
				formLogin("form-id", "form-handle", info), samlLogin("saml-id")));
		when(ssoSessionInfos.get("saml-id")).thenReturn(new RecordedSession("saml-handle", info));

		Map<String, SessionInfo> tokens = tokenService.getTokens("saml-id");

		assertTrue(tokens.get("saml-handle").current());
		assertFalse(tokens.get("form-handle").current());
	}

	@Test
	void getTokens_requestWithoutSession_marksNoSessionAsCurrent() {
		SessionInfo info = SessionInfo.builder().userName("alice").build();
		when(authService.getUserSessions(user)).thenReturn(List.of(formLogin("form-id", "form-handle", info)));

		Map<String, SessionInfo> tokens = tokenService.getTokens(null);

		assertFalse(tokens.get("form-handle").current());
	}

	@Test
	void getTokens_sessionWithoutRecordedInfo_isLeftOut() {
		SessionInfo samlInfo = SessionInfo.builder().userName("alice").build();
		when(authService.getUserSessions(user)).thenReturn(List.of(samlLogin("saml-id"), samlLogin("unrecorded-id")));
		when(ssoSessionInfos.get("saml-id")).thenReturn(new RecordedSession("saml-handle", samlInfo));

		Map<String, SessionInfo> tokens = tokenService.getTokens(null);

		assertEquals(Map.of("saml-handle", samlInfo), tokens);
	}

	@Test
	void getTokens_formLoginSessionWithoutHandle_isLeftOut() {
		SessionInfo formInfo = SessionInfo.builder().userName("alice").build();
		when(authService.getUserSessions(user)).thenReturn(List.of(formLogin("form-id", null, formInfo)));

		Map<String, SessionInfo> tokens = tokenService.getTokens("form-id");

		// never listed under its session id instead
		assertEquals(Map.of(), tokens);
	}

	@Test
	void getTokens_authenticatedUserNotFound_reportsNoSessions() {
		when(principalManager.getUser(USER_URI)).thenReturn(null);

		Map<String, SessionInfo> tokens = tokenService.getTokens(null);

		assertEquals(Map.of(), tokens);
		verifyNoInteractions(authService);
	}

	@Test
	void deleteToken_formLoginHandle_expiresOnlyThatSession() {
		SessionInformation form = formLogin("form-id", "form-handle", SessionInfo.builder().build());
		SessionInformation saml = samlLogin("saml-id");
		when(authService.getUserSessions(user)).thenReturn(List.of(form, saml));
		when(ssoSessionInfos.get("saml-id"))
				.thenReturn(new RecordedSession("saml-handle", SessionInfo.builder().build()));

		tokenService.deleteToken("form-handle");

		assertTrue(form.isExpired());
		assertFalse(saml.isExpired());
	}

	@Test
	void deleteToken_ssoHandle_expiresOnlyThatSession() {
		SessionInformation form = formLogin("form-id", "form-handle", SessionInfo.builder().build());
		SessionInformation saml = samlLogin("saml-id");
		when(authService.getUserSessions(user)).thenReturn(List.of(form, saml));
		when(ssoSessionInfos.get("saml-id"))
				.thenReturn(new RecordedSession("saml-handle", SessionInfo.builder().build()));

		tokenService.deleteToken("saml-handle");

		assertTrue(saml.isExpired());
		assertFalse(form.isExpired());
	}

	@Test
	void deleteToken_sessionId_expiresNothing() {
		SessionInformation form = formLogin("form-id", "form-handle", SessionInfo.builder().build());
		SessionInformation saml = samlLogin("saml-id");
		when(authService.getUserSessions(user)).thenReturn(List.of(form, saml));
		when(ssoSessionInfos.get("saml-id"))
				.thenReturn(new RecordedSession("saml-handle", SessionInfo.builder().build()));

		tokenService.deleteToken("form-id");
		tokenService.deleteToken("saml-id");

		assertFalse(form.isExpired());
		assertFalse(saml.isExpired());
	}

	@Test
	void deleteToken_handleOfNoSessionOfTheUser_expiresNothing() {
		SessionInformation form = formLogin("form-id", "form-handle", SessionInfo.builder().build());
		when(authService.getUserSessions(user)).thenReturn(List.of(form));

		tokenService.deleteToken("handle-of-another-user");

		assertFalse(form.isExpired());
	}

	private static SessionInformation formLogin(String sessionId, String handle, SessionInfo sessionInfo) {
		var springUser = org.springframework.security.core.userdetails.User
				.withUsername(USER_URI.toString()).password("N/A").build();
		var details = new ESUserSessionDetails(springUser, mock(User.class), sessionInfo);
		details.setSessionHandle(handle);
		return new SessionInformation(details, sessionId, new Date());
	}

	private static SessionInformation samlLogin(String sessionId) {
		return new SessionInformation(new DefaultSaml2AuthenticatedPrincipal("alice", Map.of()), sessionId, new Date());
	}
}
