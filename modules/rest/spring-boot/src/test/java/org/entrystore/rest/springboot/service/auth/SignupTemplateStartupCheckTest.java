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

package org.entrystore.rest.springboot.service.auth;

import org.apache.logging.log4j.Level;
import org.entrystore.rest.springboot.configuration.AuthFeatureProperties;
import org.entrystore.rest.springboot.util.CapturingAppender;
import org.entrystore.rest.springboot.util.EmailTemplate;
import org.entrystore.rest.springboot.util.MailTemplateRenderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class SignupTemplateStartupCheckTest {

	private static final String MALFORMED = "<!--BEGIN new-account-->Confirm";

	@Test
	void checkSignupTemplate_malformedTemplateWithSignupOn_isReportedAtStartup(@TempDir Path tmp) throws IOException {
		var check = new SignupTemplateStartupCheck(new AuthFeatureProperties(true, false), renderer(tmp, MALFORMED));

		try (CapturingAppender appender = CapturingAppender.attachTo(SignupTemplateStartupCheck.class)) {
			check.checkSignupTemplate();

			assertEquals(1, appender.countAt(Level.ERROR));
		}
	}

	@Test
	void checkSignupTemplate_withSignupOff_doesNotLoadTheTemplate(@TempDir Path tmp) throws IOException {
		var check = new SignupTemplateStartupCheck(new AuthFeatureProperties(false, false), renderer(tmp, MALFORMED));

		try (CapturingAppender appender = CapturingAppender.attachTo(MailTemplateRenderer.class)) {
			check.checkSignupTemplate();

			assertEquals(0, appender.countAt(Level.ERROR), "a template sign-up does not use must not be loaded");
		}
	}

	@Test
	void checkSignupTemplate_usableTemplate_isCachedForTheSignupsToCome(@TempDir Path tmp) throws IOException {
		MailTemplateRenderer renderer = renderer(tmp, "<!--BEGIN new-account-->Confirm<!--END new-account-->");
		var check = new SignupTemplateStartupCheck(new AuthFeatureProperties(true, false), renderer);

		try (CapturingAppender appender = CapturingAppender.attachTo(SignupTemplateStartupCheck.class)) {
			check.checkSignupTemplate();

			assertEquals(0, appender.countAt(Level.ERROR));
		}
		Files.delete(tmp.resolve(EmailTemplate.SIGNUP.getClasspathResource()));
		assertNotNull(renderer.render(EmailTemplate.SIGNUP, Map.of()), "the startup check must have cached the template");
	}

	private static MailTemplateRenderer renderer(Path tmp, String signupTemplate) throws IOException {
		Path file = tmp.resolve(EmailTemplate.SIGNUP.getClasspathResource());
		Files.writeString(file, signupTemplate);
		MockEnvironment environment = new MockEnvironment();
		environment.setProperty(EmailTemplate.SIGNUP.getTemplatePathKey(), file.toString());
		return new MailTemplateRenderer(environment);
	}
}
