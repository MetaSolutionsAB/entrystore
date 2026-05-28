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

import java.net.URI;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
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
}
