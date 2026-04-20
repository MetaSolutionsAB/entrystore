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
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apereo.cas.client.util.CommonUtils;
import org.entrystore.rest.springboot.configuration.CasCustomConfiguration;
import org.entrystore.rest.springboot.model.api.PwResetRequestBody;
import org.entrystore.rest.springboot.model.api.SignupRequestBody;
import org.entrystore.rest.springboot.model.exception.EntityNotFoundException;
import org.entrystore.rest.springboot.service.AuthService;
import org.entrystore.rest.springboot.service.SamlAuthService;
import org.entrystore.rest.springboot.util.HttpUtil;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.cas.ServiceProperties;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Controller
@RequiredArgsConstructor
public class AuthController {

	private final int MAX_REQUEST_SIZE = 32 * 1024;
	private final String signupTitle = "Sign-up";
	private final String passwordResetTitle = "Password reset";

	@Value("${entrystore.auth.saml.enabled:false}")
	private boolean isSamlAuthEnabled;

	private final AuthService authService;
	private final SamlAuthService samlAuthService;
	private final CasCustomConfiguration casConfiguration;
	private final Optional<ServiceProperties> casServiceProperties;

	@GetMapping("/auth/cas")
	public void startCasLogin(HttpServletResponse response) throws IOException {
		if (!casConfiguration.enabled()) {
			throw new EntityNotFoundException("Not Found");
		}

		String serviceUrl = casServiceProperties.orElseThrow(() -> new IllegalStateException(
				"CAS is enabled but ServiceProperties bean is missing — check CasConfig.")).getService();
		String loginUrl = casConfiguration.server().resolvedLoginUrl();

		// CommonUtils.constructRedirectUrl handles query-string parsing and URL encoding,
		// so it works correctly even when loginUrl already contains a query string.
		response.sendRedirect(CommonUtils.constructRedirectUrl(loginUrl, "service", serviceUrl, false, false));
	}

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
			consumes = {MediaType.APPLICATION_JSON_VALUE}
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
			consumes = {MediaType.APPLICATION_FORM_URLENCODED_VALUE}
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
			consumes = {MediaType.APPLICATION_JSON_VALUE}
	)
	public String signup(
			HttpServletRequest request,
			HttpServletResponse response,
			Model model,
			@RequestBody HashMap<String, String> parameters) {

		HttpUtil.checkRequestSize(request, MAX_REQUEST_SIZE);

		response.setContentType(MediaType.TEXT_HTML_VALUE);

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
			consumes = {MediaType.APPLICATION_FORM_URLENCODED_VALUE}
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
