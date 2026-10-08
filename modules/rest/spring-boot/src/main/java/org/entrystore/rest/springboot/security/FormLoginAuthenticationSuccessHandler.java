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
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.entrystore.rest.springboot.filter.CacheControlFilter;
import org.entrystore.rest.springboot.filter.ReloadUserPropertiesFilter;
import org.entrystore.rest.springboot.model.api.ErrorResponse;
import org.entrystore.rest.springboot.service.auth.LoginAttemptService;
import org.entrystore.rest.springboot.model.auth.SessionInfo;
import org.entrystore.rest.springboot.util.ErrorResponseWriter;
import org.entrystore.rest.springboot.util.HttpUtil;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Completes a form login: session lifetime, the session info that /auth/tokens reports, and stale cookies. A login
 * whose session info cannot be recorded fails with 500 and leaves no session, since later requests of the session
 * rely on that info.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FormLoginAuthenticationSuccessHandler extends SimpleUrlAuthenticationSuccessHandler {

	private final LoginAttemptService loginAttemptService;
	private final AuthTokenCookies authTokenCookies;
	private final ErrorResponseWriter errorResponseWriter;

	@Override
	public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response, Authentication auth) throws IOException {

		String username = request.getParameter("auth_username");
		if (username != null) {
			loginAttemptService.recordSuccess(username.toLowerCase());
		}

		authTokenCookies.applySessionLifetime(request, parseRequestedMaxAge(request.getParameter("auth_maxage")));

		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

		if (authentication != null && authentication.getPrincipal() instanceof ESUserSessionDetails esUserDetails) {
			try {
				Instant now = Instant.now();
				SessionInfo.SessionInfoBuilder sessionInfo = SessionInfo.builder()
						.userName(username != null ? username.toLowerCase() : null)
						.loginTime(LocalDateTime.ofInstant(now, ZoneId.systemDefault()))
						.loginExpiration(LocalDateTime.ofInstant(authTokenCookies.expiry(request.getSession()), ZoneId.systemDefault()))
						.lastAccessTime(LocalDateTime.ofInstant(now, ZoneId.systemDefault()))
						.lastUsedIpAddress(request.getRemoteAddr())
						.lastUsedUserAgent(request.getHeader("User-Agent"))
						.loginTokenMaxAge(request.getSession().getMaxInactiveInterval());

				esUserDetails.setSessionInfo(sessionInfo.build());
				// the login has just loaded the user
				request.getSession().setAttribute(ReloadUserPropertiesFilter.LAST_RELOAD_ATTRIBUTE,
						new AtomicReference<>(now));

				UsernamePasswordAuthenticationToken newAuth = new UsernamePasswordAuthenticationToken(esUserDetails, esUserDetails.getPassword(), esUserDetails.getAuthorities());
				SecurityContextHolder.getContext().setAuthentication(newAuth);
			} catch (RuntimeException e) {
				log.error("Failed to record the session info of a login, ending the new session", e);
				HttpUtil.clearAuthenticatedSession(request);
				authTokenCookies.expireAfterFailedLogin(request, response);
				errorResponseWriter.writeErrorResponseAsJson(response, ErrorResponse.builder()
						.status(HttpStatus.INTERNAL_SERVER_ERROR.value())
						.path(request.getRequestURI())
						.error("Login failed.")
						.build());
				return;
			}
		}

		authTokenCookies.expireStale(request, response);
		// The login filter ends the chain before CacheControlFilter runs, and this response sets the session cookie.
		CacheControlFilter.markSessionCookieResponse(response);
		response.setStatus(HttpStatus.OK.value());
		response.setContentType("text/html");
		response.getWriter().write("Login successful.");
	}

	/**
	 * @return the client's {@code auth_maxage} in seconds, or null if absent or not a number
	 */
	private static Integer parseRequestedMaxAge(String maxAgeParam) {
		if (StringUtils.isEmpty(maxAgeParam)) {
			return null;
		}
		try {
			return Integer.parseInt(maxAgeParam);
		} catch (NumberFormatException e) {
			log.info("Unable to parse as Integer the 'auth_maxage' parameter value of: '{}'", maxAgeParam);
			return null;
		}
	}
}
