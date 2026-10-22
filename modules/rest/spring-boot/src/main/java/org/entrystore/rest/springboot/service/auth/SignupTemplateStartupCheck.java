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

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.entrystore.rest.springboot.configuration.AuthFeatureProperties;
import org.entrystore.rest.springboot.util.EmailTemplate;
import org.entrystore.rest.springboot.util.MailTemplateRenderer;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Loads the sign-up email template once the application is ready, so that a template that cannot be loaded,
 * malformed or unreachable, is reported at deploy time: sign-up is answered alike whether or not its mail is sent,
 * so otherwise only the ERROR lines of later sign-ups would tell. A template that loads is cached for the sign-ups
 * to come; a failed load is not, so a sign-up loads a template whose host was briefly down at startup.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SignupTemplateStartupCheck {

	private final AuthFeatureProperties features;
	private final MailTemplateRenderer templates;

	@EventListener(ApplicationReadyEvent.class)
	public void checkSignupTemplate() {
		if (Boolean.TRUE.equals(features.signup()) && templates.render(EmailTemplate.SIGNUP, Map.of()) == null) {
			log.error("The sign-up email template could not be loaded, so sign-ups get no mail until it loads; "
					+ "each sign-up tries again");
		}
	}
}
