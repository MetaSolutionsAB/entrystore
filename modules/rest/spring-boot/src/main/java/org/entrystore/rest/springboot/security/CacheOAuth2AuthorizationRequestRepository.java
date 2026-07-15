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
import org.springframework.security.oauth2.client.web.AuthorizationRequestRepository;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Stores OAuth2/OIDC authorization requests in a Caffeine cache keyed by the {@code state}
 * parameter, instead of in the HTTP session. This avoids the SameSite=Strict cookie problem
 * where the browser withholds the session cookie on the cross-site redirect from the provider
 * back to the {@code /login/oauth2/code/{registrationId}} callback — the OIDC counterpart of
 * {@link CacheSaml2AuthenticationRequestRepository}.
 */
@Component
public class CacheOAuth2AuthorizationRequestRepository
		implements AuthorizationRequestRepository<OAuth2AuthorizationRequest>, CaffeineCacheSource {

	// Cardinality bound: every anonymous request to /oauth2/authorization/{registrationId} mints an
	// entry, and expireAfterWrite bounds only lifetime — without a cap, request-rate × 120 s of
	// entries could exhaust the heap. 10k entries ≈ a few MB, far above legitimate concurrent logins.
	static final long MAX_ENTRIES = 10_000;

	private final Cache<String, OAuth2AuthorizationRequest> cache =
			Caffeine.newBuilder()
					.expireAfterWrite(2, TimeUnit.MINUTES)
					.maximumSize(MAX_ENTRIES)
					.recordStats()
					.build();

	@Override
	public Map<String, Cache<?, ?>> caffeineCaches() {
		return Map.of("oauth2-authz-requests", cache);
	}

	@Override
	public OAuth2AuthorizationRequest loadAuthorizationRequest(HttpServletRequest request) {
		String state = request.getParameter(OAuth2ParameterNames.STATE);
		return (state != null) ? cache.getIfPresent(state) : null;
	}

	@Override
	public void saveAuthorizationRequest(OAuth2AuthorizationRequest authorizationRequest,
										 HttpServletRequest request,
										 HttpServletResponse response) {
		if (authorizationRequest == null) {
			// Spring's session-based repository treats a null request as removal; mirror that contract.
			removeAuthorizationRequest(request, response);
			return;
		}
		if (authorizationRequest.getState() != null) {
			cache.put(authorizationRequest.getState(), authorizationRequest);
		}
	}

	@Override
	public OAuth2AuthorizationRequest removeAuthorizationRequest(HttpServletRequest request,
																 HttpServletResponse response) {
		String state = request.getParameter(OAuth2ParameterNames.STATE);
		if (state != null) {
			OAuth2AuthorizationRequest authorizationRequest = cache.getIfPresent(state);
			cache.invalidate(state);
			return authorizationRequest;
		}
		return null;
	}
}
