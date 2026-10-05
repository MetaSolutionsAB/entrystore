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

package org.entrystore.rest.springboot.configuration;

import org.apache.commons.lang3.StringUtils;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.net.URISyntaxException;

/**
 * Targets of the links in the password-reset and sign-up confirmation emails, as URL templates in which
 * {@value #TOKEN_PLACEHOLDER} stands for the confirmation token. An unset or blank target is {@code null}: the
 * email then links to the backend's own confirmation page.
 *
 * <p>A set target may be absolute or relative to the repository base URL; {@code ConfirmationLinkBuilder}
 * resolves it and checks that the result is an http(s) URL. It must contain the placeholder and parse as a URI,
 * or startup fails: a wrong value would otherwise send every user a link that cannot confirm anything.
 */
@ConfigurationProperties(prefix = "entrystore.auth.confirmation.url")
public record ConfirmationUrlProperties(String pwreset, String signup) {

	public static final String TOKEN_PLACEHOLDER = "__CONFIRMATION_TOKEN__";

	private static final String PREFIX = "entrystore.auth.confirmation.url.";

	public ConfirmationUrlProperties {
		pwreset = validated("pwreset", pwreset);
		signup = validated("signup", signup);
	}

	private static String validated(String key, String template) {
		if (StringUtils.isBlank(template)) {
			return null;
		}
		String trimmed = template.strip();
		if (!trimmed.contains(TOKEN_PLACEHOLDER)) {
			throw new IllegalArgumentException(PREFIX + key + " must contain " + TOKEN_PLACEHOLDER
					+ ", which is replaced by the confirmation token");
		}
		try {
			// The token is alphanumeric, so any alphanumeric stand-in checks the URL the email will carry.
			new URI(trimmed.replace(TOKEN_PLACEHOLDER, "token"));
		} catch (URISyntaxException e) {
			throw new IllegalArgumentException(PREFIX + key + " is not a valid URL: " + trimmed, e);
		}
		return trimmed;
	}
}
