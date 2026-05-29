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

package org.entrystore.rest.springboot.service;

import org.entrystore.PrincipalManager;
import org.entrystore.User;
import org.entrystore.rest.springboot.model.exception.ForbiddenException;
import org.entrystore.rest.springboot.security.SsrfValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.net.InetAddress;
import java.net.URI;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProxyServiceTest {

	@Mock
	private PrincipalManager principalManager;

	@Mock
	private ContextService contextService;

	@Mock
	private SsrfValidator ssrfValidator;

	@Mock
	private User guestUser;

	private ProxyService service;

	@BeforeEach
	void setUp() {
		service = new ProxyService(principalManager, contextService, ssrfValidator);
		service.setWhitelistAnon(Set.of());
	}

	@Test
	void validateGlobalAccess_guest_hostNotWhitelisted_throwsForbidden() {
		URI guestUri = URI.create("http://example.com/_principals/resource/_guest");
		when(principalManager.getGuestUser()).thenReturn(guestUser);
		when(guestUser.getURI()).thenReturn(guestUri);
		when(principalManager.getAuthenticatedUserURI()).thenReturn(guestUri);

		assertThrows(ForbiddenException.class,
				() -> service.validateGlobalAccess("example.com"));
	}

	@Test
	void validateGlobalAccess_guest_hostWhitelisted_noException() {
		URI guestUri = URI.create("http://example.com/_principals/resource/_guest");
		when(principalManager.getGuestUser()).thenReturn(guestUser);
		when(guestUser.getURI()).thenReturn(guestUri);
		when(principalManager.getAuthenticatedUserURI()).thenReturn(guestUri);
		service.setWhitelistAnon(Set.of("wikidata.org"));

		assertDoesNotThrow(() -> service.validateGlobalAccess("wikidata.org"));
	}

	@Test
	void validateGlobalAccess_authenticatedUser_noException() {
		URI guestUri = URI.create("http://example.com/_principals/resource/_guest");
		URI userUri = URI.create("http://example.com/_principals/resource/alice");
		when(principalManager.getGuestUser()).thenReturn(guestUser);
		when(guestUser.getURI()).thenReturn(guestUri);
		when(principalManager.getAuthenticatedUserURI()).thenReturn(userUri);

		assertDoesNotThrow(() -> service.validateGlobalAccess("any-host.example.com"));
	}

	@Test
	void validateRedirectTarget_guest_globalProxy_redirectHostNotWhitelisted_throwsForbidden() throws Exception {
		URI base = URI.create("http://whitelisted-upstream.example.com/start");
		String location = "http://evil-public.example.com/landing";
		SsrfValidator.ValidatedTarget redirectTarget = new SsrfValidator.ValidatedTarget(
				URI.create(location), "evil-public.example.com", InetAddress.getByName("192.0.2.1"));
		when(ssrfValidator.validateForProxy("http://evil-public.example.com/landing")).thenReturn(redirectTarget);
		URI guestUri = URI.create("http://example.com/_principals/resource/_guest");
		when(principalManager.getGuestUser()).thenReturn(guestUser);
		when(guestUser.getURI()).thenReturn(guestUri);
		when(principalManager.getAuthenticatedUserURI()).thenReturn(guestUri);
		// Only the initial upstream is whitelisted; the redirect target is not.
		service.setWhitelistAnon(Set.of("whitelisted-upstream.example.com"));

		assertThrows(ForbiddenException.class,
				() -> service.validateRedirectTarget(base, location, true));
	}

	@Test
	void validateRedirectTarget_guest_globalProxy_redirectHostWhitelisted_returnsTarget() throws Exception {
		URI base = URI.create("http://whitelisted-upstream.example.com/start");
		String location = "http://cdn.example.com/asset";
		SsrfValidator.ValidatedTarget redirectTarget = new SsrfValidator.ValidatedTarget(
				URI.create(location), "cdn.example.com", InetAddress.getByName("192.0.2.2"));
		when(ssrfValidator.validateForProxy("http://cdn.example.com/asset")).thenReturn(redirectTarget);
		URI guestUri = URI.create("http://example.com/_principals/resource/_guest");
		when(principalManager.getGuestUser()).thenReturn(guestUser);
		when(guestUser.getURI()).thenReturn(guestUri);
		when(principalManager.getAuthenticatedUserURI()).thenReturn(guestUri);
		service.setWhitelistAnon(Set.of("whitelisted-upstream.example.com", "cdn.example.com"));

		SsrfValidator.ValidatedTarget result = service.validateRedirectTarget(base, location, true);

		assertEquals("cdn.example.com", result.host());
	}

	@Test
	void validateRedirectTarget_guest_contextProxy_redirectHostNotWhitelisted_noException() throws Exception {
		URI base = URI.create("http://some-context-upstream.example.com/start");
		String location = "http://another-public.example.com/landing";
		SsrfValidator.ValidatedTarget redirectTarget = new SsrfValidator.ValidatedTarget(
				URI.create(location), "another-public.example.com", InetAddress.getByName("192.0.2.3"));
		when(ssrfValidator.validateForProxy("http://another-public.example.com/landing")).thenReturn(redirectTarget);
		// Empty anon whitelist would block a guest if it were enforced; the context path must not enforce it,
		// so validateGlobalAccess (and thus principalManager) is never consulted on this path.
		service.setWhitelistAnon(Set.of());

		SsrfValidator.ValidatedTarget result = service.validateRedirectTarget(base, location, false);

		assertEquals("another-public.example.com", result.host());
	}

	@Test
	void validateRedirectTarget_authenticatedUser_globalProxy_redirectHostNotWhitelisted_noException() throws Exception {
		URI base = URI.create("http://whitelisted-upstream.example.com/start");
		String location = "http://public.example.com/landing";
		SsrfValidator.ValidatedTarget redirectTarget = new SsrfValidator.ValidatedTarget(
				URI.create(location), "public.example.com", InetAddress.getByName("192.0.2.4"));
		when(ssrfValidator.validateForProxy("http://public.example.com/landing")).thenReturn(redirectTarget);
		URI guestUri = URI.create("http://example.com/_principals/resource/_guest");
		URI userUri = URI.create("http://example.com/_principals/resource/alice");
		when(principalManager.getGuestUser()).thenReturn(guestUser);
		when(guestUser.getURI()).thenReturn(guestUri);
		when(principalManager.getAuthenticatedUserURI()).thenReturn(userUri);
		// Empty anon whitelist would block a guest, but an authenticated user is exempt from the anon-whitelist check.
		service.setWhitelistAnon(Set.of());

		SsrfValidator.ValidatedTarget result = service.validateRedirectTarget(base, location, true);

		assertEquals("public.example.com", result.host());
	}
}
