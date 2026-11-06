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

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.entrystore.repository.config.Settings;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The reCAPTCHA configuration shared by the sign-up and password-reset forms, which render the widget
 * with the site key, and by the server-side check, which verifies the token with the secret.
 *
 * <p>Startup fails when reCAPTCHA is on and the secret is blank, since no token could be verified. A
 * blank site key only logs a WARN, as in 5.x: clients such as EntryScape render the widget with their
 * own site key and post the token, so only EntryStore's own HTML forms lose the widget. When reCAPTCHA
 * is off or the site key is blank, {@link #getSiteKey()} is null, which the forms read as "render no
 * widget".
 *
 * <p>Read through {@code @Value}: {@code entrystore.auth.recaptcha} is both a scalar and the prefix of
 * the key properties, which {@code @ConfigurationProperties} cannot bind.
 */
@Slf4j
@Getter
@Component
public class RecaptchaSettings {

	private final boolean enabled;
	private final String siteKey;
	private final String secret;

	public RecaptchaSettings(@Value("${entrystore.auth.recaptcha:false}") boolean enabled,
							 @Value("${entrystore.auth.recaptcha.public-key:#{null}}") String siteKey,
							 @Value("${entrystore.auth.recaptcha.private-key:#{null}}") String secret) {
		if (enabled && StringUtils.isBlank(secret)) {
			throw new IllegalStateException("EntryStore startup aborted: " + Settings.AUTH_RECAPTCHA
					+ " is enabled but missing: [" + Settings.AUTH_RECAPTCHA_PRIVATE_KEY + "]");
		}
		if (enabled && StringUtils.isBlank(siteKey)) {
			log.warn("{} is enabled but {} is not set: EntryStore's sign-up and password-reset forms render "
					+ "without the reCAPTCHA widget, so only clients that render it themselves can pass the check",
					Settings.AUTH_RECAPTCHA, Settings.AUTH_RECAPTCHA_PUBLIC_KEY);
		}
		this.enabled = enabled;
		this.siteKey = enabled && StringUtils.isNotBlank(siteKey) ? siteKey : null;
		this.secret = secret;
	}
}
