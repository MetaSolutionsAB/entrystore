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

import lombok.RequiredArgsConstructor;
import org.entrystore.PrincipalManager;
import org.entrystore.rest.springboot.model.auth.SessionInfo;
import org.entrystore.rest.springboot.security.ESUserSessionDetails;
import org.jetbrains.annotations.Nullable;
import org.springframework.security.core.session.SessionInformation;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

/**
 * The authenticated user's registered password-login sessions, which clients identify by their session handle
 * ({@link ESUserSessionDetails#getSessionHandle()}), never by the session id. SAML, CAS and OIDC sessions are not
 * listed.
 */
@Service
@RequiredArgsConstructor
public class TokenService {

	private final PrincipalManager principalManager;
	private final AuthService authService;

	/**
	 * @param currentSessionId id of the session making the request, or null if it has none
	 * @return the user's password-login sessions keyed by handle; a session whose login has not completed yet is left
	 * out
	 */
	public Map<String, SessionInfo> getTokens(@Nullable String currentSessionId) {
		Map<String, SessionInfo> tokenInfo = new HashMap<>();
		for (SessionInformation session : userSessions()) {
			if (session.getPrincipal() instanceof ESUserSessionDetails esUser && esUser.getEsUser() != null) {
				String handle = esUser.getSessionHandle();
				SessionInfo info = esUser.getSessionInfo();
				if (handle != null && info != null) {
					tokenInfo.put(handle, info.toBuilder()
							.current(session.getSessionId().equals(currentSessionId))
							.build());
				}
			}
		}
		return tokenInfo;
	}

	/**
	 * Ends the user's own session with the given handle, which may be the current one. Anything else, such as another
	 * user's handle, ends nothing.
	 */
	public void deleteToken(String handle) {
		for (SessionInformation session : userSessions()) {
			if (session.getPrincipal() instanceof ESUserSessionDetails esUser
					&& handle.equals(esUser.getSessionHandle())) {
				session.expireNow();
			}
		}
	}

	private Iterable<SessionInformation> userSessions() {
		return authService.getAllUserSessions(principalManager.getAuthenticatedUserURI(), false);
	}
}
