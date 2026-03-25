package org.entrystore.rest.springboot.security;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.entrystore.Entry;
import org.entrystore.GraphType;
import org.entrystore.PrincipalManager;
import org.entrystore.User;
import org.entrystore.rest.springboot.configuration.SamlCustomConfiguration;
import org.entrystore.rest.springboot.model.auth.AuthState;
import org.entrystore.rest.springboot.model.auth.SamlIdpInfo;
import org.entrystore.rest.springboot.model.exception.InternalServerErrorException;
import org.entrystore.rest.springboot.model.exception.UnauthorizedException;
import org.entrystore.rest.springboot.service.SamlAuthService;
import org.entrystore.rest.springboot.service.auth.BasicVerifier;
import org.entrystore.rest.springboot.service.auth.SamlAuthStateCache;
import org.jetbrains.annotations.NotNull;
import org.springframework.security.core.Authentication;
import org.springframework.security.saml2.provider.service.authentication.DefaultSaml2AuthenticatedPrincipal;
import org.springframework.security.saml2.provider.service.authentication.Saml2Authentication;
import org.springframework.security.web.authentication.SavedRequestAwareAuthenticationSuccessHandler;
import org.springframework.security.web.savedrequest.HttpSessionRequestCache;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;

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

		if (authentication instanceof Saml2Authentication samlToken) {
			String username = samlToken.getName();
			String idpId = null;
			if (samlToken.getPrincipal() instanceof DefaultSaml2AuthenticatedPrincipal principal) {
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
				redirectToLoginFailureUrl(response, customRedirectFailureUrl);
				return;
			}

			User esUser = userService.loadUser(username);
			if (esUser == null) {
				SamlIdpInfo idpInfo = samlAuthService.findIdpForSamlResponse(idpId);
				if (idpInfo == null || !idpInfo.autoProvisioning()) {
					log.warn("User '{}' not found in EntryStore. User auto-provisioning is deactivated for IdP '{}'", username, idpId);
				} else {
					log.info("User '{}' not found in EntryStore. Creating new user since User auto-provisioning is activated for IdP '{}'", username, idpId);
					esUser = createEsUser(username);
				}
			} else {
				log.info("Existing EntryStore user '{}' logged in via SAML", username);
				// shall we update ES user attributes here if they have changed in the IdP?
			}

			if (esUser != null && !BasicVerifier.isUserDisabled(principalManager, esUser)) {
				if (cachedAuthState != null && cachedAuthState.successUrl() != null) {
					log.debug("Redirecting to custom success URL: {}", cachedAuthState.successUrl());
					response.sendRedirect(cachedAuthState.successUrl());
					return;
				}

				// Clear any saved request that might point back to SAML endpoints
				new HttpSessionRequestCache().removeRequest(request, response);
				// Proceeds with standard Spring behavior (redirects to defaultTargetUrl)
				super.onAuthenticationSuccess(request, response, authentication);
				return;
			}

			log.info("Login failed with username '{}' via IdP '{}'", username, idpId);
			redirectToLoginFailureUrl(response, customRedirectFailureUrl);
		}
	}

	private @NotNull User createEsUser(String username) {
		URI currentUser = principalManager.getAuthenticatedUserURI();
		try {
			principalManager.setAuthenticatedUserURI(principalManager.getAdminUser().getURI());

			Entry entry = principalManager.createResource(null, GraphType.User, null, null);
			if (entry != null) {
				User u = (User) entry.getResource();
				log.info("Created user '{}'", u.getURI());
				principalManager.setPrincipalName(entry.getResourceURI(), username);
				// TODO set some basic metadata, if we can get it from the SAML server
				// Signup.setFoafMetadata(entry, new org.restlet.security.User(...));
				return u;
			} else {
				throw new InternalServerErrorException("An error occurred when creating the new user");
			}

		} finally {
			principalManager.setAuthenticatedUserURI(currentUser);
		}
	}

	private void redirectToLoginFailureUrl(HttpServletResponse response,
										   String customRedirectFailureUrl) throws IOException {
		if (customRedirectFailureUrl != null) {
			log.debug("Redirecting to custom failure URL: {}", customRedirectFailureUrl);
			response.sendRedirect(customRedirectFailureUrl);
		} else if (samlConfiguration.redirectFailure().url() != null) {
			log.debug("Redirecting to default failure URL: {}", samlConfiguration.redirectFailure().url());
			response.sendRedirect(samlConfiguration.redirectFailure().url());
		} else {
			throw new UnauthorizedException("Login with SAML failed.");
		}
	}
}
