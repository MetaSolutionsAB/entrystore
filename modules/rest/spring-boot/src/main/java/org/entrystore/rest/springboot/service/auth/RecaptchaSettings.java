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
import org.apache.commons.lang3.StringUtils;
import org.entrystore.repository.config.Settings;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * The reCAPTCHA configuration shared by the sign-up and password-reset forms, which render the widget
 * with the site key, and by the server-side check, which verifies the token with the secret. Keeping
 * one gate for both stops a form from minting tokens that the check can never accept.
 *
 * <p>Startup fails when reCAPTCHA is on and either key is blank: without a site key the form cannot
 * produce a token, and without a secret no token can be verified. When reCAPTCHA is off,
 * {@link #getSiteKey()} is null, which the forms read as "render no widget".
 *
 * <p>Read through {@code @Value}: {@code entrystore.auth.recaptcha} is both a scalar and the prefix of
 * the key properties, which {@code @ConfigurationProperties} cannot bind.
 */
@Getter
@Component
public class RecaptchaSettings {

	private final boolean enabled;
	private final String siteKey;
	private final String secret;

	public RecaptchaSettings(@Value("${entrystore.auth.recaptcha:false}") boolean enabled,
							 @Value("${entrystore.auth.recaptcha.public-key:#{null}}") String siteKey,
							 @Value("${entrystore.auth.recaptcha.private-key:#{null}}") String secret) {
		this.enabled = enabled;
		this.siteKey = enabled ? siteKey : null;
		this.secret = secret;
		if (enabled) {
			List<String> missing = new ArrayList<>();
			if (StringUtils.isBlank(siteKey)) {
				missing.add(Settings.AUTH_RECAPTCHA_PUBLIC_KEY);
			}
			if (StringUtils.isBlank(secret)) {
				missing.add(Settings.AUTH_RECAPTCHA_PRIVATE_KEY);
			}
			if (!missing.isEmpty()) {
				throw new IllegalStateException("EntryStore startup aborted: " + Settings.AUTH_RECAPTCHA
						+ " is enabled but missing: " + missing);
			}
		}
	}
}
