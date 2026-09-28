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

package org.entrystore.rest.springboot.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.Authentication;
import org.springframework.security.saml2.core.Saml2Error;
import org.springframework.security.saml2.provider.service.authentication.Saml2AuthenticationException;
import org.springframework.security.saml2.provider.service.registration.RelyingPartyRegistrationRepository;
import org.springframework.security.saml2.provider.service.web.OpenSaml5AuthenticationTokenConverter;
import org.springframework.security.web.authentication.AuthenticationConverter;

/** Validates the legacy routing hint without allowing it to override a saved authentication request. */
final class LegacySamlAuthenticationConverter implements AuthenticationConverter {

	private final SamlAcsRequestMatcher matcher;
	private final RelyingPartyRegistrationRepository registrations;
	private final CacheSaml2AuthenticationRequestRepository requests;
	private final OpenSaml5AuthenticationTokenConverter delegate;

	LegacySamlAuthenticationConverter(SamlAcsRequestMatcher matcher,
			RelyingPartyRegistrationRepository registrations, CacheSaml2AuthenticationRequestRepository requests) {
		this.matcher = matcher;
		this.registrations = registrations;
		this.requests = requests;
		delegate = new OpenSaml5AuthenticationTokenConverter(registrations);
		delegate.setRequestMatcher(matcher);
		delegate.setAuthenticationRequestRepository(requests);
	}

	@Override
	public Authentication convert(HttpServletRequest request) {
		if (matcher.isLegacy(request)) {
			String[] ids = request.getParameterValues("idp");
			if (ids == null || ids.length != 1 || ids[0].isBlank()
					|| registrations.findByRegistrationId(ids[0]) == null) {
				throw invalidIdp();
			}
			var saved = requests.loadAuthenticationRequest(request);
			if (saved != null && !ids[0].equals(saved.getRelyingPartyRegistrationId())) {
				throw invalidIdp();
			}
		}
		return delegate.convert(request);
	}

	private static Saml2AuthenticationException invalidIdp() {
		return new Saml2AuthenticationException(new Saml2Error("invalid_registration_id", "Invalid SAML IdP."));
	}
}
