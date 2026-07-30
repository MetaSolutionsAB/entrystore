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
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.entrystore.PrincipalManager;
import org.entrystore.rest.springboot.configuration.SamlCustomConfiguration;
import org.entrystore.rest.springboot.configuration.SamlCustomConfiguration.Idp;
import org.entrystore.rest.springboot.model.auth.AuthState;
import org.entrystore.rest.springboot.service.SamlAuthService;
import org.entrystore.rest.springboot.service.auth.SamlAuthStateCache;
import org.entrystore.rest.springboot.util.ErrorResponseWriter;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.security.saml2.provider.service.authentication.DefaultSaml2AuthenticatedPrincipal;
import org.springframework.security.saml2.provider.service.authentication.Saml2Authentication;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Slf4j
@Component
@Conditional(SamlLoginSuccessHandler.SamlEnabledCondition.class)
public class SamlLoginSuccessHandler
		extends AbstractSsoLoginSuccessHandler<Saml2Authentication, SamlLoginSuccessHandler.SamlContext> {

	private final SamlAuthService samlAuthService;
	private final SamlAuthStateCache samlAuthStateCache;
	private final SamlCustomConfiguration samlConfiguration;

	public SamlLoginSuccessHandler(ESUserDetailsService userService, SamlAuthService samlAuthService,
								   SamlAuthStateCache samlAuthStateCache, PrincipalManager principalManager,
								   ErrorResponseWriter errorResponseWriter,
								   SamlCustomConfiguration samlConfiguration) {
		super(userService, principalManager, errorResponseWriter);
		this.samlAuthService = samlAuthService;
		this.samlAuthStateCache = samlAuthStateCache;
		this.samlConfiguration = samlConfiguration;
	}

	@Override
	protected String authTypeLabel() {
		return "SAML";
	}

	@Override
	protected Class<Saml2Authentication> tokenType() {
		return Saml2Authentication.class;
	}

	@Override
	protected SamlContext resolveContext(HttpServletRequest request, Saml2Authentication token) {
		String idpId = null;
		if (token.getPrincipal() instanceof DefaultSaml2AuthenticatedPrincipal principal) {
			idpId = principal.getRelyingPartyRegistrationId();
		}

		AuthState cachedAuthState = null;
		String relayStateId = request.getParameter("RelayState");
		if (relayStateId != null) {
			cachedAuthState = samlAuthStateCache.getAuthState(relayStateId);
		}
		return new SamlContext(idpId, cachedAuthState);
	}

	@Override
	protected String describeAuthSource(SamlContext context) {
		return "SAML IdP '" + context.idpId() + "'";
	}

	@Override
	protected boolean isAutoProvisioningEnabled(SamlContext context) {
		Idp idpInfo = samlAuthService.findIdpForSamlResponse(context.idpId());
		if (idpInfo == null) {
			log.warn("No IdP configuration found for SAML response from IdP '{}' — check entrystore.auth.saml.idp.*",
					context.idpId());
			return false;
		}
		return idpInfo.userAutoProvisioning();
	}

	@Override
	protected String defaultFailureUrl() {
		return samlConfiguration.redirectFailure().url();
	}

	@Override
	protected String resolveFailureUrl(SamlContext context) {
		// isValidRedirectUrl returns false for null, so the guard also covers the no-custom-URL case.
		String customRedirectFailureUrl = context.cachedAuthState() != null
				? context.cachedAuthState().failureUrl() : null;
		if (samlAuthService.isValidRedirectUrl(customRedirectFailureUrl)) {
			return customRedirectFailureUrl;
		}
		return defaultFailureUrl();
	}

	@Override
	protected boolean tryCustomSuccessRedirect(HttpServletRequest request, HttpServletResponse response,
											   SamlContext context) throws IOException {
		AuthState authState = context.cachedAuthState();
		// isValidRedirectUrl returns false for null, so the guard also covers the no-success-URL case.
		if (authState == null || !samlAuthService.isValidRedirectUrl(authState.successUrl())) {
			return false;
		}
		String successUrl = authState.successUrl();
		log.debug("Redirecting to custom success URL: {}", successUrl);
		// Route through the configured RedirectStrategy so CacheAwareRedirectStrategy
		// can stamp Cache-Control: private, no-store before sendRedirect commits the
		// response — preventing a shared cache from replaying the Set-Cookie.
		getRedirectStrategy().sendRedirect(request, response, successUrl);
		return true;
	}

	// Per-request SAML state resolved once after the token-type guard: the IdP that authenticated the
	// user and the relay-state entry carrying custom success/failure URLs (whitelist-validated at use).
	record SamlContext(String idpId, AuthState cachedAuthState) {}

	/**
	 * Activates the bean via the same relaxed Boolean binding {@code SamlCustomConfiguration.enabled()}
	 * uses, so values like {@code on}/{@code yes}/{@code 1} enable the bean exactly when the
	 * {@code SecurityConfig} SAML branch runs. {@code @ConditionalOnProperty(havingValue = "true")}
	 * would match only the literal string and fail startup for relaxed spellings.
	 */
	static class SamlEnabledCondition implements Condition {
		@Override
		public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
			return Binder.get(context.getEnvironment())
					.bind("entrystore.auth.saml.enabled", Boolean.class)
					.orElse(false);
		}
	}
}
