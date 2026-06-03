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
import org.apache.commons.lang3.RandomStringUtils;
import org.entrystore.rest.springboot.model.auth.AuthState;
import org.entrystore.rest.springboot.service.SamlAuthService;
import org.entrystore.rest.springboot.service.auth.SamlAuthStateCache;
import org.entrystore.rest.springboot.util.HttpUtil;
import org.springframework.core.convert.converter.Converter;
import org.springframework.stereotype.Component;

/**
 * Resolves the SAML {@code RelayState} for an outgoing authentication request: it mints a random
 * token, persists the requested success/failure redirect URLs that pass validation against it, and
 * returns the token to be carried through the IdP round-trip.
 * <p>
 * This runs inside Spring Security's {@code Saml2WebSsoAuthenticationRequestFilter}, which serves
 * {@code /saml2/authenticate/{registrationId}} independently of {@code AuthController}. The redirect
 * URLs are therefore validated against the domain whitelist <em>here</em> — any URL that fails
 * validation is dropped before it reaches the cache, closing the open-redirect bypass where the
 * filter path skipped the controller's validation (ENTRYSTORE-996).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SamlRelayStateResolver implements Converter<HttpServletRequest, String> {

	private final SamlAuthService samlAuthService;
	private final SamlAuthStateCache samlAuthStateCache;

	@Override
	public String convert(HttpServletRequest request) {
		String relayStateToken = RandomStringUtils.secure().nextAlphanumeric(16);

		String successUrl = request.getParameter("successurl");
		String failureUrl = request.getParameter("failureurl");

		if (successUrl != null && !samlAuthService.isValidRedirectUrl(successUrl)) {
			log.info("Dropping non-whitelisted SAML success redirect URL: {}", HttpUtil.sanitizeForLog(successUrl));
			successUrl = null;
		}
		if (failureUrl != null && !samlAuthService.isValidRedirectUrl(failureUrl)) {
			log.info("Dropping non-whitelisted SAML failure redirect URL: {}", HttpUtil.sanitizeForLog(failureUrl));
			failureUrl = null;
		}

		if (successUrl != null || failureUrl != null) {
			samlAuthStateCache.storeAuthState(relayStateToken, new AuthState(successUrl, failureUrl));
		}

		return relayStateToken;
	}
}
