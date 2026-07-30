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

package org.entrystore.rest.springboot.util;

import lombok.Getter;
import org.entrystore.repository.config.Settings;

/**
 * The three transactional email templates. Each constant binds the subject key, the template-path key
 * and the bundled fallback resource for one email, which is what lets {@code EmailSender} render all
 * three through a single path.
 *
 * <p>{@code description} is the phrase used in the "Unable to load email template for …" error.
 */
@Getter
public enum EmailTemplate {

	SIGNUP(Settings.SIGNUP_SUBJECT,
			"User sign-up request",
			Settings.SIGNUP_CONFIRMATION_MESSAGE_TEMPLATE_PATH,
			"email_signup.html",
			"sign-up confirmation"),

	PASSWORD_RESET(Settings.AUTH_PASSWORD_RESET_SUBJECT,
			"Password reset request",
			Settings.AUTH_PASSWORD_RESET_CONFIRMATION_MESSAGE_TEMPLATE_PATH,
			"email_pwreset.html",
			"password reset confirmation"),

	PASSWORD_CHANGE(Settings.AUTH_PASSWORD_CHANGE_SUBJECT,
			"Your password has been changed",
			Settings.AUTH_PASSWORD_CHANGE_CONFIRMATION_MESSAGE_TEMPLATE_PATH,
			"email_pwchange.html",
			"password change confirmation");

	private final String subjectKey;
	private final String defaultSubject;
	private final String templatePathKey;
	private final String classpathResource;
	private final String description;

	EmailTemplate(String subjectKey, String defaultSubject, String templatePathKey,
				  String classpathResource, String description) {
		this.subjectKey = subjectKey;
		this.defaultSubject = defaultSubject;
		this.templatePathKey = templatePathKey;
		this.classpathResource = classpathResource;
		this.description = description;
	}
}
