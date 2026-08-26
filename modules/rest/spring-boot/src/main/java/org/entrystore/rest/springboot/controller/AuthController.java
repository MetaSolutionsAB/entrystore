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
import org.entrystore.rest.springboot.model.api.ConfirmRequestBody;
import org.entrystore.rest.springboot.model.api.PwResetRequestBody;
import org.entrystore.rest.springboot.model.api.SignupRequestBody;
import org.entrystore.rest.springboot.model.auth.ConfirmationResult;
import org.entrystore.rest.springboot.model.exception.BadRequestException;
import org.entrystore.rest.springboot.model.exception.EntityNotFoundException;
import org.entrystore.rest.springboot.service.AuthService;
import org.entrystore.rest.springboot.service.OidcAuthService;
import org.entrystore.rest.springboot.service.SamlAuthService;
import org.entrystore.rest.springboot.util.HttpUtil;
import org.entrystore.rest.springboot.util.RequestBodyValidator;
import org.entrystore.rest.springboot.util.WebResourceUrls;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.cas.ServiceProperties;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
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

	private final WebResourceUrls webResourceUrls;

	private static final int MAX_REQUEST_SIZE = 32 * 1024;
	private static final String SIGNUP_TITLE = "Sign-up";
	private static final String PASSWORD_RESET_TITLE = "Password reset";

	/** Per-flow attributes for the shared {@code confirm_form} template (new confirmation mode). */
	private record ConfirmForm(String title, String action, String passwordLabel, String intro) {
	}

	private static final ConfirmForm SIGNUP_FORM = new ConfirmForm(SIGNUP_TITLE, "/auth/signup/confirm", "Password",
			"To confirm your sign-up, re-enter the email address and the password you chose.");
	private static final ConfirmForm PWRESET_FORM = new ConfirmForm(PASSWORD_RESET_TITLE, "/auth/pwreset/confirm", "New password",
			"To reset your password, enter your email address and choose a new password.");

	/**
	 * Exposed to every view this controller renders. A bean reference cannot be used from the
	 * templates directly: Thymeleaf forbids SpEL bean access in the restricted context that
	 * attribute expressions are evaluated in.
	 */
	@ModelAttribute("stylesheetPath")
	String stylesheetPath() {
		return webResourceUrls.getStylesheetPath();
	}

	@Value("${entrystore.auth.saml.enabled:false}")
	private boolean isSamlAuthEnabled;

	@Value("${entrystore.auth.oidc.enabled:false}")
	private boolean isOidcAuthEnabled;

	private final AuthService authService;
	private final SamlAuthService samlAuthService;
	private final OidcAuthService oidcAuthService;
	private final CasCustomConfiguration casConfiguration;
	private final Optional<ServiceProperties> casServiceProperties;
	// Optional: the bean exists only when spring.security.oauth2.client registrations are configured.
	private final Optional<ClientRegistrationRepository> clientRegistrationRepository;
	private final RequestBodyValidator requestBodyValidator;

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

	/**
	 * Initiates OIDC authentication by redirecting to the selected provider's authorization
	 * endpoint. An unknown registration id would otherwise surface deep inside Spring Security's
	 * {@code OAuth2AuthorizationRequestRedirectFilter} as an HTML 500 that bypasses
	 * {@code AppExceptionHandler}, so the guard below fails early. A missing registration repository
	 * altogether is config-shaped (500), regardless of what the caller sent. An unknown id is then
	 * classified by the selection's provenance: a bogus {@code ?provider=} parameter is the caller's
	 * fault (400); a config-derived id — a {@code default-provider} typo, or an
	 * {@code entrystore.auth.oidc.provider.{id}} entry whose map key has no registration (domain
	 * routing returns that key, never the {@code domains} values) — is a server misconfiguration
	 * surfaced as a 500 that {@code AppExceptionHandler} logs at error.
	 * {@code OidcProviderRegistrationValidator} rejects all of these at startup; the guard is the
	 * runtime backstop.
	 */
	@GetMapping("/auth/oidc")
	public String startOidcLogin(@RequestParam(required = false) String username,
								 @RequestParam(required = false) String provider,
								 @RequestParam(name = "successurl", required = false) String successUrl,
								 @RequestParam(name = "failureurl", required = false) String failureUrl,
								 RedirectAttributes redirectAttributes) {

		if (!isOidcAuthEnabled) {
			throw new EntityNotFoundException("Not Found");
		}

		if (successUrl != null && oidcAuthService.isValidRedirectUrl(successUrl)) {
			redirectAttributes.addAttribute("successurl", successUrl);
		}

		if (failureUrl != null && oidcAuthService.isValidRedirectUrl(failureUrl)) {
			redirectAttributes.addAttribute("failureurl", failureUrl);
		}

		var selection = oidcAuthService.findProviderIdForRequest(username, provider);
		// No repository bean at all is config-shaped, not caller-shaped (see method Javadoc).
		var registrations = clientRegistrationRepository.orElseThrow(
				() -> new IllegalStateException("OIDC is enabled but no spring.security.oauth2.client.registration "
						+ "entries are configured — no provider can complete a login"));
		if (registrations.findByRegistrationId(selection.id()) == null) {
			if (selection.fromRequestParameter()) {
				// sanitizeForLog: the raw ?provider= value is reflected into the message, which is
				// copied into the JSON body and the handler's debug log.
				throw new BadRequestException("Unknown OIDC provider: " + HttpUtil.sanitizeForLog(selection.id()));
			}
			throw new IllegalStateException("Configured OIDC provider id '" + selection.id()
					+ "' has no matching spring.security.oauth2.client.registration entry — check "
					+ "entrystore.auth.oidc.default-provider and the entrystore.auth.oidc.provider.{id} keys");
		}
		redirectAttributes.addAttribute("providerId", selection.id());

		// Spring Security's OAuth2AuthorizationRequestRedirectFilter serves this path;
		// OidcAuthorizationRequestResolver re-validates and caches the redirect URLs there.
		return "redirect:/oauth2/authorization/{providerId}";
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
		String message = authService.pwReset(request, pwResetRequestBody, PASSWORD_RESET_TITLE);
		model.addAttribute("title", PASSWORD_RESET_TITLE);
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
		String message = authService.pwReset(request, pwResetRequestBody, PASSWORD_RESET_TITLE);
		model.addAttribute("title", PASSWORD_RESET_TITLE);
		model.addAttribute("message", message);
		return "auth";
	}

	@Operation(summary = "Legacy mode: confirms the password change. New mode: renders the confirmation form for a valid token.")
	@GetMapping(path = "/auth/pwreset")
	public String confirmPasswordReset(
			Model model,
			@RequestParam(required = false) String confirm
	) {
		if (confirm == null || confirm.isEmpty()) {
			return "pwreset_form";
		}

		if (authService.isLegacyConfirmationMode()) {
			String message = authService.confirmPasswordLegacy(confirm, PASSWORD_RESET_TITLE);
			model.addAttribute("title", PASSWORD_RESET_TITLE);
			model.addAttribute("message", message);
			return "auth";
		}

		authService.assertPasswordResetTokenValid(confirm, PASSWORD_RESET_TITLE);
		addConfirmFormAttributes(model, PWRESET_FORM, confirm, null);
		return "confirm_form";
	}

	@Operation(summary = "Confirms a password change after the user re-enters the email and chosen password.")
	@PostMapping(path = "/auth/pwreset/confirm", consumes = {MediaType.APPLICATION_JSON_VALUE})
	public String confirmPasswordResetSubmit(
			HttpServletRequest request,
			HttpServletResponse response,
			Model model,
			@RequestBody ConfirmRequestBody body) {

		return submitPasswordResetConfirmation(request, response, model, body.confirm(), body.email(), body.password());
	}

	@Operation(summary = "Confirms a password change after the user re-enters the email and chosen password. Request is an html form.")
	@PostMapping(path = "/auth/pwreset/confirm", consumes = {MediaType.APPLICATION_FORM_URLENCODED_VALUE})
	public String confirmPasswordResetSubmitViaForm(
			HttpServletRequest request,
			HttpServletResponse response,
			Model model,
			@RequestParam MultiValueMap<String, String> paramMap) {

		return submitPasswordResetConfirmation(request, response, model, paramMap.getFirst("confirm"),
				paramMap.getFirst("email"), paramMap.getFirst("password"));
	}

	@Operation(summary = "Generates new link for user sign-up confirmation and sends an email to provided email address.")
	@PostMapping(path = "/auth/signup",
			consumes = {MediaType.APPLICATION_JSON_VALUE}
	)
	public String signup(
			HttpServletRequest request,
			HttpServletResponse response,
			Model model,
			@RequestBody SignupRequestBody signupRequestBody) {

		HttpUtil.checkRequestSize(request, MAX_REQUEST_SIZE);

		response.setContentType(MediaType.TEXT_HTML_VALUE);

		// Validated here rather than with @Valid so the size limit above still decides first. @Valid runs
		// during argument resolution, which precedes the method body, so an oversized body whose fields
		// are also invalid would answer 400 instead of 413 — and an oversized sign-up body almost always
		// is invalid, since the bulk has to go in one of these fields.
		requestBodyValidator.assertValid(signupRequestBody, SIGNUP_TITLE);

		String message = authService.signup(request, signupRequestBody, SIGNUP_TITLE);
		model.addAttribute("title", SIGNUP_TITLE);
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

		// Not @Valid @ModelAttribute: the form spells the captcha field g-recaptcha-response, which is
		// not a legal record component name, so Spring cannot bind it. The body is assembled here and
		// validated against the same constraints, so both content types answer with the same message.
		SignupRequestBody signupRequestBody = SignupRequestBody.fromFormParameters(parameters);
		requestBodyValidator.assertValid(signupRequestBody, SIGNUP_TITLE);

		String message = authService.signup(request, signupRequestBody, SIGNUP_TITLE);
		model.addAttribute("title", SIGNUP_TITLE);
		model.addAttribute("message", message);
		return "auth";
	}

	@Operation(summary = "Legacy mode: confirms new user creation. New mode: renders the confirmation form for a valid token.")
	@GetMapping(path = "/auth/signup")
	public String confirmSignup(
			Model model,
			HttpServletResponse response,
			@RequestParam(required = false) String confirm
	) {
		if (confirm == null || confirm.isEmpty()) {
			return "signup_form";
		}

		if (authService.isLegacyConfirmationMode()) {
			String message = authService.confirmSignupLegacy(confirm, SIGNUP_TITLE);
			model.addAttribute("title", SIGNUP_TITLE);
			model.addAttribute("message", message);
			response.setStatus(HttpStatus.CREATED.value());
			return "auth";
		}

		authService.assertSignupTokenValid(confirm, SIGNUP_TITLE);
		addConfirmFormAttributes(model, SIGNUP_FORM, confirm, null);
		return "confirm_form";
	}

	@Operation(summary = "Confirms new user creation after the user re-enters the email and chosen password.")
	@PostMapping(path = "/auth/signup/confirm", consumes = {MediaType.APPLICATION_JSON_VALUE})
	public String confirmSignupSubmit(
			HttpServletRequest request,
			HttpServletResponse response,
			Model model,
			@RequestBody ConfirmRequestBody body) {

		return submitSignupConfirmation(request, response, model, body.confirm(), body.email(), body.password());
	}

	@Operation(summary = "Confirms new user creation after the user re-enters the email and chosen password. Request is an html form.")
	@PostMapping(path = "/auth/signup/confirm", consumes = {MediaType.APPLICATION_FORM_URLENCODED_VALUE})
	public String confirmSignupSubmitViaForm(
			HttpServletRequest request,
			HttpServletResponse response,
			Model model,
			@RequestParam MultiValueMap<String, String> paramMap) {

		return submitSignupConfirmation(request, response, model, paramMap.getFirst("confirm"),
				paramMap.getFirst("email"), paramMap.getFirst("password"));
	}

	private String submitSignupConfirmation(HttpServletRequest request, HttpServletResponse response, Model model,
											String confirm, String email, String password) {
		HttpUtil.checkRequestSize(request, MAX_REQUEST_SIZE);
		ConfirmationResult result = authService.confirmSignup(request, confirm, email, password, SIGNUP_TITLE);
		return renderConfirmation(model, response, result, SIGNUP_FORM, confirm, HttpStatus.CREATED.value());
	}

	private String submitPasswordResetConfirmation(HttpServletRequest request, HttpServletResponse response, Model model,
												   String confirm, String email, String password) {
		HttpUtil.checkRequestSize(request, MAX_REQUEST_SIZE);
		ConfirmationResult result = authService.confirmPassword(request, confirm, email, password, PASSWORD_RESET_TITLE);
		return renderConfirmation(model, response, result, PWRESET_FORM, confirm, HttpStatus.OK.value());
	}

	private String renderConfirmation(Model model, HttpServletResponse response, ConfirmationResult result,
									  ConfirmForm form, String token, int successStatus) {
		if (result.success()) {
			model.addAttribute("title", form.title());
			model.addAttribute("message", result.message());
			response.setStatus(successStatus);
			return "auth";
		}
		String error = "The information you entered is incorrect. " + result.remainingAttempts() + " attempt(s) remaining.";
		addConfirmFormAttributes(model, form, token, error);
		response.setStatus(HttpStatus.UNAUTHORIZED.value());
		return "confirm_form";
	}

	private void addConfirmFormAttributes(Model model, ConfirmForm form, String token, String error) {
		model.addAttribute("title", form.title());
		model.addAttribute("action", form.action());
		model.addAttribute("passwordLabel", form.passwordLabel());
		model.addAttribute("intro", form.intro());
		model.addAttribute("token", token);
		model.addAttribute("error", error);
	}
}
