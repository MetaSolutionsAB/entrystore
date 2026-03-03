package org.entrystore.rest.standalone.springboot.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.entrystore.rest.standalone.springboot.service.auth.LoginAttemptService;
import org.entrystore.rest.standalone.springboot.model.auth.SessionInfo;
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

@Slf4j
@Component
@RequiredArgsConstructor
public class ESAuthenticationSuccessHandler extends SimpleUrlAuthenticationSuccessHandler {

	private final LoginAttemptService loginAttemptService;

	@Override
	public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response, Authentication auth) throws IOException {

		String username = request.getParameter("auth_username");
		if (username != null) {
			loginAttemptService.recordSuccess(username.toLowerCase());
		}

		int effectiveMaxAge = request.getServletContext().getSessionCookieConfig().getMaxAge();
		// If auth_maxage parameter is set use it as the session max-age, instead of the default cookie config from properties
		String maxAgeParam = request.getParameter("auth_maxage");
		if (StringUtils.isNotEmpty(maxAgeParam)) {
			try {
				int customMaxAge = Integer.parseInt(maxAgeParam);
				if (customMaxAge > 0 && customMaxAge < effectiveMaxAge) {
					// set the user input max-age only if it's lower than the default configured cookie max-age
					effectiveMaxAge = customMaxAge;
				}
			} catch (NumberFormatException e) {
				log.info("Unable to parse as Integer the 'auth_maxage' parameter value of: '{}'", maxAgeParam);
			}
		}

		request.getSession().setMaxInactiveInterval(effectiveMaxAge);

		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

		if (authentication != null && authentication.getPrincipal() instanceof ESUserSessionDetails esUserDetails) {
			try {
				Instant now = Instant.now();
				SessionInfo.SessionInfoBuilder sessionInfo = SessionInfo.builder()
						.userName(esUserDetails.getSessionInfo().userName())
						.loginTime(LocalDateTime.ofInstant(now, ZoneId.systemDefault()))
						.loginExpiration(LocalDateTime.ofInstant(now.plusSeconds(request.getSession().getMaxInactiveInterval()), ZoneId.systemDefault()))
						.lastAccessTime(LocalDateTime.ofInstant(now, ZoneId.systemDefault()))
						.lastUsedIpAddress(request.getRemoteAddr())
						.lastUsedUserAgent(request.getHeader("User-Agent"))
						.loginTokenMaxAge(request.getSession().getMaxInactiveInterval());

				esUserDetails.setSessionInfo(sessionInfo.build());

				UsernamePasswordAuthenticationToken newAuth = new UsernamePasswordAuthenticationToken(esUserDetails, esUserDetails.getPassword(), esUserDetails.getAuthorities());
				SecurityContextHolder.getContext().setAuthentication(newAuth);
			} catch (Exception e) {
				log.error("Failed to build session metadata on login success, login will proceed without session info", e);
			}
		}

		response.setStatus(HttpStatus.OK.value());
		response.setContentType("text/html");
		response.getWriter().write("Login successful.");
	}
}
