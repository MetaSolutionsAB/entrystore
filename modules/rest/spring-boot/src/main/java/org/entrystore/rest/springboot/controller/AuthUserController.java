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
import org.entrystore.rest.springboot.security.AuthTokenCookies;
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
	private final AuthTokenCookies authTokenCookies;

	@Operation(summary = "Provides basic information about the currently logged-in user.")
	@GetMapping(path = "/auth/user", produces = MediaType.APPLICATION_JSON_VALUE)
	public GetAuthUserResponse userInfo(
			@RequestHeader(name = HttpHeaders.ACCEPT_LANGUAGE, defaultValue = HttpHeaders.ACCEPT_LANGUAGE) String acceptLanguage,
			HttpServletRequest request
	) {
		// Read the existing session without creating one: a guest must not get a session (and thus no
		// auth_token cookie / Cache-Control). The expiry is only reported for authenticated, non-guest users.
		HttpSession session = request.getSession(false);
		return userService.getUserInfo(acceptLanguage, session != null ? authTokenCookies.expiry(session) : null);
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
