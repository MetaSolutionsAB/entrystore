package org.entrystore.rest.standalone.springboot.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.entrystore.PrincipalManager;
import org.entrystore.rest.standalone.springboot.model.auth.SessionInfo;
import org.entrystore.rest.standalone.springboot.security.ESUserSessionDetails;
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

	public Map<String, SessionInfo> getTokens() {
		Map<String, SessionInfo> tokenInfo = new HashMap<>();

		List<SessionInformation> sessionsList = authService.getAllUserSessions(principalManager.getAuthenticatedUserURI(), false);

		for (SessionInformation session : sessionsList) {
			if (session.getPrincipal() instanceof ESUserSessionDetails esUser && esUser.getEsUser() != null) {
				tokenInfo.put(session.getSessionId(), esUser.getSessionInfo());
			}
		}

		return tokenInfo;
	}

	public void deleteToken(String token) {

		List<SessionInformation> sessionsList = authService.getAllUserSessions(principalManager.getAuthenticatedUserURI(), false);

		for (SessionInformation session : sessionsList) {
			if (session.getSessionId().equals(token)) {
				session.expireNow();
			}
		}
	}
}
