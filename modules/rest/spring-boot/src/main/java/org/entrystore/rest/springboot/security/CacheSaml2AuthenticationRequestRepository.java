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
import lombok.extern.slf4j.Slf4j;
import org.entrystore.rest.springboot.configuration.CaffeineCacheSource;
import org.entrystore.rest.springboot.util.CapacityEvictionWarning;
import org.springframework.security.saml2.provider.service.authentication.AbstractSaml2AuthenticationRequest;
import org.springframework.security.saml2.provider.service.web.Saml2AuthenticationRequestRepository;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Stores SAML authentication requests in a Caffeine cache keyed by the relay state token,
 * instead of in the HTTP session. This avoids the SameSite=Strict cookie problem where
 * the browser withholds the session cookie on the cross-site POST from the IdP back to
 * the ACS endpoint.
 *
 * <p>The cache is written on the anonymous, un-rate-limited login-initiation path, so
 * {@code maximumSize} bounds the heap and {@link CapacityEvictionWarning} reports when the bound
 * bites — the same posture as {@link CacheOAuth2AuthorizationRequestRepository}.
 */
@Slf4j
@Component
public class CacheSaml2AuthenticationRequestRepository
		implements Saml2AuthenticationRequestRepository<AbstractSaml2AuthenticationRequest>, CaffeineCacheSource {

	// expireAfterWrite bounds lifetime only, so request-rate × 120 s of anonymous entries needs a cap;
	// 10k entries ≈ a few MB, far above legitimate concurrent logins.
	static final long MAX_ENTRIES = 10_000;

	private final CapacityEvictionWarning capacityWarning =
			new CapacityEvictionWarning(log, "SAML authentication-request", MAX_ENTRIES);

	private final Cache<String, AbstractSaml2AuthenticationRequest> cache = Caffeine.newBuilder()
			.expireAfterWrite(2, TimeUnit.MINUTES)
			.maximumSize(MAX_ENTRIES)
			.evictionListener(capacityWarning.listener())
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

	/**
	 * Removes and returns the request for the {@code RelayState} of this ACS POST. The atomic
	 * {@code asMap().remove} guarantees that of two concurrent POSTs carrying the same RelayState only
	 * one takes the request out of the cache; a {@code getIfPresent} + {@code invalidate} pair would
	 * hand it to both. This is not a replay guard on its own: {@code Saml2WebSsoAuthenticationFilter}
	 * obtains the request through the converter's non-destructive {@link #loadAuthenticationRequest}
	 * before calling this method and discards the value returned here. The atomic form matches
	 * {@link CacheOAuth2AuthorizationRequestRepository}, whose filter does consume the removed value.
	 */
	@Override
	public AbstractSaml2AuthenticationRequest removeAuthenticationRequest(
			HttpServletRequest request, HttpServletResponse response) {
		String relayState = request.getParameter("RelayState");
		return (relayState != null) ? cache.asMap().remove(relayState) : null;
	}
}
