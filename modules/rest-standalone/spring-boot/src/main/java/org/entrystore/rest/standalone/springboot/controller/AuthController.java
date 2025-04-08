package org.entrystore.rest.standalone.springboot.controller;

import io.swagger.v3.oas.annotations.Operation;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class AuthController {

	@Operation(
		summary = "Returns a basic HTML-form for login",
		description = "It is recommended to use any of the other login-resources. Performs a cookie-based login and " +
			"should only be used for testing purposes, does not really belong to the API.")
	@GetMapping(path = "/auth/login")
	public String getLogin() {
		// Uses Thymeleaf templating engine - returns login.html template from /resources/templates
		return "login";
	}
}
