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

package org.entrystore.rest.springboot.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.entrystore.rest.springboot.configuration.OidcCustomConfiguration;
import org.entrystore.rest.springboot.configuration.OidcCustomConfiguration.Provider;
import org.entrystore.rest.springboot.model.exception.BadRequestException;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.util.Locale;

/**
 * OIDC counterpart of {@link SamlAuthService}: redirect-URL whitelist validation, email-domain
 * based provider routing, and resolution of the per-provider username claim.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OidcAuthService {

	private final OidcCustomConfiguration oidcConfiguration;

	public boolean isValidRedirectUrl(String url) {
		if (StringUtils.isEmpty(url)) {
			return false;
		}
		try {
			var uri = URI.create(url);
			var scheme = uri.getScheme();
			if (scheme != null && !"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
				return false;
			}
			// A hostless URL (relative path, opaque URI) can never match a host whitelist; guard
			// explicitly because the whitelist is an immutable List, whose contains(null) throws.
			// Hostnames are case-insensitive: normalize the request side; the configured whitelist
			// is lowercased at binding time (OidcCustomConfiguration).
			var host = uri.getHost();
			return host != null && oidcConfiguration.redirectDomainWhitelist().contains(host.toLowerCase(Locale.ROOT));
		} catch (IllegalArgumentException e) {
			return false;
		}
	}

	public String findProviderIdForRequest(String username, String provider) {

		if (StringUtils.isNotBlank(username)) {
			String domain = StringUtils.substringAfter(username, "@");
			if (!domain.isEmpty()) {
				String providerId = findProviderIdForDomain(domain);
				// Unlike the SAML equivalent, an unmatched domain falls through to the explicit
				// provider parameter / default provider instead of surfacing a null provider id.
				if (providerId != null) {
					return providerId;
				}
			}
		}
		if (StringUtils.isNotBlank(provider)) {
			return provider;
		}

		String defaultProvider = oidcConfiguration.defaultProvider();
		if (StringUtils.isEmpty(defaultProvider)) {
			log.warn("Provider parameter missing and no default OIDC provider configured, unable to select a provider.");
			throw new BadRequestException("Unable to initialize OIDC provider configuration. "
					+ "Provider parameter missing and no default provider configured.");
		}
		return defaultProvider;
	}

	private String findProviderIdForDomain(String domain) {
		String wildcardProvider = null;
		for (var entry : oidcConfiguration.provider().entrySet()) {
			var domains = entry.getValue().domains();
			if (domains.contains("*")) {
				wildcardProvider = entry.getKey();
			}
			if (domains.contains(domain.toLowerCase())) {
				return entry.getKey();
			}
		}
		// we return the provider matching the wildcard only if we cannot find anything more
		// specific for that particular domain, this way we treat wildcards as fallback
		return wildcardProvider;
	}

	public Provider findProviderForCallback(String providerId) {

		if (StringUtils.isNotBlank(providerId)) {
			return oidcConfiguration.provider().get(providerId);
		}

		String defaultProvider = oidcConfiguration.defaultProvider();
		if (StringUtils.isEmpty(defaultProvider)) {
			log.warn("Provider id missing and no default OIDC provider configured, unable to resolve provider configuration. "
					+ "Provider from OIDC callback: {}", providerId);
			return null;
		}
		return oidcConfiguration.provider().get(defaultProvider);
	}

	/**
	 * The claim mapped to the EntryStore username for the given client registration — resolved
	 * against the merged ID-token and userinfo claims (see {@code UsernameClaimOidcUserService}).
	 * A registration without an {@code entrystore.auth.oidc.provider.{id}.*} entry uses the
	 * default claim — such a provider can still log in existing users, but never auto-provisions
	 * (see {@code OidcLoginSuccessHandler#isAutoProvisioningEnabled}).
	 */
	public String usernameClaimFor(String providerId) {
		Provider provider = StringUtils.isNotBlank(providerId) ? oidcConfiguration.provider().get(providerId) : null;
		return provider != null ? provider.usernameClaim() : Provider.DEFAULT_USERNAME_CLAIM;
	}
}
