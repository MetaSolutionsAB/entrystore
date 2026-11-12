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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.session.SessionRegistryImpl;

import java.net.URI;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TokenServiceTest {

	private static final URI USER_URI = URI.create("https://example.org/store/_principals/resource/7");

	private final SessionRegistryImpl sessionRegistry = new SessionRegistryImpl();
	private final PrincipalManager principalManager = mock(PrincipalManager.class);
	private final AuthService authService = mock(AuthService.class);
	private final TokenService tokenService = new TokenService(principalManager, authService);

	@BeforeEach
	void listTheSessionsOfTheRegistry() {
		when(principalManager.getAuthenticatedUserURI()).thenReturn(USER_URI);
		when(authService.getAllUserSessions(USER_URI, false))
				.thenAnswer(_ -> sessionRegistry.getAllSessions(sessionDetails(null), false));
	}

	@Test
	void getTokens_keysTheSessionsByHandleAndMarksTheCurrentOne() {
		logIn("session-1", "handle-1");
		logIn("session-2", "handle-2");

		var tokens = tokenService.getTokens("session-1");

		assertEquals(Set.of("handle-1", "handle-2"), tokens.keySet());
		assertTrue(tokens.get("handle-1").current());
		assertFalse(tokens.get("handle-2").current());
		assertEquals("alice", tokens.get("handle-2").userName());
	}

	@Test
	void getTokens_withoutASessionOfTheRequest_marksNoneCurrent() {
		logIn("session-1", "handle-1");

		var tokens = tokenService.getTokens(null);

		assertFalse(tokens.get("handle-1").current());
	}

	@Test
	void getTokens_leavesOutASessionWhoseLoginHasNotCompleted() {
		logIn("session-1", "handle-1");
		logIn("session-2", null);

		assertEquals(Set.of("handle-1"), tokenService.getTokens("session-2").keySet());
	}

	@Test
	void deleteToken_withAHandle_endsThatSessionOnly() {
		logIn("session-1", "handle-1");
		logIn("session-2", "handle-2");

		tokenService.deleteToken("handle-2");

		assertTrue(sessionRegistry.getSessionInformation("session-2").isExpired());
		assertFalse(sessionRegistry.getSessionInformation("session-1").isExpired());
	}

	@ParameterizedTest(name = "\"{0}\"")
	@ValueSource(strings = {"session-1", "session-1.node0", "unknown-handle", " "})
	void deleteToken_withAnythingButAHandle_endsNothing(String token) {
		logIn("session-1", "handle-1");

		tokenService.deleteToken(token);

		assertFalse(sessionRegistry.getSessionInformation("session-1").isExpired());
	}

	private void logIn(String sessionId, String handle) {
		sessionRegistry.registerNewSession(sessionId, sessionDetails(handle));
	}

	private static ESUserSessionDetails sessionDetails(String handle) {
		var springUser = new org.springframework.security.core.userdetails.User(
				USER_URI.toString(), "", List.of(new SimpleGrantedAuthority("ROLE_USER")));
		var details = new ESUserSessionDetails(springUser, mock(User.class),
				SessionInfo.builder().userName("alice").build());
		details.setSessionHandle(handle);
		return details;
	}
}
