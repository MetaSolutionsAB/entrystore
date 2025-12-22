package org.entrystore.rest.standalone.springboot.controller;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.entrystore.rest.standalone.springboot.model.api.PwResetRequestBody;
import org.entrystore.rest.standalone.springboot.model.api.SignupRequestBody;
import org.entrystore.rest.standalone.springboot.model.exception.EntityNotFoundException;
import org.entrystore.rest.standalone.springboot.service.AuthService;
import org.entrystore.rest.standalone.springboot.service.SamlAuthService;
import org.entrystore.rest.standalone.springboot.util.HttpUtil;
import org.entrystore.rest.standalone.springboot.util.JsonUtil;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@Controller
@RequiredArgsConstructor
public class AuthController {

	private final int MAX_REQUEST_SIZE = 32 * 1024;
	private final String signupTitle = "Sign-up";
	private final String passwordResetTitle = "Password reset";

	@Value("${app.security.saml.enabled:false}")
	private boolean isSamlAuthEnabled;

	private final AuthService authService;
	private final SamlAuthService samlAuthService;

	// Endpoint initiates SAML authentication by redirecting to the IdP for authentication
	@GetMapping("/auth/saml")
	public String startSamlLogin(@RequestParam(required = false) String username,
								 @RequestParam(required = false) String idp,
								 @RequestParam(name = "successurl", required = false) String successUrl,
								 @RequestParam(name = "failureurl", required = false) String failureUrl,
								 RedirectAttributes redirectAttributes) {

		if (!isSamlAuthEnabled) {
			throw new EntityNotFoundException("Not Found");
		}

		if (successUrl != null && samlAuthService.isValidRedirectUrl(successUrl)) {
			redirectAttributes.addAttribute("successurl", successUrl);
		}

		if (failureUrl != null && samlAuthService.isValidRedirectUrl(failureUrl)) {
			redirectAttributes.addAttribute("failureurl", failureUrl);
		}

		String idpId = samlAuthService.findIdpIdForRequest(username, idp);
		redirectAttributes.addAttribute("idpId", idpId);

		return "redirect:/saml2/authenticate/{idpId}";
	}

	@Operation(
			summary = "Returns a basic HTML-form for basic login",
			description = "It is recommended to use any of the other login-resources. Performs a cookie-based login and " +
					"should only be used for testing purposes, does not really belong to the API.")
	@GetMapping(path = "/auth/login")
	public String getLoginPage() {
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
		HttpUtil.checkRequestSize(request, MAX_REQUEST_SIZE);
		String message = authService.pwReset(request, pwResetRequestBody, passwordResetTitle);
		model.addAttribute("title", passwordResetTitle);
		model.addAttribute("message", message);
		return "auth";
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

		HttpUtil.checkRequestSize(request, MAX_REQUEST_SIZE);

		PwResetRequestBody pwResetRequestBody = new PwResetRequestBody(
				paramMap.getFirst("email"),
				paramMap.getFirst("password"),
				paramMap.getFirst("urlsuccess"),
				paramMap.getFirst("urlfailure"),
				paramMap.getFirst("g-recaptcha-response"));
		String message = authService.pwReset(request, pwResetRequestBody, passwordResetTitle);
		model.addAttribute("title", passwordResetTitle);
		model.addAttribute("message", message);
		return "auth";
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

		String message = authService.confirmPassword(confirm, passwordResetTitle);
		model.addAttribute("title", passwordResetTitle);
		model.addAttribute("message", message);
		return "auth";
	}

	@Operation(summary = "Generates new link for user sign-up confirmation and sends an email to provided email address.")
	@PostMapping(path = "/auth/signup",
			consumes = {MediaType.APPLICATION_JSON_VALUE},
			produces = {MediaType.TEXT_HTML_VALUE}
	)
	public String signup(
			HttpServletRequest request,
			Model model,
			@RequestBody String requestBody) {

		HashMap<String, String> parameters = JsonUtil.jsonToMap(requestBody);

		HttpUtil.checkRequestSize(request, MAX_REQUEST_SIZE);
		SignupRequestBody signupRequestBody = new SignupRequestBody(
				parameters.get("email"),
				parameters.get("password"),
				parameters.get("firstname"),
				parameters.get("lastname"),
				parameters.get("urlsuccess"),
				parameters.get("urlfailure"),
				parameters.get("grecaptcharesponse"));

		parameters.remove("email");
		parameters.remove("password");
		parameters.remove("firstname");
		parameters.remove("lastname");
		parameters.remove("urlsuccess");
		parameters.remove("urlfailure");
		parameters.remove("grecaptcharesponse");
		String message = authService.signup(request, signupRequestBody, parameters, signupTitle);
		model.addAttribute("title", signupTitle);
		model.addAttribute("message", message);
		return "auth";
	}

	@Operation(summary = "Generates new link for user sign-up confirmation and sends an email to provided email address. Request is an html form.")
	@PostMapping(path = "/auth/signup",
			consumes = {MediaType.APPLICATION_FORM_URLENCODED_VALUE},
			produces = {MediaType.TEXT_HTML_VALUE}
	)
	public String signupViaForm(
			HttpServletRequest request,
			Model model,
			@RequestParam Map<String, String> parameters) {

		HttpUtil.checkRequestSize(request, MAX_REQUEST_SIZE);

		SignupRequestBody signupRequestBody = new SignupRequestBody(
				parameters.get("email"),
				parameters.get("password"),
				parameters.get("firstname"),
				parameters.get("lastname"),
				parameters.get("urlsuccess"),
				parameters.get("urlfailure"),
				parameters.get("g-recaptcha-response"));

		parameters.remove("email");
		parameters.remove("password");
		parameters.remove("firstname");
		parameters.remove("lastname");
		parameters.remove("urlsuccess");
		parameters.remove("urlfailure");
		parameters.remove("g-recaptcha-response");

		String message = authService.signup(request, signupRequestBody, parameters, signupTitle);
		model.addAttribute("title", signupTitle);
		model.addAttribute("message", message);
		return "auth";
	}

	@Operation(summary = "Checks if the signup-token is valid and confirms new user creation")
	@GetMapping(path = "/auth/signup")
	public String confirmSignup(
			Model model,
			@RequestParam(required = false) String confirm,
			HttpServletResponse response
	) {
		if (confirm == null || confirm.isEmpty()) {
			return "signup_form";
		}

		String message = authService.confirmSignup(confirm, signupTitle);
		model.addAttribute("title", signupTitle);
		model.addAttribute("message", message);
		response.setStatus(HttpStatus.CREATED.value());
		return "auth";
	}
}
