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
import org.entrystore.rest.springboot.configuration.OidcCustomConfiguration;
import org.entrystore.rest.springboot.configuration.OidcCustomConfiguration.Provider;
import org.entrystore.rest.springboot.model.auth.AuthState;
import org.entrystore.rest.springboot.service.OidcAuthService;
import org.entrystore.rest.springboot.service.auth.OidcAuthStateCache;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Slf4j
@Component
@Conditional(OidcLoginSuccessHandler.OidcEnabledCondition.class)
public class OidcLoginSuccessHandler
		extends AbstractSsoLoginSuccessHandler<OAuth2AuthenticationToken, OidcLoginSuccessHandler.OidcContext> {

	private final OidcAuthService oidcAuthService;
	private final OidcAuthStateCache oidcAuthStateCache;
	private final OidcCustomConfiguration oidcConfiguration;

	public OidcLoginSuccessHandler(ESUserDetailsService userService, OidcAuthService oidcAuthService,
								   OidcAuthStateCache oidcAuthStateCache, PrincipalManager principalManager,
								   OidcCustomConfiguration oidcConfiguration) {
		super(userService, principalManager);
		this.oidcAuthService = oidcAuthService;
		this.oidcAuthStateCache = oidcAuthStateCache;
		this.oidcConfiguration = oidcConfiguration;
	}

	@Override
	protected String authTypeLabel() {
		return "OIDC";
	}

	@Override
	protected Class<OAuth2AuthenticationToken> tokenType() {
		return OAuth2AuthenticationToken.class;
	}

	@Override
	protected OidcContext resolveContext(HttpServletRequest request, OAuth2AuthenticationToken token) {
		if (!(token.getPrincipal() instanceof OidcUser)) {
			// A plain-OAuth2 (non-OIDC) login reaches this handler when a client registration omits
			// the 'openid' scope. UsernameClaimOidcUserService never ran for such a login, so the
			// principal name is whatever Spring defaulted to — fail closed instead of matching or
			// provisioning an unintended EntryStore username. The base class catches this, clears
			// the session and redirects to the default failure URL.
			throw new IllegalStateException("OIDC login for registration '"
					+ token.getAuthorizedClientRegistrationId()
					+ "' did not produce an OIDC principal — the client registration is missing the 'openid' scope");
		}

		AuthState cachedAuthState = null;
		String state = request.getParameter(OAuth2ParameterNames.STATE);
		if (state != null) {
			cachedAuthState = oidcAuthStateCache.getAuthState(state);
		}
		return new OidcContext(token.getAuthorizedClientRegistrationId(), cachedAuthState);
	}

	@Override
	protected String describeAuthSource(OidcContext context) {
		return "OIDC provider '" + context.providerId() + "'";
	}

	@Override
	protected boolean isAutoProvisioningEnabled(OidcContext context) {
		Provider provider = oidcAuthService.findProviderForCallback(context.providerId());
		if (provider == null) {
			log.warn("No provider configuration found for OIDC login from provider '{}' — check entrystore.auth.oidc.provider.*",
					context.providerId());
			return false;
		}
		return provider.userAutoProvisioning();
	}

	@Override
	protected String defaultFailureUrl() {
		return oidcConfiguration.redirectFailure().url();
	}

	@Override
	protected String resolveFailureUrl(OidcContext context) {
		// isValidRedirectUrl returns false for null, so the guard also covers the no-custom-URL case.
		String customRedirectFailureUrl = context.cachedAuthState() != null
				? context.cachedAuthState().failureUrl() : null;
		if (oidcAuthService.isValidRedirectUrl(customRedirectFailureUrl)) {
			return customRedirectFailureUrl;
		}
		return defaultFailureUrl();
	}

	@Override
	protected boolean tryCustomSuccessRedirect(HttpServletRequest request, HttpServletResponse response,
											   OidcContext context) throws IOException {
		AuthState authState = context.cachedAuthState();
		// isValidRedirectUrl returns false for null, so the guard also covers the no-success-URL case.
		if (authState == null || !oidcAuthService.isValidRedirectUrl(authState.successUrl())) {
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

	// Per-request OIDC state resolved once after the token-type guard: the client registration that
	// authenticated the user and the state-keyed entry carrying custom success/failure URLs
	// (whitelist-validated at use).
	record OidcContext(String providerId, AuthState cachedAuthState) {}

	/**
	 * Activates the bean via the same relaxed Boolean binding {@code OidcCustomConfiguration.enabled()}
	 * uses, so values like {@code on}/{@code yes}/{@code 1} enable the bean exactly when the
	 * {@code SecurityConfig} OIDC branch runs. {@code @ConditionalOnProperty(havingValue = "true")}
	 * would match only the literal string and fail startup for relaxed spellings.
	 */
	static class OidcEnabledCondition implements Condition {
		@Override
		public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
			return Binder.get(context.getEnvironment())
					.bind("entrystore.auth.oidc.enabled", Boolean.class)
					.orElse(false);
		}
	}
}
