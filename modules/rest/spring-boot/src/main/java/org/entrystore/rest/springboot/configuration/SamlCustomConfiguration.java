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

@ConfigurationProperties(prefix = "entrystore.auth.saml")
public record SamlCustomConfiguration(
		@DefaultValue("false") boolean enabled,
		String defaultIdp,
		List<String> redirectDomainWhitelist,
		Map<String, Idp> idp,
		RedirectUrl redirectSuccess,
		RedirectUrl redirectFailure
) {
	public SamlCustomConfiguration {
		if (redirectSuccess == null) redirectSuccess = new RedirectUrl("/auth/user");
		if (redirectFailure == null) redirectFailure = new RedirectUrl("/auth/user");
		// Copy the bound collections so a consumer cannot mutate the redirect whitelist or IdP routing.
		redirectDomainWhitelist = redirectDomainWhitelist == null ? List.of() : List.copyOf(redirectDomainWhitelist);
		idp = idp == null ? Map.of() : Map.copyOf(idp);
	}

	// Per-IdP configuration, keyed in the map by the IdP id (entrystore.auth.saml.idp.{id}.*).
	public record Idp(
			@DefaultValue("*") List<String> domains,
			@DefaultValue("false") boolean userAutoProvisioning,
			Metadata metadata
	) {
		public Idp {
			domains = domains == null ? List.of("*") : List.copyOf(domains);
			metadata = metadata == null ? new Metadata(Metadata.DEFAULT_MAX_AGE_SECONDS) : metadata;
		}

		// Asserting-party (IdP) metadata refresh settings; binds entrystore.auth.saml.idp.{id}.metadata.*.
		// max-age is the staleness ceiling in seconds before the IdP metadata is re-fetched at runtime,
		// so signing-certificate rollovers are picked up without a restart (revived for ENTRYSTORE-1061).
		public record Metadata(@DefaultValue("604800") long maxAge) {
			// 604800 s = 7 days, the legacy default refresh interval (Settings.AUTH_SAML_IDP_METADATA_MAXAGE).
			// Keep the @DefaultValue literal above in sync with this constant.
			public static final long DEFAULT_MAX_AGE_SECONDS = 604800L;
			// Refreshing IdP metadata more often than this would hammer the federation endpoint; below it
			// is treated as a misconfiguration rather than a useful refresh ceiling.
			public static final long MIN_MAX_AGE_SECONDS = 60L;

			public Metadata {
				if (maxAge < MIN_MAX_AGE_SECONDS) {
					throw new IllegalArgumentException(
							"entrystore.auth.saml.idp.<id>.metadata.max-age must be at least "
									+ MIN_MAX_AGE_SECONDS + " seconds");
				}
			}
		}
	}

	public record RedirectUrl(String url) {}
}
