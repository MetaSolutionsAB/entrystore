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

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.entrystore.PrincipalManager;
import org.entrystore.User;
import org.entrystore.rest.springboot.configuration.SamlCustomConfiguration;
import org.entrystore.rest.springboot.configuration.SamlCustomConfiguration.Idp;
import org.entrystore.rest.springboot.model.auth.AuthState;
import org.entrystore.rest.springboot.service.SamlAuthService;
import org.entrystore.rest.springboot.service.auth.BasicVerifier;
import org.entrystore.rest.springboot.service.auth.SamlAuthStateCache;
import org.entrystore.rest.springboot.util.HttpUtil;
import org.springframework.security.core.Authentication;
import org.springframework.security.saml2.provider.service.authentication.DefaultSaml2AuthenticatedPrincipal;
import org.springframework.security.saml2.provider.service.authentication.Saml2Authentication;
import org.springframework.security.web.authentication.SavedRequestAwareAuthenticationSuccessHandler;
import org.springframework.security.web.savedrequest.HttpSessionRequestCache;
import org.springframework.stereotype.Component;

import java.io.IOException;

// TODO make it optional bean - instantiated only when saml is enabled
@Slf4j
@Component
@RequiredArgsConstructor
public class SamlLoginSuccessHandler extends SavedRequestAwareAuthenticationSuccessHandler {

	private final ESUserDetailsService userService;
	private final SamlAuthService samlAuthService;
	private final SamlAuthStateCache samlAuthStateCache;
	private final PrincipalManager principalManager;
	private final SamlCustomConfiguration samlConfiguration;

	@Override
	public void onAuthenticationSuccess(HttpServletRequest request,
	                                    HttpServletResponse response,
	                                    Authentication authentication) throws IOException, ServletException {
		try {
			handleSamlAuthentication(request, response, authentication);
		} catch (IOException | ServletException e) {
			throw e;
		} catch (Exception e) {
			String user = authentication != null ? authentication.getName() : "<unknown>";
			log.error("Unexpected {} during SAML login for user '{}': {}",
					e.getClass().getSimpleName(), user, e.getMessage(), e);
			redirectToLoginFailureUrl(request, response, null);
		}
	}

	private void handleSamlAuthentication(HttpServletRequest request,
	                                      HttpServletResponse response,
	                                      Authentication authentication) throws IOException, ServletException {

		if (!(authentication instanceof Saml2Authentication)) {
			// Defense in depth: Spring Security's SAML filter only ever produces Saml2Authentication,
			// but if a future provider/test harness emits a different token type we must not treat
			// that as success — the filter has already persisted it to the SecurityContext.
			// ERROR because reaching this branch indicates a Spring Security wiring bug.
			log.error("Unexpected authentication type '{}' in SAML success handler",
					authentication == null ? "null" : authentication.getClass().getName());
			redirectToLoginFailureUrl(request, response, null);
			return;
		}

		String username = authentication.getName();
		String idpId = null;
		if (authentication.getPrincipal() instanceof DefaultSaml2AuthenticatedPrincipal principal) {
			idpId = principal.getRelyingPartyRegistrationId();
		}

		log.info("Successfully authenticated via SAML IdP '{}', username: '{}'", idpId, username);

		// Extract relay state data from the cache, if present
		AuthState cachedAuthState = null;
		String relayStateId = request.getParameter("RelayState");
		if (relayStateId != null) {
			cachedAuthState = samlAuthStateCache.getAuthState(relayStateId);
		}
		String customRedirectFailureUrl = null;
		if (cachedAuthState != null && cachedAuthState.failureUrl() != null) {
			customRedirectFailureUrl = cachedAuthState.failureUrl();
		}

		if ("admin".equalsIgnoreCase(username)) {
			log.warn("Ignoring received username 'admin' from SAML IdP '{}'", idpId);
			redirectToLoginFailureUrl(request, response, customRedirectFailureUrl);
			return;
		}

		User esUser = userService.loadUser(username);
		if (esUser == null) {
			Idp idpInfo = samlAuthService.findIdpForSamlResponse(idpId);
			if (idpInfo == null || !idpInfo.userAutoProvisioning()) {
				log.warn("User '{}' not found in EntryStore. User auto-provisioning is deactivated for IdP '{}'", username, idpId);
			} else {
				log.info("User '{}' not found in EntryStore. Creating new user since User auto-provisioning is activated for IdP '{}'", username, idpId);
				esUser = userService.createUser(username);
			}
		} else {
			log.info("Existing EntryStore user '{}' logged in via SAML", username);
			// shall we update ES user attributes here if they have changed in the IdP?
		}

		if (esUser != null && !BasicVerifier.isUserDisabled(principalManager, esUser)) {
			if (cachedAuthState != null && cachedAuthState.successUrl() != null
					&& samlAuthService.isValidRedirectUrl(cachedAuthState.successUrl())) {
				log.debug("Redirecting to custom success URL: {}", cachedAuthState.successUrl());
				// Route through the configured RedirectStrategy so CacheAwareRedirectStrategy
				// can stamp Cache-Control: private, no-store before sendRedirect commits the
				// response — preventing a shared cache from replaying the Set-Cookie.
				getRedirectStrategy().sendRedirect(request, response, cachedAuthState.successUrl());
				return;
			}

			// Clear any saved request that might point back to SAML endpoints
			new HttpSessionRequestCache().removeRequest(request, response);
			// Proceeds with standard Spring behavior (redirects to defaultTargetUrl)
			super.onAuthenticationSuccess(request, response, authentication);
			return;
		}

		log.info("Login failed with username '{}' via IdP '{}'", username, idpId);
		redirectToLoginFailureUrl(request, response, customRedirectFailureUrl);
	}

	private void redirectToLoginFailureUrl(HttpServletRequest request, HttpServletResponse response,
	                                       String customRedirectFailureUrl) throws IOException {
		// Spring Security's SAML filter already persisted the authentication to the SecurityContext;
		// undo that before redirecting so the rejected user doesn't remain authenticated.
		HttpUtil.clearAuthenticatedSession(request);
		// isValidRedirectUrl returns false for null, so the guard also covers the no-custom-URL case.
		String redirectUrl = samlConfiguration.redirectFailure().url();
		if (samlAuthService.isValidRedirectUrl(customRedirectFailureUrl)) {
			redirectUrl = customRedirectFailureUrl;
		}
		HttpUtil.redirectOrWriteUnauthorized(response, request.getRequestURI(), redirectUrl,
				"SAML login failed");
	}

	@Override
	protected String determineTargetUrl(HttpServletRequest request, HttpServletResponse response) {
		// ENTRYSTORE-996: a custom success URL is resolved and whitelist-validated from the cached
		// relay state in handleSamlAuthentication. On fall-through we must use only the trusted default
		// target and never derive it from a request parameter (e.g. ?successurl=) — doing so would be an
		// open redirect, since Saml2WebSsoAuthenticationFilter processes the ACS callback independently.
		// Overriding the two-arg variant intercepts the parameter/referer logic in the Spring base class.
		return getDefaultTargetUrl();
	}
}
