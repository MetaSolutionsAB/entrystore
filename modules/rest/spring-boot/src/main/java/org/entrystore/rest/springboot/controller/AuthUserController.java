package org.entrystore.rest.springboot.controller;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.entrystore.rest.springboot.model.api.DeleteAuthTokenRequestBody;
import org.entrystore.rest.springboot.model.api.GetAuthUserResponse;
import org.entrystore.rest.springboot.model.auth.SessionInfo;
import org.entrystore.rest.springboot.service.TokenService;
import org.entrystore.rest.springboot.service.UserService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@Slf4j
@RestController
@RequiredArgsConstructor
public class AuthUserController {

	private final UserService userService;
	private final TokenService tokenService;

	@Operation(summary = "Provides basic information about the currently logged-in user.")
	@GetMapping(path = "/auth/user", produces = MediaType.APPLICATION_JSON_VALUE)
	public GetAuthUserResponse userInfo(
			@RequestHeader(name = HttpHeaders.ACCEPT_LANGUAGE, defaultValue = HttpHeaders.ACCEPT_LANGUAGE) String acceptLanguage,
			HttpServletRequest request
	) {
		// Read the existing session without creating one: a guest must not get a session (and thus no
		// auth_token cookie / Cache-Control). The interval is only used for authenticated, non-guest users.
		HttpSession session = request.getSession(false);
		int maxInactiveInterval = session != null ? session.getMaxInactiveInterval() : 0;
		return userService.getUserInfo(acceptLanguage, maxInactiveInterval);
	}

	@Operation(summary = "Provides list of active tokens of a currently logged-in user.")
	@GetMapping(path = "/auth/tokens", produces = MediaType.APPLICATION_JSON_VALUE)
	public Map<String, SessionInfo> tokensInfo() {
		return tokenService.getTokens();
	}

	@Operation(summary = "Deletes the session of provided cookie token.")
	@DeleteMapping(path = "/auth/tokens", produces = MediaType.APPLICATION_JSON_VALUE, consumes = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<Void> deleteToken(
			@Valid @RequestBody DeleteAuthTokenRequestBody body
	) {

		tokenService.deleteToken(body.token());

		return ResponseEntity
				.noContent()
				.build();
	}
}
