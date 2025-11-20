package org.entrystore.rest.standalone.springboot.security;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationFailureHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Slf4j
@Component
@RequiredArgsConstructor
public class ESAuthenticationFailureHandler extends SimpleUrlAuthenticationFailureHandler {
	@Override
	public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response, AuthenticationException exception) throws ServletException, IOException {

		if (exception instanceof DisabledException) {
			log.warn("User {} is disabled", request.getParameter("auth_username"));
			response.setStatus(HttpStatus.FORBIDDEN.value());
			response.setHeader("Content-Type", "text/html");
			response.getOutputStream().println("Login failed. The account is disabled.");
			return;
		}
		super.onAuthenticationFailure(request, response, exception);
	}
}
