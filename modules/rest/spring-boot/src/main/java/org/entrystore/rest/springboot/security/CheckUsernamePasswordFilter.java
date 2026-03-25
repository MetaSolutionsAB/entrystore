package org.entrystore.rest.springboot.security;

import jakarta.annotation.PostConstruct;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.entrystore.config.Config;
import org.entrystore.repository.config.Settings;
import org.entrystore.repository.security.Password;
import org.entrystore.rest.springboot.service.auth.LoginAttemptService;
import org.entrystore.rest.springboot.util.HttpUtil;
import org.jetbrains.annotations.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Objects;

/**
 * Class checks the request size and parameters before the authentication via username and password process starts
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CheckUsernamePasswordFilter extends OncePerRequestFilter {

	private final Config config;
	private final LoginAttemptService loginAttemptService;
	private static List<String> passwordLoginWhitelist;
	private static List<String> passwordLoginBlacklist;

	@PostConstruct
	public void init() {
		if ("whitelist".equalsIgnoreCase(config.getString(Settings.AUTH_PASSWORD))) {
			passwordLoginWhitelist = config.getStringList(Settings.AUTH_PASSWORD_WHITELIST);
		}
		passwordLoginBlacklist = config.getStringList(Settings.AUTH_PASSWORD_BLACKLIST);
	}

	@Override
	protected void doFilterInternal(@NotNull HttpServletRequest request, @NotNull HttpServletResponse response, @NotNull FilterChain filterChain)
			throws ServletException, IOException {

		String username = request.getParameter("auth_username");
		String password = request.getParameter("auth_password");

		if (username != null || password != null) {
			// means someone is trying to authenticate

			if (request.getContentLength() > 0 && HttpUtil.isLargerThan(request, 32768)) {
				//TODO throw new EntityTooLargeException("The size of the request is larger than 32KB");
				log.warn("The size of the request is larger than 32KB, request blocked");
				response.setStatus(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
				response.setContentType("application/json");
				response.getWriter().write("{\"error\":\"The size of the request is larger than 32KB\"}");
				response.getWriter().flush();
				return;
			}

			if (password == null || password.isEmpty()) {
				// TODO throw new BadRequestException("Password is missing");
				response.sendError(HttpServletResponse.SC_BAD_REQUEST, "Password is missing");
				return;
			}

			try {
				Password.check(password, Password.getSaltedHash(password));
			} catch (IllegalArgumentException ex) {
				// TODO throw new BadRequestException(ex.getMessage());
				response.sendError(HttpServletResponse.SC_BAD_REQUEST, ex.getMessage());
				return;
			}

			if (username == null || username.isEmpty()) {
				// TODO throw new BadRequestException("Username is missing");
				response.sendError(HttpServletResponse.SC_BAD_REQUEST, "Username is missing");
				return;
			}

			// Use case for whitelisting: enforced SSO with some users that should be able to log in
			// with their local credentials, see https://entrystore.org/#!KB/Authentication.md
			if ((passwordLoginBlacklist != null && passwordLoginBlacklist.stream().anyMatch(s -> s.equalsIgnoreCase(username.toLowerCase()))) ||
					(passwordLoginWhitelist != null && passwordLoginWhitelist.stream().noneMatch(s -> s.equalsIgnoreCase(username.toLowerCase())))) {
				log.warn("User {} is blacklisted", username);
				// TODO throw new UnauthorizedException("Login failed.");
				response.setContentType("text/html");
				response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
				if (request.getHeader("Accept") != null && !Objects.equals(request.getHeader("Accept"), MediaType.APPLICATION_JSON_VALUE)) {
					response.getWriter().write("Login failed.");
				} else {
					response.getWriter().write("The request requires user authentication");
				}
				return;
			}

			if (loginAttemptService.isLockedOut(username.toLowerCase())) {
				log.warn("User {} is temporarily locked out due to too many failed login attempts", username);
				response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
				response.setContentType("text/html");
				response.getWriter().write("User account is temporarily disabled. Too many failed logins.");
				return;
			}
		}

		filterChain.doFilter(request, response);
	}
}
