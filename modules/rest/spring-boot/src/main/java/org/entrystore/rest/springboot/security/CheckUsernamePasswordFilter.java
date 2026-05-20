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
import org.entrystore.rest.springboot.model.api.ErrorResponse;
import org.entrystore.rest.springboot.service.auth.LoginAttemptService;
import org.entrystore.rest.springboot.util.HttpUtil;
import org.jetbrains.annotations.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

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
				log.warn("The size of the request is larger than 32KB, request blocked");
				HttpUtil.writeErrorResponseAsJson(response, ErrorResponse.builder()
						.status(HttpStatus.PAYLOAD_TOO_LARGE.value())
						.path(request.getRequestURI())
						.error("The size of the request is larger than 32KB")
						.build());
				return;
			}

			if (password == null || password.isEmpty()) {
				HttpUtil.writeErrorResponseAsJson(response, ErrorResponse.builder()
						.status(HttpStatus.BAD_REQUEST.value())
						.path(request.getRequestURI())
						.error("Password is missing")
						.build());
				return;
			}

			try {
				Password.check(password, Password.getSaltedHash(password));
			} catch (IllegalArgumentException ex) {
				log.warn("Password validation failed: {}", ex.getMessage(), ex);
				HttpUtil.writeErrorResponseAsJson(response, ErrorResponse.builder()
						.status(HttpStatus.BAD_REQUEST.value())
						.path(request.getRequestURI())
						.error("Invalid credentials format")
						.build());
				return;
			}

			if (username == null || username.isEmpty()) {
				HttpUtil.writeErrorResponseAsJson(response, ErrorResponse.builder()
						.status(HttpStatus.BAD_REQUEST.value())
						.path(request.getRequestURI())
						.error("Username is missing")
						.build());
				return;
			}

			// Lockout is checked before any short-circuit branch so locked-out attempts always
			// produce a 429 regardless of whether the username is blacklisted, on the whitelist,
			// or unknown — preventing an attacker from using the absence of 429 to enumerate which
			// usernames take the normal auth path.
			if (loginAttemptService.isLockedOut(username)) {
				log.warn("User {} is temporarily locked out due to too many failed login attempts", HttpUtil.sanitizeForLog(username));
				HttpUtil.writeErrorResponseAsJson(response, ErrorResponse.builder()
						.status(HttpStatus.TOO_MANY_REQUESTS.value())
						.path(request.getRequestURI())
						.error("Too many login attempts. Please try again later.")
						.build());
				return;
			}

			// Use case for whitelisting: enforced SSO with some users that should be able to log in
			// with their local credentials, see https://entrystore.org/#!KB/Authentication.md
			if ((passwordLoginBlacklist != null && passwordLoginBlacklist.stream().anyMatch(s -> s.equalsIgnoreCase(username))) ||
					(passwordLoginWhitelist != null && passwordLoginWhitelist.stream().noneMatch(s -> s.equalsIgnoreCase(username)))) {
				log.warn("User {} is blacklisted", HttpUtil.sanitizeForLog(username));
				// Record the failure so blacklisted attempts trip the same lockout threshold as
				// any other username — otherwise the absence of 429 on retries would identify
				// which usernames are blacklisted. The call is guarded so a Caffeine fault cannot
				// surface as a 500 that itself becomes the enumeration oracle.
				try {
					loginAttemptService.recordFailure(username);
				} catch (RuntimeException e) {
					loginAttemptService.getRecordFailureErrorCounter().increment();
					log.error("Failed to record blacklist login attempt for [{}] — lockout tracking is degraded",
							HttpUtil.sanitizeForLog(username), e);
				}
				// Equalize wall-clock cost with the bad-credentials path. A non-blacklisted submission
				// falls through filterChain.doFilter, which eventually runs DaoAuthenticationProvider's
				// bcrypt verification against the stored hash; the blacklist branch never reaches that
				// code. Without a synthetic equivalent here an attacker classifies blacklisted vs
				// non-blacklisted usernames by timing alone — re-opening the very enumeration channel
				// the unified 401 was meant to close. The synthetic hash is discarded.
				Password.getSaltedHash(password);
				// Body must remain identical to the generic 401 used for all other authentication
				// failures so account state cannot be enumerated.
				HttpUtil.writeUnauthorizedAsJson(response, request);
				return;
			}
		}

		filterChain.doFilter(request, response);
	}
}
