package org.entrystore.rest.standalone.springboot.controller;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.entrystore.rest.standalone.springboot.model.api.PwResetRequestBody;
import org.entrystore.rest.standalone.springboot.model.exception.EntityTooLargeException;
import org.entrystore.rest.standalone.springboot.service.AuthService;
import org.entrystore.rest.standalone.springboot.util.HttpUtil;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

@Slf4j
@Controller
@RequiredArgsConstructor
public class AuthController {

	private final int MAX_REQUEST_SIZE = 32 * 1024;
	private final AuthService authService;

	@Operation(
			summary = "Returns a basic HTML-form for login",
			description = "It is recommended to use any of the other login-resources. Performs a cookie-based login and " +
					"should only be used for testing purposes, does not really belong to the API.")
	@GetMapping(path = "/auth/login")
	public String getLogin() {
		// Uses Thymeleaf templating engine - returns login.html template from /resources/templates
		return "login";
	}

	@Operation(summary = "Generates new link for password change confirmation and sends an email to the User.")
	@PostMapping(path = "/auth/pwreset",
			consumes = {MediaType.APPLICATION_JSON_VALUE},
			produces = {MediaType.TEXT_HTML_VALUE}
	)
	public String resetPassword(
			HttpServletRequest request,
			Model model,
			@RequestBody PwResetRequestBody pwResetRequestBody) {
		checkRequestSize(request);
		String message = authService.pwReset(request, pwResetRequestBody);
		model.addAttribute("message", message);
		return "pwreset";
	}

	@Operation(summary = "Generates new link for password change confirmation and sends an email to the User. Request is an html form.")
	@PostMapping(path = "/auth/pwreset",
			consumes = {MediaType.APPLICATION_FORM_URLENCODED_VALUE},
			produces = {MediaType.TEXT_HTML_VALUE}
	)
	public String resetPasswordViaForm(
			HttpServletRequest request,
			Model model,
			@RequestParam MultiValueMap<String, String> paramMap) {
		checkRequestSize(request);

		PwResetRequestBody pwResetRequestBody = new PwResetRequestBody(
				paramMap.getFirst("email"),
				paramMap.getFirst("password"),
				paramMap.getFirst("urlsuccess"),
				paramMap.getFirst("urlfailure"),
				paramMap.getFirst("g-recaptcha-response"));
		String message = authService.pwReset(request, pwResetRequestBody);
		model.addAttribute("message", message);
		return "pwreset";
	}

	@Operation(summary = "Checks if the password-reset-token is valid and confirms user's password change")
	@GetMapping(path = "/auth/pwreset")
	public String confirmPasswordReset(
			Model model,
			@RequestParam(required = false) String confirm
	) {
		if (confirm == null || confirm.isEmpty()) {
			return "pwreset_form";
		}

		String message = authService.confirmPassword(confirm);
		model.addAttribute("message", message);
		return "pwreset";
	}

	private void checkRequestSize(HttpServletRequest request) {
		if (HttpUtil.isLargerThan(request, MAX_REQUEST_SIZE)) {
			throw new EntityTooLargeException("The size of the representation is larger than 32KB or unknown, request blocked.");
		}
	}
}
