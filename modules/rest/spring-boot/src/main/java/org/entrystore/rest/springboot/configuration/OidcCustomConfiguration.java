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

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.List;
import java.util.Map;

/**
 * EntryStore-specific OIDC login settings, mirroring the structure of {@link SamlCustomConfiguration}.
 * The client registration itself (client id/secret, scopes, issuer) is configured via Spring Boot's
 * native {@code spring.security.oauth2.client.registration.{id}.*} / {@code ...client.provider.{id}.*}
 * properties — the same split SAML uses with {@code spring.security.saml2.relyingparty.*}.
 */
@ConfigurationProperties(prefix = "entrystore.auth.oidc")
public record OidcCustomConfiguration(
		@DefaultValue("false") boolean enabled,
		String defaultProvider,
		List<String> redirectDomainWhitelist,
		Map<String, Provider> provider,
		RedirectUrl redirectSuccess,
		RedirectUrl redirectFailure
) {
	public OidcCustomConfiguration {
		if (redirectSuccess == null) redirectSuccess = new RedirectUrl("/auth/user");
		if (redirectFailure == null) redirectFailure = new RedirectUrl("/auth/user");
		// Copy the bound collections so a consumer cannot mutate the redirect whitelist or provider routing.
		redirectDomainWhitelist = redirectDomainWhitelist == null ? List.of() : List.copyOf(redirectDomainWhitelist);
		provider = provider == null ? Map.of() : Map.copyOf(provider);
	}

	// Per-provider configuration, keyed in the map by the provider id (entrystore.auth.oidc.provider.{id}.*).
	// The id must match the Spring client-registration id (spring.security.oauth2.client.registration.{id}.*).
	public record Provider(
			@DefaultValue("*") List<String> domains,
			@DefaultValue("false") boolean userAutoProvisioning,
			@DefaultValue(DEFAULT_USERNAME_CLAIM) String usernameClaim
	) {
		/** Claim mapped to the EntryStore username when {@code username-claim} is not configured. */
		public static final String DEFAULT_USERNAME_CLAIM = "email";

		public Provider {
			domains = domains == null ? List.of("*") : List.copyOf(domains);
			// Blank covers programmatic construction; property binding applies the @DefaultValue on null.
			usernameClaim = usernameClaim == null || usernameClaim.isBlank() ? DEFAULT_USERNAME_CLAIM : usernameClaim;
		}
	}

	public record RedirectUrl(String url) {}
}
