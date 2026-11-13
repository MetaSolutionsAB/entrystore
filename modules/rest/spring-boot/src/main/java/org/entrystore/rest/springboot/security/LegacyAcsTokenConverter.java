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
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpMethod;
import org.springframework.security.core.Authentication;
import org.springframework.security.saml2.provider.service.authentication.Saml2AuthenticationToken;
import org.springframework.security.saml2.provider.service.registration.RelyingPartyRegistration;
import org.springframework.security.web.authentication.AuthenticationConverter;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

import java.util.ArrayList;
import java.util.List;

/**
 * Makes a SAML response posted to the 5.x assertion consumer service URL without its {@code idp} parameter
 * validate against the URL it was posted to. The ACS location of a 5.x multi-IdP registration carries
 * {@code idp=<registrationId>}, but some IdPs post to the URL without it and name that URL in the response's
 * {@code Destination} and the assertion's {@code Recipient}, both of which Spring requires to equal the ACS
 * location.
 *
 * <p>For such a POST, the token gets the registration's ACS location without that one parameter. The registration
 * stays the one the delegate resolved, and Spring's response and assertion validation runs unchanged. Every other
 * token is returned as is: a POST with {@code idp}, a response to Spring's ACS paths, and a registration whose ACS
 * location has no {@code idp} parameter naming it.
 */
@RequiredArgsConstructor
public final class LegacyAcsTokenConverter implements AuthenticationConverter {

	private static final RequestMatcher LEGACY_ACS_POST =
			PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, SamlAcsRequestMatcher.LEGACY_ACS_PATH);

	private final AuthenticationConverter delegate;
	private final SamlAcsRequestMatcher acsMatcher;

	@Override
	public @Nullable Authentication convert(HttpServletRequest request) {
		Authentication authentication = delegate.convert(request);
		if (!(authentication instanceof Saml2AuthenticationToken token) || !LEGACY_ACS_POST.matches(request)) {
			return authentication;
		}
		if (acsMatcher.matcher(request).getVariables().containsKey("registrationId")) {
			return token;
		}
		RelyingPartyRegistration registration = token.getRelyingPartyRegistration();
		String location = withoutIdpParameter(registration.getAssertionConsumerServiceLocation(),
				registration.getRegistrationId());
		if (location == null) {
			return token;
		}
		return new Saml2AuthenticationToken(registration.mutate().assertionConsumerServiceLocation(location).build(),
				token.getSaml2Response(), token.getAuthenticationRequest());
	}

	/**
	 * The location without its {@code idp} query parameter, or null unless it has exactly one {@code idp} parameter
	 * and its value is the registration id. The rest of the location is kept verbatim.
	 */
	static @Nullable String withoutIdpParameter(String location, String registrationId) {
		int queryStart = location.indexOf('?');
		if (queryStart < 0) {
			return null;
		}
		String idpPrefix = SamlAcsRequestMatcher.LEGACY_IDP_PARAMETER + "=";
		List<String> kept = new ArrayList<>();
		String idp = null;
		int idpCount = 0;
		for (String parameter : location.substring(queryStart + 1).split("&", -1)) {
			if (parameter.equals(SamlAcsRequestMatcher.LEGACY_IDP_PARAMETER) || parameter.startsWith(idpPrefix)) {
				idpCount++;
				idp = parameter.substring(Math.min(parameter.length(), idpPrefix.length()));
			} else {
				kept.add(parameter);
			}
		}
		if (idpCount != 1 || !registrationId.equals(idp)) {
			return null;
		}
		String path = location.substring(0, queryStart);
		return kept.isEmpty() ? path : path + "?" + String.join("&", kept);
	}
}
