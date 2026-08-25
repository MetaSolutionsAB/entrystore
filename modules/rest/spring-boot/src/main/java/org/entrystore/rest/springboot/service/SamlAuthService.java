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
import org.entrystore.rest.springboot.configuration.SamlCustomConfiguration;
import org.entrystore.rest.springboot.configuration.SamlCustomConfiguration.Idp;
import org.entrystore.rest.springboot.model.exception.BadRequestException;
import org.entrystore.rest.springboot.util.CaseFolding;
import org.springframework.stereotype.Service;

import java.net.URI;

@Slf4j
@Service
@RequiredArgsConstructor
public class SamlAuthService {

	private final SamlCustomConfiguration samlConfiguration;

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
			return host != null && samlConfiguration.redirectDomainWhitelist().contains(host);
		} catch (IllegalArgumentException e) {
			return false;
		}
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
			if (domains.contains(CaseFolding.toLowerCase(domain))) {
				return entry.getKey();
			}
		}
		// we return the IDP matching the wildcard only if we cannot find anything more
		// specific for that particular domain, this way we treat wildcards as fallback
		return wildcardIdp;
	}

	public Idp findIdpForSamlResponse(String idpName) {

		if (StringUtils.isNotBlank(idpName)) {
			return samlConfiguration.idp().get(idpName);
		}

		String defaultIdp = samlConfiguration.defaultIdp();
		if (StringUtils.isEmpty(defaultIdp)) {
			log.warn("IdP parameter missing and no default IdP configured, unable to properly initialize IDP configuration. " +
					"IDP from SAML response: {}", idpName);
			return null;
		}
		return samlConfiguration.idp().get(defaultIdp);
	}

}
