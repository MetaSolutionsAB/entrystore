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
import org.entrystore.rest.springboot.security.RecordedSession;
import org.entrystore.rest.springboot.security.SsoSessionInfos;
import org.jetbrains.annotations.Nullable;
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
	 * Returns the session info of the authenticated user's sessions by session handle, whichever way they logged in.
	 * A session without a recorded handle or info is left out.
	 *
	 * @param currentSessionId the id of the session that makes the request, whose info is marked as current, or null
	 *                         if the request has no session (HTTP Basic)
	 */
	public Map<String, SessionInfo> getTokens(@Nullable String currentSessionId) {
		Map<String, SessionInfo> tokenInfo = new HashMap<>();
		for (SessionInformation session : sessionsOfAuthenticatedUser()) {
			RecordedSession recorded = recorded(session);
			if (recorded != null) {
				tokenInfo.put(recorded.handle(), recorded.info().toBuilder()
						.current(session.getSessionId().equals(currentSessionId))
						.build());
			}
		}
		return tokenInfo;
	}

	/**
	 * Ends the authenticated user's session with the given handle. Anything else is ignored without an error: an
	 * unknown handle, as 5.x did for an unknown token, another user's handle and a session id.
	 */
	public void deleteToken(String handle) {
		for (SessionInformation session : sessionsOfAuthenticatedUser()) {
			RecordedSession recorded = recorded(session);
			if (recorded != null && recorded.handle().equals(handle)) {
				session.expireNow();
			}
		}
	}

	/** @return what the session's login recorded, or null if its handle or info is missing */
	private @Nullable RecordedSession recorded(SessionInformation session) {
		if (!(session.getPrincipal() instanceof ESUserSessionDetails formLogin)) {
			return ssoSessionInfos.get(session.getSessionId());
		}
		String handle = formLogin.getSessionHandle();
		SessionInfo info = formLogin.getSessionInfo();
		return handle != null && info != null ? new RecordedSession(handle, info) : null;
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
