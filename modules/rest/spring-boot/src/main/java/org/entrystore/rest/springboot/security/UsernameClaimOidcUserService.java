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

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.entrystore.rest.springboot.model.auth.UserAuthRole;
import org.entrystore.rest.springboot.service.OidcAuthService;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * {@link OidcUserService} that names the principal after the per-provider configured claim
 * ({@code entrystore.auth.oidc.provider.{id}.username-claim}, default {@code email}), resolved
 * from the merged ID-token and userinfo claims. The shared SSO success flow
 * ({@code AbstractSsoLoginSuccessHandler}) and the per-request principal mapping
 * ({@code SetUserURIAfterAuthenticationFilter}) both derive the EntryStore username from
 * {@code authentication.getName()}, so remapping the name here is the single point that keeps
 * the two consistent.
 * <p>
 * A login whose tokens carry no usable value for the configured claim is rejected with an
 * {@link OAuth2AuthenticationException} (routed to the OIDC failure handler) instead of falling
 * back to another claim — a silent fallback to e.g. {@code sub} could match or auto-provision an
 * unintended EntryStore user.
 */
@Slf4j
@RequiredArgsConstructor
public class UsernameClaimOidcUserService extends OidcUserService {

	static final String ERROR_CODE_INVALID_USERNAME_CLAIM = "invalid_username_claim";

	private final OidcAuthService oidcAuthService;

	@Override
	public OidcUser loadUser(OidcUserRequest userRequest) throws OAuth2AuthenticationException {
		OidcUser oidcUser = super.loadUser(userRequest);
		return remapPrincipalName(oidcUser, userRequest.getClientRegistration().getRegistrationId());
	}

	// Package-private so the claim resolution (merged ID-token + userinfo claims) is unit-testable
	// without the userinfo-endpoint fetch super.loadUser performs when one is configured.
	OidcUser remapPrincipalName(OidcUser oidcUser, String registrationId) {
		String usernameClaim = oidcAuthService.usernameClaimFor(registrationId);

		// getClaimAsString consults the merged ID-token + userinfo claims, matching what the
		// DefaultOidcUser constructed below resolves getName() against.
		String username = oidcUser.getClaimAsString(usernameClaim);
		if (StringUtils.isBlank(username)) {
			log.warn("OIDC provider '{}' returned no usable '{}' claim — check the registration's scopes "
					+ "and entrystore.auth.oidc.provider.{}.username-claim", registrationId, usernameClaim, registrationId);
			throw new OAuth2AuthenticationException(new OAuth2Error(ERROR_CODE_INVALID_USERNAME_CLAIM,
					"Username claim '" + usernameClaim + "' is missing or blank in the tokens from provider '"
							+ registrationId + "'", null));
		}

		// Role parity with the other SSO protocols: Spring's SAML provider grants ROLE_USER by
		// default and CasConfig grants it explicitly, while OidcUserService yields only
		// OIDC_USER/SCOPE_* authorities — without ROLE_USER an OIDC login would be denied on
		// endpoints gated by hasAnyRole(USER, ADMIN) in SecurityConfig (e.g. GET /auth/tokens).
		Set<GrantedAuthority> authorities = new LinkedHashSet<>(oidcUser.getAuthorities());
		authorities.add(new SimpleGrantedAuthority("ROLE_" + UserAuthRole.USER.name()));
		return new DefaultOidcUser(authorities, oidcUser.getIdToken(), oidcUser.getUserInfo(), usernameClaim);
	}
}
