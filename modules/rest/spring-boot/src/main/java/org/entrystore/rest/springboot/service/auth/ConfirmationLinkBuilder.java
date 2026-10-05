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

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.Strings;
import org.entrystore.impl.RepositoryManagerImpl;
import org.entrystore.rest.springboot.configuration.ConfirmationUrlProperties;
import org.springframework.stereotype.Component;

import java.net.URI;

import static org.entrystore.rest.springboot.configuration.ConfirmationUrlProperties.TOKEN_PLACEHOLDER;

/**
 * Builds the link in a password-reset or sign-up confirmation email: the configured
 * {@code entrystore.auth.confirmation.url.*} template with the token substituted, else the backend's own
 * confirmation page. The link is the same in legacy and credential-confirmation mode.
 *
 * <p>A relative template is resolved against the repository base URL, taken as ending with {@code /}: a leading
 * {@code /} resolves against the host, a template without one is appended to the base path. A template that does
 * not resolve to an http(s) URL with a host fails startup.
 */
@Slf4j
@Component
public class ConfirmationLinkBuilder {

	private static final String PREFIX = "entrystore.auth.confirmation.url.";

	private final String passwordResetTemplate;
	private final String signupTemplate;

	public ConfirmationLinkBuilder(ConfirmationUrlProperties urls, RepositoryManagerImpl repositoryManager) {
		String repositoryUrl = repositoryManager.getRepositoryURL().toExternalForm();
		this.passwordResetTemplate = urls.pwreset() != null
				? resolve("pwreset", urls.pwreset(), repositoryUrl)
				: repositoryUrl + "auth/pwreset?confirm=" + TOKEN_PLACEHOLDER;
		this.signupTemplate = urls.signup() != null
				? resolve("signup", urls.signup(), repositoryUrl)
				: repositoryUrl + "auth/signup?confirm=" + TOKEN_PLACEHOLDER;
		log.info("Password-reset confirmation emails link to {}", passwordResetTemplate);
		log.info("Sign-up confirmation emails link to {}", signupTemplate);
	}

	public String passwordResetLink(String token) {
		return passwordResetTemplate.replace(TOKEN_PLACEHOLDER, token);
	}

	public String signupLink(String token) {
		return signupTemplate.replace(TOKEN_PLACEHOLDER, token);
	}

	private static String resolve(String key, String template, String repositoryUrl) {
		// The placeholder is URI-safe, so it survives resolution unchanged.
		URI resolved = URI.create(Strings.CS.appendIfMissing(repositoryUrl, "/")).resolve(URI.create(template));
		if (!Strings.CI.equalsAny(resolved.getScheme(), "http", "https") || resolved.getHost() == null) {
			throw new IllegalArgumentException(PREFIX + key + " must resolve to an http(s) URL with a host: "
					+ template);
		}
		return resolved.toString();
	}
}
