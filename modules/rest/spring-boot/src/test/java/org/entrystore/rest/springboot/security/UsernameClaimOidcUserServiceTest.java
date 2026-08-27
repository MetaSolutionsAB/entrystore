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

import org.entrystore.rest.springboot.service.OidcAuthService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.OidcUserInfo;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UsernameClaimOidcUserServiceTest {

	private static final String REGISTRATION_ID = "keycloak";

	@Mock
	private OidcAuthService oidcAuthService;

	// The registration deliberately has no user-info endpoint, so OidcUserService builds the
	// user from the ID token alone and the test never performs network I/O.
	private static ClientRegistration clientRegistration() {
		return ClientRegistration.withRegistrationId(REGISTRATION_ID)
				.clientId("entrystore")
				.clientSecret("secret")
				.authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
				.redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
				.authorizationUri("https://idp.example.com/authorize")
				.tokenUri("https://idp.example.com/token")
				.scope("openid", "email")
				.build();
	}

	private static OidcUserRequest userRequest(Map<String, Object> idTokenClaims) {
		var accessToken = new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, "access-token",
				Instant.now(), Instant.now().plusSeconds(60), Set.of("openid", "email"));
		var idToken = new OidcIdToken("id-token", Instant.now(), Instant.now().plusSeconds(60), idTokenClaims);
		return new OidcUserRequest(clientRegistration(), accessToken, idToken);
	}

	@Test
	void principalIsNamedAfterTheConfiguredClaim() {
		when(oidcAuthService.usernameClaimFor(REGISTRATION_ID)).thenReturn("email");
		var service = new UsernameClaimOidcUserService(oidcAuthService);

		var user = service.loadUser(userRequest(Map.of("sub", "opaque-subject", "email", "jane@example.com")));

		assertEquals("jane@example.com", user.getName());
	}

	@Test
	void principalIsGrantedRoleUserAlongsideTheOidcAuthorities() {
		when(oidcAuthService.usernameClaimFor(REGISTRATION_ID)).thenReturn("email");
		var service = new UsernameClaimOidcUserService(oidcAuthService);

		var user = service.loadUser(userRequest(Map.of("sub", "opaque-subject", "email", "jane@example.com")));

		// Role parity with SAML (Spring grants ROLE_USER by default) and CAS (CasConfig grants it
		// explicitly): without it, endpoints gated by hasAnyRole(USER, ADMIN) — e.g. GET /auth/tokens —
		// return 403 for OIDC logins. The OIDC_USER authority must survive the remapping.
		var authorityNames = user.getAuthorities().stream().map(a -> a.getAuthority()).toList();
		assertTrue(authorityNames.contains("ROLE_USER"), "ROLE_USER missing: " + authorityNames);
		assertTrue(authorityNames.contains("OIDC_USER"), "OIDC_USER lost in remapping: " + authorityNames);
	}

	@Test
	void nonDefaultClaimIsHonored() {
		when(oidcAuthService.usernameClaimFor(REGISTRATION_ID)).thenReturn("preferred_username");
		var service = new UsernameClaimOidcUserService(oidcAuthService);

		var user = service.loadUser(userRequest(Map.of(
				"sub", "opaque-subject", "email", "jane@example.com", "preferred_username", "jane")));

		assertEquals("jane", user.getName());
	}

	@Test
	void missingClaimIsRejectedInsteadOfFallingBackToAnotherClaim() {
		when(oidcAuthService.usernameClaimFor(REGISTRATION_ID)).thenReturn("email");
		var service = new UsernameClaimOidcUserService(oidcAuthService);

		var ex = assertThrows(OAuth2AuthenticationException.class,
				() -> service.loadUser(userRequest(Map.of("sub", "opaque-subject"))));

		assertEquals(UsernameClaimOidcUserService.ERROR_CODE_INVALID_USERNAME_CLAIM, ex.getError().getErrorCode());
	}

	// Some IdPs deliver claims like `email` only via the userinfo endpoint, not the ID token —
	// the claim must resolve from the merged claim view. Exercised via remapPrincipalName because
	// loadUser's userinfo fetch cannot run without network I/O.
	@Test
	void claimDeliveredOnlyViaUserinfoIsResolved() {
		when(oidcAuthService.usernameClaimFor(REGISTRATION_ID)).thenReturn("email");
		var service = new UsernameClaimOidcUserService(oidcAuthService);
		var idToken = new OidcIdToken("id-token", Instant.now(), Instant.now().plusSeconds(60),
				Map.of("sub", "opaque-subject"));
		var userInfo = new OidcUserInfo(Map.of("sub", "opaque-subject", "email", "jane@example.com"));
		var oidcUser = new DefaultOidcUser(List.of(new SimpleGrantedAuthority("OIDC_USER")), idToken, userInfo, "sub");

		var remapped = service.remapPrincipalName(oidcUser, REGISTRATION_ID);

		assertEquals("jane@example.com", remapped.getName());
	}

	@Test
	void blankClaimValueIsRejected() {
		when(oidcAuthService.usernameClaimFor(REGISTRATION_ID)).thenReturn("email");
		var service = new UsernameClaimOidcUserService(oidcAuthService);

		var ex = assertThrows(OAuth2AuthenticationException.class,
				() -> service.loadUser(userRequest(Map.of("sub", "opaque-subject", "email", " "))));

		assertEquals(UsernameClaimOidcUserService.ERROR_CODE_INVALID_USERNAME_CLAIM, ex.getError().getErrorCode());
	}
}
