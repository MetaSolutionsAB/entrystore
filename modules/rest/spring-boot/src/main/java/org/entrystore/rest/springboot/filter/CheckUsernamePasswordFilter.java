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

package org.entrystore.rest.springboot.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.entrystore.repository.config.Settings;
import org.entrystore.repository.security.Password;
import org.entrystore.rest.springboot.configuration.IndexedListSettings;
import org.entrystore.rest.springboot.configuration.PasswordLoginMode;
import org.entrystore.rest.springboot.model.api.ErrorResponse;
import org.entrystore.rest.springboot.service.auth.LoginAttemptService;
import org.entrystore.rest.springboot.util.ErrorResponseWriter;
import org.entrystore.rest.springboot.util.HttpUtil;
import org.jetbrains.annotations.NotNull;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Class checks the request size and parameters before the authentication via username and password process starts
 */
@Slf4j
@Component
public class CheckUsernamePasswordFilter extends OncePerRequestFilter {

	private static final RequestMatcher LOGIN_REQUEST =
			PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, "/auth/cookie");

	private static final int MAX_LOGIN_BODY = 32768;

	private final LoginAttemptService loginAttemptService;
	private final ErrorResponseWriter errorResponseWriter;
	private final boolean whitelistMode;
	private final List<String> passwordLoginWhitelist;
	private final List<String> passwordLoginBlacklist;

	public CheckUsernamePasswordFilter(LoginAttemptService loginAttemptService,
									   ErrorResponseWriter errorResponseWriter,
									   PasswordLoginMode passwordLoginMode,
									   Environment environment) {
		this.loginAttemptService = loginAttemptService;
		this.errorResponseWriter = errorResponseWriter;
		this.whitelistMode = passwordLoginMode == PasswordLoginMode.WHITELIST;
		this.passwordLoginWhitelist = whitelistMode
				? IndexedListSettings.read(environment, Settings.AUTH_PASSWORD_WHITELIST)
				: List.of();
		this.passwordLoginBlacklist = IndexedListSettings.read(environment, Settings.AUTH_PASSWORD_BLACKLIST);
		if (whitelistMode && passwordLoginWhitelist.isEmpty()) {
			// Deliberately not a startup failure: an empty whitelist fails closed — every password
			// login is denied — which is also the only way this layer can express "no local password
			// logins at all", so aborting would turn that legitimate configuration into an outage.
			// The ERROR is what points a misconfigured deployment at the misspelt or missing
			// whitelist key.
			log.error("{}=whitelist with no {} configured: every password login will be rejected",
					Settings.AUTH_PASSWORD, Settings.AUTH_PASSWORD_WHITELIST);
		}
	}

	@Override
	protected void doFilterInternal(@NotNull HttpServletRequest request, @NotNull HttpServletResponse response, @NotNull FilterChain filterChain)
			throws ServletException, IOException {

		if (HttpUtil.isMultipart(request)) {
			// Reading parameters would make Jetty parse the upload before any access check. The login form is
			// form-urlencoded, as in 5.x, so a multipart login is refused rather than let past the checks below.
			// Backstop: MultipartRequestFilter already answers 415 to multipart on every non-upload route.
			if (LOGIN_REQUEST.matches(request)) {
				errorResponseWriter.writeErrorResponseAsJson(response, ErrorResponse.builder()
						.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE.value())
						.path(request.getRequestURI())
						.error("Login expects an application/x-www-form-urlencoded body")
						.build());
				return;
			}
			filterChain.doFilter(request, response);
			return;
		}

		if (LOGIN_REQUEST.matches(request) && !isFormUrlEncoded(request)) {
			// 5.x parsed the login body as a form whatever its content type; clients such as FME rely on that
			byte[] body = request.getInputStream().readNBytes(MAX_LOGIN_BODY + 1);
			if (body.length > MAX_LOGIN_BODY) {
				writePayloadTooLarge(request, response);
				return;
			}
			request = new FormBodyRequest(request, new String(body, StandardCharsets.UTF_8));
		}

		String username = request.getParameter("auth_username");
		String password = request.getParameter("auth_password");

		if (username != null || password != null) {
			// means someone is trying to authenticate

			if (request.getContentLength() > 0 && HttpUtil.isLargerThan(request, MAX_LOGIN_BODY)) {
				writePayloadTooLarge(request, response);
				return;
			}

			if (password == null || password.isEmpty()) {
				errorResponseWriter.writeErrorResponseAsJson(response, ErrorResponse.builder()
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
				errorResponseWriter.writeErrorResponseAsJson(response, ErrorResponse.builder()
						.status(HttpStatus.BAD_REQUEST.value())
						.path(request.getRequestURI())
						.error("Invalid credentials format")
						.build());
				return;
			}

			if (username == null || username.isEmpty()) {
				errorResponseWriter.writeErrorResponseAsJson(response, ErrorResponse.builder()
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
				errorResponseWriter.writeErrorResponseAsJson(response, ErrorResponse.builder()
						.status(HttpStatus.TOO_MANY_REQUESTS.value())
						.path(request.getRequestURI())
						.error("Too many login attempts. Please try again later.")
						.build());
				return;
			}

			// Use case for whitelisting: enforced SSO with some users that should be able to log in
			// with their local credentials, see https://entrystore.org/#!KB/Authentication.md
			boolean blacklisted = passwordLoginBlacklist.stream().anyMatch(s -> s.equalsIgnoreCase(username));
			if (blacklisted ||
					(whitelistMode && passwordLoginWhitelist.stream().noneMatch(s -> s.equalsIgnoreCase(username)))) {
				if (blacklisted) {
					log.warn("User {} is blacklisted", HttpUtil.sanitizeForLog(username));
				} else {
					log.warn("User {} is not on the password login whitelist", HttpUtil.sanitizeForLog(username));
				}
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
				errorResponseWriter.writeUnauthorizedAsJson(response, request.getRequestURI());
				return;
			}
		}

		filterChain.doFilter(request, response);
	}

	private void writePayloadTooLarge(HttpServletRequest request, HttpServletResponse response) throws IOException {
		log.warn("The size of the request is larger than 32KB, request blocked");
		errorResponseWriter.writeErrorResponseAsJson(response, ErrorResponse.builder()
				.status(HttpStatus.PAYLOAD_TOO_LARGE.value())
				.path(request.getRequestURI())
				.error("The size of the request is larger than 32KB")
				.build());
	}

	private static boolean isFormUrlEncoded(HttpServletRequest request) {
		String contentType = request.getContentType();
		return contentType != null
				&& contentType.split(";", 2)[0].strip().equalsIgnoreCase(MediaType.APPLICATION_FORM_URLENCODED_VALUE);
	}

	/**
	 * Serves the parameters of a body that the servlet container does not parse as a form, because of its content
	 * type, after those of the query string. A pair with malformed percent-encoding is skipped.
	 */
	private static final class FormBodyRequest extends HttpServletRequestWrapper {

		private final Map<String, String[]> parameters;

		FormBodyRequest(HttpServletRequest request, String body) {
			super(request);
			Map<String, List<String>> values = new LinkedHashMap<>();
			request.getParameterMap().forEach((name, v) -> values.put(name, new ArrayList<>(List.of(v))));
			for (String pair : body.split("&")) {
				if (pair.isEmpty()) {
					continue;
				}
				int eq = pair.indexOf('=');
				try {
					String name = URLDecoder.decode(eq < 0 ? pair : pair.substring(0, eq), StandardCharsets.UTF_8);
					String value = eq < 0 ? "" : URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
					values.computeIfAbsent(name, k -> new ArrayList<>()).add(value);
				} catch (IllegalArgumentException e) {
					// skip the malformed pair
				}
			}
			Map<String, String[]> parameters = new LinkedHashMap<>();
			values.forEach((name, v) -> parameters.put(name, v.toArray(String[]::new)));
			this.parameters = Collections.unmodifiableMap(parameters);
		}

		@Override
		public String getParameter(String name) {
			String[] values = parameters.get(name);
			return values == null ? null : values[0];
		}

		@Override
		public String[] getParameterValues(String name) {
			return parameters.get(name);
		}

		@Override
		public Map<String, String[]> getParameterMap() {
			return parameters;
		}

		@Override
		public Enumeration<String> getParameterNames() {
			return Collections.enumeration(parameters.keySet());
		}
	}
}
