package org.entrystore.rest.standalone.springboot.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Slf4j
@Component
@RequiredArgsConstructor
public class ESAuthenticationSuccessHandler extends SimpleUrlAuthenticationSuccessHandler {

	@Override
	public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response, Authentication auth) throws IOException {

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
		response.setStatus(HttpStatus.OK.value());
		response.setHeader("Content-Type", "text/html");
		response.getOutputStream().println("Login successful.");
	}
}
