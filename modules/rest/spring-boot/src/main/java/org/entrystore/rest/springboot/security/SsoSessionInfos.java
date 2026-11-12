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

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.entrystore.rest.springboot.model.auth.SessionInfo;
import org.jetbrains.annotations.Nullable;
import org.springframework.context.event.EventListener;
import org.springframework.security.core.session.SessionDestroyedEvent;
import org.springframework.security.core.session.SessionIdChangedEvent;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The session info that /auth/tokens reports for SAML, CAS and OIDC sessions, by session id. Their principals are
 * Spring's own and cannot carry it, as {@link ESUserSessionDetails} does for form logins. An entry lives as long as
 * its session, so this is session state, not a cache: it is not listed under /management/caches, where clearing it
 * would blank the info of live sessions.
 */
@Component
@RequiredArgsConstructor
public class SsoSessionInfos {

	private final AuthTokenCookies authTokenCookies;
	private final Map<String, SessionInfo> bySessionId = new ConcurrentHashMap<>();

	/**
	 * Records the info of the request's session with the fields a form login records. Call when an SSO login has
	 * succeeded, after its session lifetime is set.
	 */
	public void recordLogin(HttpServletRequest request, String username) {
		HttpSession session = request.getSession();
		LocalDateTime now = LocalDateTime.now();
		bySessionId.put(session.getId(), asOfRequest(request, session, now)
				.userName(username.toLowerCase())
				.loginTime(now)
				.build());
	}

	/**
	 * Updates the info of the request's session as of this request. A session without recorded info gets none, so a
	 * request can neither add an entry for a session that has no SSO login nor bring back the entry of a session
	 * that has just ended.
	 */
	public void refresh(HttpServletRequest request) {
		HttpSession session = request.getSession(false);
		if (session == null) {
			return;
		}
		try {
			SessionInfo.SessionInfoBuilder updated = asOfRequest(request, session, LocalDateTime.now());
			bySessionId.computeIfPresent(session.getId(), (_, recorded) -> updated
					.userName(recorded.userName())
					.loginTime(recorded.loginTime())
					.build());
		} catch (IllegalStateException endedByAConcurrentRequest) {
			// the request is served without the session, and the session's end removes the entry
		}
	}

	public @Nullable SessionInfo get(String sessionId) {
		return bySessionId.get(sessionId);
	}

	@EventListener
	public void onSessionDestroyed(SessionDestroyedEvent event) {
		bySessionId.remove(event.getId());
	}

	/**
	 * Drops the entry under the old id, which no SessionDestroyedEvent will name. A login changes the id of an
	 * existing session before its success handler runs; an SSO login then records the info under the new id.
	 */
	@EventListener
	public void onSessionIdChanged(SessionIdChangedEvent event) {
		bySessionId.remove(event.getOldSessionId());
	}

	/** The fields that every request on the session updates. */
	private SessionInfo.SessionInfoBuilder asOfRequest(HttpServletRequest request, HttpSession session,
													   LocalDateTime now) {
		return SessionInfo.builder()
				.loginExpiration(LocalDateTime.ofInstant(authTokenCookies.expiry(session), ZoneId.systemDefault()))
				.lastAccessTime(now)
				.lastUsedIpAddress(request.getRemoteAddr())
				.lastUsedUserAgent(request.getHeader("User-Agent"))
				.loginTokenMaxAge(session.getMaxInactiveInterval());
	}
}
