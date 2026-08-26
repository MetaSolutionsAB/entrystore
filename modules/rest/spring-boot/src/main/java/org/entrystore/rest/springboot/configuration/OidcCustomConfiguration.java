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
import org.entrystore.rest.springboot.util.CaseFolding;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.net.URI;
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
	/**
	 * Normalizes and validates the bound values so misconfiguration fails at binding, naming the
	 * key, instead of surfacing later and vaguer. The bound collections are copied so a consumer
	 * cannot mutate the whitelist or provider routing. Blank whitelist elements (a trailing or
	 * doubled comma) carry no semantics and are filtered out rather than rejected — this record
	 * binds even when OIDC is disabled, so rejecting a formatting artifact would abort unrelated
	 * deployments. The surviving entries are lowercased (hostnames are case-insensitive;
	 * {@code OidcAuthService} compares the case-folded request-side host) and must round-trip
	 * through {@code URI.getHost()} unchanged — the invariant the lookup depends on — so any entry
	 * that lookup could never match (scheme, port, path, userinfo, wildcards, spaces, hosts the URI
	 * parser rejects) fails at binding instead of silently dropping every caller-supplied
	 * {@code successurl}/{@code failureurl}. A bracketed IPv6 literal ({@code [::1]}) round-trips
	 * and is therefore allowed. A blank {@code default-provider} is normalized to null so
	 * downstream sees one "not configured" state instead of a whitespace id failing per-request.
	 */
	public OidcCustomConfiguration {
		if (redirectSuccess == null) redirectSuccess = new RedirectUrl("/auth/user");
		if (redirectFailure == null) redirectFailure = new RedirectUrl("/auth/user");
		defaultProvider = StringUtils.trimToNull(defaultProvider);
		redirectDomainWhitelist = redirectDomainWhitelist == null ? List.of()
				: redirectDomainWhitelist.stream()
						.filter(StringUtils::isNotBlank)
						.map(CaseFolding::toLowerCase)
						.toList();
		for (String host : redirectDomainWhitelist) {
			String parsed;
			try {
				parsed = URI.create("https://" + host).getHost();
			} catch (IllegalArgumentException e) {
				parsed = null;
			}
			if (!host.equals(parsed)) {
				throw new IllegalArgumentException("entrystore.auth.oidc.redirect-domain-whitelist entries "
						+ "must be single bare hostnames (or bracketed IPv6 literals) that URI.getHost() can "
						+ "return, without scheme, port, path, userinfo or wildcards; got: '" + host + "'");
			}
		}
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
			// Copy + lowercase: the routing lookup compares against a case-folded request domain —
			// an uppercase config entry would otherwise silently misroute to the wildcard/default.
			domains = domains == null ? List.of("*")
					: domains.stream().map(CaseFolding::toLowerCase).toList();
			// Blank covers programmatic construction; property binding applies the @DefaultValue on null.
			usernameClaim = usernameClaim == null || usernameClaim.isBlank() ? DEFAULT_USERNAME_CLAIM : usernameClaim;
		}
	}

	/**
	 * Rejects blank and syntactically invalid values at binding, naming the key. A blank binding
	 * ({@code entrystore.auth.oidc.redirect-success.url=}) bypasses the null-only defaulting in the
	 * outer constructor; downstream, {@code SecurityConfig}'s {@code setDefaultTargetUrl} and
	 * {@code SimpleUrlAuthenticationFailureHandler} reject it at bean creation anyway — but only
	 * when OIDC is enabled, and without naming the offending key. A malformed URL would otherwise
	 * defer failure to the first post-login redirect at runtime.
	 */
	public record RedirectUrl(String url) {
		public RedirectUrl {
			if (url == null || url.isBlank()) {
				throw new IllegalArgumentException(
						"entrystore.auth.oidc redirect-success.url/redirect-failure.url must not be blank when configured");
			}
			try {
				URI.create(url);
			} catch (IllegalArgumentException e) {
				throw new IllegalArgumentException(
						"entrystore.auth.oidc redirect-success.url/redirect-failure.url is not a valid URI: " + url, e);
			}
		}
	}
}
