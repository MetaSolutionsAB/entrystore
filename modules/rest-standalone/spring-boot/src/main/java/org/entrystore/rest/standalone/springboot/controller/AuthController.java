package org.entrystore.rest.standalone.springboot.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class AuthController {

	@GetMapping(path = "/auth/login")
	public String getLogin() {
		return "login";
	}
}
