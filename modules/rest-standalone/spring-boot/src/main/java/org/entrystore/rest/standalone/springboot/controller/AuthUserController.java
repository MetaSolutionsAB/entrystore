package org.entrystore.rest.standalone.springboot.controller;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.entrystore.rest.standalone.springboot.model.api.GetAuthUserResponse;
import org.entrystore.rest.standalone.springboot.service.UserService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequiredArgsConstructor
public class AuthUserController {

	private final UserService userService;

	@Operation(summary = "Provides basic information about the currently logged-in user.")
	@GetMapping(path = "/auth/user", produces = MediaType.APPLICATION_JSON_VALUE)
	public GetAuthUserResponse userInfo(HttpServletRequest request,
										@CookieValue(value = "JSESSIONID") String authToken,
										@RequestHeader(defaultValue = HttpHeaders.ACCEPT_LANGUAGE, name = HttpHeaders.ACCEPT_LANGUAGE) String acceptLanguage) {
		return userService.getUserInfo(authToken, acceptLanguage);
	}
}
