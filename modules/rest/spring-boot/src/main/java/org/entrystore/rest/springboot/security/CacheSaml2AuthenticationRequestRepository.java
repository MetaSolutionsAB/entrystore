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
import com.github.benmanes.caffeine.cache.Ticker;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.entrystore.rest.springboot.configuration.CaffeineCacheSource;
import org.entrystore.rest.springboot.configuration.SamlCustomConfiguration;
import org.entrystore.rest.springboot.util.OldestFirstBoundedCache;
import org.springframework.security.saml2.provider.service.authentication.AbstractSaml2AuthenticationRequest;
import org.springframework.security.saml2.provider.service.web.Saml2AuthenticationRequestRepository;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Stores SAML authentication requests in a Caffeine cache keyed by the relay state token,
 * instead of in the HTTP session. This avoids the SameSite=Strict cookie problem where
 * the browser withholds the session cookie on the cross-site POST from the IdP back to
 * the ACS endpoint.
 *
 * <p>Entries expire after {@link SamlCustomConfiguration#requestLifetime()}: Spring validates the response's
 * {@code InResponseTo} against the request loaded here, so a login that outlives its entry fails.
 *
 * <p>The cache is written on the anonymous login-start path, which {@link SsoLoginRateLimitFilter} limits per client.
 * Against a flood from many clients, {@link OldestFirstBoundedCache} bounds the heap and evicts the oldest login first.
 */
@Slf4j
@Component
public class CacheSaml2AuthenticationRequestRepository
		implements Saml2AuthenticationRequestRepository<AbstractSaml2AuthenticationRequest>, CaffeineCacheSource {

	// At about 2 KB per request, 50k entries ≈ 100 MB at worst; a flood needs ~55 req/s for 15 minutes to fill it.
	static final long MAX_ENTRIES = 50_000;

	private final OldestFirstBoundedCache<String, AbstractSaml2AuthenticationRequest> cache;

	public CacheSaml2AuthenticationRequestRepository(SamlCustomConfiguration samlConfiguration, Ticker ticker) {
		this.cache = new OldestFirstBoundedCache<>(samlConfiguration.requestLifetime(), MAX_ENTRIES, ticker, log,
				"SAML authentication-request");
	}

	@Override
	public Map<String, Cache<?, ?>> caffeineCaches() {
		return Map.of("saml2-authn-requests", cache.caffeineCache());
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
		if (authRequest == null) {
			// As in Spring's session repository a null request means removal, here of the one named by RelayState.
			removeAuthenticationRequest(request, response);
			return;
		}
		if (authRequest.getRelayState() != null) {
			cache.put(authRequest.getRelayState(), authRequest);
		}
	}

	/**
	 * Removes and returns the request for the {@code RelayState} of this ACS POST. The atomic
	 * {@link OldestFirstBoundedCache#remove} guarantees that of two concurrent POSTs carrying the same RelayState
	 * only one takes the request out of the cache; a {@code getIfPresent} + {@code invalidate} pair would
	 * hand it to both. This is not a replay guard on its own: {@code Saml2WebSsoAuthenticationFilter}
	 * obtains the request through the converter's non-destructive {@link #loadAuthenticationRequest}
	 * before calling this method and discards the value returned here. The atomic form matches
	 * {@link CacheOAuth2AuthorizationRequestRepository}, whose filter does consume the removed value.
	 */
	@Override
	public AbstractSaml2AuthenticationRequest removeAuthenticationRequest(
			HttpServletRequest request, HttpServletResponse response) {
		String relayState = request.getParameter("RelayState");
		return (relayState != null) ? cache.remove(relayState) : null;
	}
}
