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

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.entrystore.rest.springboot.configuration.CaffeineCacheSource;
import org.springframework.security.saml2.provider.service.web.Saml2AuthenticationRequestRepository;
import org.springframework.security.saml2.provider.service.authentication.AbstractSaml2AuthenticationRequest;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Stores SAML authentication requests in a Caffeine cache keyed by the relay state token,
 * instead of in the HTTP session. This avoids the SameSite=Strict cookie problem where
 * the browser withholds the session cookie on the cross-site POST from the IdP back to
 * the ACS endpoint.
 */
@Component
public class CacheSaml2AuthenticationRequestRepository
		implements Saml2AuthenticationRequestRepository<AbstractSaml2AuthenticationRequest>, CaffeineCacheSource {

	private final Cache<String, AbstractSaml2AuthenticationRequest> cache =
			Caffeine.newBuilder()
					.expireAfterWrite(2, TimeUnit.MINUTES)
					.recordStats()
					.build();

	@Override
	public Map<String, Cache<?, ?>> caffeineCaches() {
		return Map.of("saml2-authn-requests", cache);
	}

	@Override
	public AbstractSaml2AuthenticationRequest loadAuthenticationRequest(HttpServletRequest request) {
		String relayState = request.getParameter("RelayState");
		return (relayState != null) ? cache.getIfPresent(relayState) : null;
	}

	@Override
	public void saveAuthenticationRequest(AbstractSaml2AuthenticationRequest authRequest,
										  HttpServletRequest request,
										  HttpServletResponse response) {
		if (authRequest != null && authRequest.getRelayState() != null) {
			cache.put(authRequest.getRelayState(), authRequest);
		}
	}

	@Override
	public AbstractSaml2AuthenticationRequest removeAuthenticationRequest(
			HttpServletRequest request, HttpServletResponse response) {
		String relayState = request.getParameter("RelayState");
		if (relayState != null) {
			AbstractSaml2AuthenticationRequest authRequest = cache.getIfPresent(relayState);
			cache.invalidate(relayState);
			return authRequest;
		}
		return null;
	}
}
