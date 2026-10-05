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

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.entrystore.rest.springboot.configuration.SamlCustomConfiguration;
import org.entrystore.rest.springboot.configuration.SamlCustomConfiguration.Idp;
import org.entrystore.rest.springboot.model.exception.BadRequestException;
import org.springframework.boot.context.properties.bind.BindResult;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
public class SamlAuthService {

	private static final String REDIRECT_DOMAIN_WHITELIST = "entrystore.auth.saml.redirect-domain-whitelist";

	private final SamlCustomConfiguration samlConfiguration;

	private final List<String> redirectDomainWhitelist;

	public SamlAuthService(SamlCustomConfiguration samlConfiguration, Environment environment) {
		this.samlConfiguration = samlConfiguration;
		this.redirectDomainWhitelist = resolveRedirectDomainWhitelist(samlConfiguration, Binder.get(environment));
		if (samlConfiguration.enabled()) {
			redirectDomainWhitelist.forEach(domain -> log.info("Allowed domain for redirects: {}", domain));
		}
	}

	/**
	 * The bound list covers the bare and comma-separated value, but Spring counts list indices from 0, so the
	 * 5.x indexed form ({@code .1}, {@code .2}, …) binds to an empty list. That form is read here the way 5.x
	 * read it: from {@code .1} up to the first missing index, and ignored when a bare value is set, even an
	 * empty one.
	 */
	private static List<String> resolveRedirectDomainWhitelist(SamlCustomConfiguration config, Binder binder) {
		boolean bareValue = binder.bind(REDIRECT_DOMAIN_WHITELIST, String.class).isBound();
		if (bareValue && indexedEntry(binder, 1).isBound()) {
			log.warn("{} has both a bare value and indexed entries; the indexed entries are ignored",
					REDIRECT_DOMAIN_WHITELIST);
		}
		if (bareValue || !config.redirectDomainWhitelist().isEmpty()) {
			return config.redirectDomainWhitelist();
		}
		var hosts = new ArrayList<String>();
		BindResult<String> entry;
		for (int i = 1; (entry = indexedEntry(binder, i)).isBound(); i++) {
			hosts.add(entry.get());
		}
		return List.copyOf(hosts);
	}

	private static BindResult<String> indexedEntry(Binder binder, int index) {
		return binder.bind(REDIRECT_DOMAIN_WHITELIST + "." + index, String.class);
	}

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
			var host = uri.getHost();
			return host != null && redirectDomainWhitelist.contains(host);
		} catch (IllegalArgumentException e) {
			return false;
		}
	}

	/**
	 * Where a failed SAML login goes: {@code requestedUrl} if the redirect whitelist admits it, else the configured
	 * default failure URL.
	 */
	public String failureRedirectUrl(String requestedUrl) {
		return isValidRedirectUrl(requestedUrl) ? requestedUrl : samlConfiguration.redirectFailure().url();
	}

	public String findIdpIdForRequest(String username, String idp) {

		if (StringUtils.isNotBlank(username)) {
			String domain = StringUtils.substringAfter(username, "@");
			if (!domain.isEmpty()) {
				return findIdpIdForDomain(domain);
			}
		}
		if (StringUtils.isNotBlank(idp)) {
			return idp;
		}

		String defaultIdp = samlConfiguration.defaultIdp();
		if (StringUtils.isEmpty(defaultIdp)) {
			log.warn("IdP parameter missing and no default IdP configured, unable to properly initialize IDP configuration.");
			throw new BadRequestException("Unable to initialize IDP configuration. IdP parameter missing and no default IdP configured.");
		}
		return defaultIdp;
	}

	private String findIdpIdForDomain(String domain) {
		String wildcardIdp = null;
		for (var entry : samlConfiguration.idp().entrySet()) {
			var domains = entry.getValue().domains();
			if (domains.contains("*")) {
				wildcardIdp = entry.getKey();
			}
			if (domains.contains(domain.toLowerCase())) {
				return entry.getKey();
			}
		}
		// we return the IDP matching the wildcard only if we cannot find anything more
		// specific for that particular domain, this way we treat wildcards as fallback
		return wildcardIdp;
	}

	/**
	 * The IdP whose registration accepted the SAML response, or {@code null}; not {@code default-idp}, whose
	 * policy (e.g. user auto-provisioning) must not apply to another IdP's response.
	 */
	public Idp findIdpForSamlResponse(String idpName) {
		return StringUtils.isNotBlank(idpName) ? samlConfiguration.idp().get(idpName) : null;
	}

}
