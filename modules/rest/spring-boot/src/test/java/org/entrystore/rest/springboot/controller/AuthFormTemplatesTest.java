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

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Renders the sign-up and password-reset forms the way {@link AuthController} feeds them, covering the
 * reCAPTCHA-disabled branch that the integration tests, which run with reCAPTCHA on, never reach.
 */
class AuthFormTemplatesTest {

	private static final String SITE_KEY = "configured-site-key";
	private static final String GOOGLE_TEST_SITE_KEY = "6LeIxAcTAAAAAJcZVRqyHh71UMIEGNQ_MXjiZKhI";

	private static SpringTemplateEngine engine;

	@BeforeAll
	static void createEngine() {
		ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
		resolver.setPrefix("templates/");
		resolver.setSuffix(".html");
		resolver.setTemplateMode(TemplateMode.HTML);
		engine = new SpringTemplateEngine();
		engine.setTemplateResolver(resolver);
	}

	private static String render(String template, String recaptchaSiteKey) {
		Context context = new Context();
		context.setVariable("stylesheetPath", "/css/entrystore.css");
		context.setVariable("recaptchaSiteKey", recaptchaSiteKey);
		return engine.process(template, context);
	}

	@Test
	void signupForm_withRecaptchaEnabled_rendersTheWidgetWithTheConfiguredSiteKey() {
		String html = render("signup_form", SITE_KEY);

		assertTrue(html.contains("https://www.google.com/recaptcha/api.js"), html);
		assertTrue(html.contains("data-sitekey=\"" + SITE_KEY + "\""), html);
		assertFalse(html.contains(GOOGLE_TEST_SITE_KEY), html);
	}

	@Test
	void signupForm_withRecaptchaDisabled_rendersNoCaptchaMarkup() {
		String html = render("signup_form", null);

		assertFalse(html.contains("recaptcha"), html);
		assertTrue(html.contains("<input type=\"submit\" value=\"Sign-up\" />"), html);
	}

	@Test
	void signupForm_isTitledSignUp() {
		String html = render("signup_form", null);

		assertTrue(html.contains("<title>Sign-up</title>"), html);
		assertTrue(html.contains("<h3>Sign-up</h3>"), html);
	}

	@Test
	void pwresetForm_withRecaptchaEnabled_rendersTheWidgetWithTheConfiguredSiteKey() {
		String html = render("pwreset_form", SITE_KEY);

		assertTrue(html.contains("https://www.google.com/recaptcha/api.js"), html);
		assertTrue(html.contains("data-sitekey=\"" + SITE_KEY + "\""), html);
		assertFalse(html.contains(GOOGLE_TEST_SITE_KEY), html);
	}

	@Test
	void pwresetForm_withRecaptchaDisabled_rendersNoCaptchaMarkup() {
		String html = render("pwreset_form", null);

		assertFalse(html.contains("recaptcha"), html);
		assertTrue(html.contains("<input type=\"submit\" value=\"Reset password\" />"), html);
	}
}
