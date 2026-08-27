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
import lombok.extern.slf4j.Slf4j;
import org.entrystore.rest.springboot.model.auth.AuthState;
import org.entrystore.rest.springboot.service.OidcAuthService;
import org.entrystore.rest.springboot.service.auth.OidcAuthStateCache;
import org.entrystore.rest.springboot.util.HttpUtil;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestRedirectFilter;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;

/**
 * Resolves the authorization request for an outgoing OIDC login and persists the requested
 * success/failure redirect URLs that pass validation, keyed by the request's {@code state}
 * parameter — the OIDC counterpart of {@code SamlRelayStateResolver} (there the key is the
 * self-minted RelayState; here Spring Security has already minted the CSRF-binding state).
 * <p>
 * This runs inside Spring Security's {@code OAuth2AuthorizationRequestRedirectFilter}, which
 * serves {@code /oauth2/authorization/{registrationId}} independently of {@code AuthController}.
 * The redirect URLs are therefore validated against the domain whitelist <em>here</em> — any URL
 * that fails validation is dropped before it reaches the cache, so the filter path cannot bypass
 * the controller's validation (ENTRYSTORE-996 parity).
 */
@Slf4j
@RequiredArgsConstructor
public class OidcAuthorizationRequestResolver implements OAuth2AuthorizationRequestResolver {

	private final OAuth2AuthorizationRequestResolver delegate;
	private final OidcAuthService oidcAuthService;
	private final OidcAuthStateCache oidcAuthStateCache;

	public OidcAuthorizationRequestResolver(ClientRegistrationRepository clientRegistrationRepository,
											OidcAuthService oidcAuthService, OidcAuthStateCache oidcAuthStateCache) {
		this(new DefaultOAuth2AuthorizationRequestResolver(clientRegistrationRepository,
						OAuth2AuthorizationRequestRedirectFilter.DEFAULT_AUTHORIZATION_REQUEST_BASE_URI),
				oidcAuthService, oidcAuthStateCache);
	}

	@Override
	public OAuth2AuthorizationRequest resolve(HttpServletRequest request) {
		return cacheValidatedAuthState(request, delegate.resolve(request));
	}

	@Override
	public OAuth2AuthorizationRequest resolve(HttpServletRequest request, String clientRegistrationId) {
		return cacheValidatedAuthState(request, delegate.resolve(request, clientRegistrationId));
	}

	private OAuth2AuthorizationRequest cacheValidatedAuthState(HttpServletRequest request,
															   OAuth2AuthorizationRequest authorizationRequest) {
		if (authorizationRequest == null || authorizationRequest.getState() == null) {
			return authorizationRequest;
		}

		String successUrl = request.getParameter("successurl");
		String failureUrl = request.getParameter("failureurl");

		if (successUrl != null && !oidcAuthService.isValidRedirectUrl(successUrl)) {
			log.info("Dropping non-whitelisted OIDC success redirect URL: {}", HttpUtil.sanitizeForLog(successUrl));
			successUrl = null;
		}
		if (failureUrl != null && !oidcAuthService.isValidRedirectUrl(failureUrl)) {
			log.info("Dropping non-whitelisted OIDC failure redirect URL: {}", HttpUtil.sanitizeForLog(failureUrl));
			failureUrl = null;
		}

		if (successUrl != null || failureUrl != null) {
			oidcAuthStateCache.storeAuthState(authorizationRequest.getState(), new AuthState(successUrl, failureUrl));
		}

		return authorizationRequest;
	}
}
