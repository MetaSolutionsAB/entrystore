package org.entrystore.rest.springboot.security;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.entrystore.rest.springboot.service.auth.LoginAttemptService;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationFailureHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Slf4j
@Component
@RequiredArgsConstructor
public class FormLoginAuthenticationFailureHandler extends SimpleUrlAuthenticationFailureHandler {

	private final LoginAttemptService loginAttemptService;

	@Override
	public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response, AuthenticationException exception) throws ServletException, IOException {

		String username = request.getParameter("auth_username");

		if (exception instanceof DisabledException) {
			log.warn("User {} is disabled", username);
			response.setStatus(HttpStatus.FORBIDDEN.value());
			response.setContentType("text/html");
			response.getWriter().write("Login failed. The account is disabled.");
			return;
		}

		if (username != null) {
			loginAttemptService.recordFailure(username.toLowerCase());
		}

		super.onAuthenticationFailure(request, response, exception);
	}
}
