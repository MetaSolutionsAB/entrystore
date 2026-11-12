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
import lombok.extern.slf4j.Slf4j;
import org.entrystore.PrincipalManager;
import org.entrystore.User;
import org.entrystore.rest.springboot.model.auth.SessionInfo;
import org.entrystore.rest.springboot.security.ESUserSessionDetails;
import org.entrystore.rest.springboot.security.SsoSessionInfos;
import org.springframework.security.core.session.SessionInformation;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class TokenService {

	private final PrincipalManager principalManager;
	private final AuthService authService;
	private final SsoSessionInfos ssoSessionInfos;

	/**
	 * Returns the session info of the authenticated user's sessions by session id, whichever way they logged in. A
	 * session without recorded info is left out.
	 */
	public Map<String, SessionInfo> getTokens() {
		Map<String, SessionInfo> tokenInfo = new HashMap<>();
		for (SessionInformation session : sessionsOfAuthenticatedUser()) {
			SessionInfo sessionInfo = session.getPrincipal() instanceof ESUserSessionDetails formLogin
					? formLogin.getSessionInfo()
					: ssoSessionInfos.get(session.getSessionId());
			if (sessionInfo != null) {
				tokenInfo.put(session.getSessionId(), sessionInfo);
			}
		}
		return tokenInfo;
	}

	/**
	 * Ends the authenticated user's session with the given id. Any other token is ignored without an error: 5.x did the
	 * same for an unknown token, but removed another user's.
	 */
	public void deleteToken(String token) {
		for (SessionInformation session : sessionsOfAuthenticatedUser()) {
			if (session.getSessionId().equals(token)) {
				session.expireNow();
			}
		}
	}

	private List<SessionInformation> sessionsOfAuthenticatedUser() {
		User user = principalManager.getUser(principalManager.getAuthenticatedUserURI());
		if (user == null) {
			log.warn("No user found for URI: {}", principalManager.getAuthenticatedUserURI());
			return List.of();
		}
		return authService.getUserSessions(user);
	}
}
