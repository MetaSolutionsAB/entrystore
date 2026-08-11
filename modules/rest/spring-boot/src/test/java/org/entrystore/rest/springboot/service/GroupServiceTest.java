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

import org.entrystore.ContextManager;
import org.entrystore.PrincipalManager;
import org.entrystore.User;
import org.entrystore.impl.RepositoryManagerImpl;
import org.entrystore.rest.springboot.model.exception.BadRequestException;
import org.entrystore.rest.springboot.model.exception.ForbiddenException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.support.PropertySourcesPlaceholderConfigurer;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Pins the {@code entrystore.nonadmin.group-context-creation} privilege gate in both directions.
 * The gate changed meaning in this release ({@code 1} previously fell through to
 * {@code Boolean.parseBoolean} and meant admin-only; Spring's boolean binding maps it to enabled), and
 * ENTRYSTORE-977 (07e11c31) hardened precisely the path the gate protects — so both "off keeps it
 * admin-only" and "on opens it to authenticated non-admins" need to fail loudly if a refactor flips
 * them. The gate-open cases probe with an invalid name: a {@code BadRequestException} from validation
 * proves the caller was admitted past the gate without needing the admin-escalated creation path.
 */
@ExtendWith(MockitoExtension.class)
class GroupServiceTest {

	private static final URI USER_URI = URI.create("http://example.com/_principals/resource/42");
	private static final URI GUEST_URI = URI.create("http://example.com/_principals/resource/_guest");

	@Mock
	private RepositoryManagerImpl repositoryManager;

	@Mock
	private PrincipalManager principalManager;

	@Mock
	private UserService userService;

	@Mock
	private User guestUser;

	@Mock
	private User requestingUser;

	private GroupService service;

	@BeforeEach
	void setUp() {
		service = new GroupService(repositoryManager, principalManager, userService, new ReservedNamesService());
	}

	@Test
	void guestCaller_isRejectedRegardlessOfTheGate() {
		gate(true);
		when(principalManager.getAuthenticatedUserURI()).thenReturn(GUEST_URI);
		when(principalManager.getGuestUser()).thenReturn(guestUser);
		when(guestUser.getURI()).thenReturn(GUEST_URI);

		assertThrows(ForbiddenException.class, () -> service.createGroup("ctx", "name"));
	}

	@Test
	void unauthenticatedCaller_isRejectedRegardlessOfTheGate() {
		// The other half of the two-part guard: a null authenticated URI must short-circuit before the
		// guest comparison (which would otherwise be the only thing standing between null and the gate).
		gate(true);
		when(principalManager.getAuthenticatedUserURI()).thenReturn(null);

		assertThrows(ForbiddenException.class, () -> service.createGroup("ctx", "name"));
	}

	@Test
	void nonAdmin_withGateOff_isRejected() {
		// The default: absent/off keeps group-with-context creation admin-only.
		gate(false);
		authenticatedNonGuest();
		when(principalManager.getUser(USER_URI)).thenReturn(requestingUser);
		when(userService.isAdmin(requestingUser)).thenReturn(false);

		assertThrows(ForbiddenException.class, () -> service.createGroup("ctx", "name"));
		verify(userService).isAdmin(requestingUser);
	}

	@Test
	void nonAdmin_withGateOn_isAdmittedPastTheGate() {
		gate(true);
		authenticatedNonGuest();

		// The invalid name throws AFTER the gate and BEFORE admin escalation, so reaching validation
		// proves the non-admin was admitted — the admin check must not even run, with any argument
		// (an argument-specific never() could not fail: the gate path calls isAdmin with getUser's
		// result, which is not the requestingUser mock).
		assertThrows(BadRequestException.class, () -> service.createGroup("ctx", "bad**name"));
		verifyNoInteractions(userService);
	}

	@Test
	void admin_withGateOff_isAdmittedPastTheGate() {
		gate(false);
		authenticatedNonGuest();
		when(principalManager.getUser(USER_URI)).thenReturn(requestingUser);
		when(userService.isAdmin(requestingUser)).thenReturn(true);

		assertThrows(BadRequestException.class, () -> service.createGroup("ctx", "bad**name"));
	}

	@ParameterizedTest(name = "gate binds from the configured key with value \"{0}\"")
	@ValueSource(strings = {"on", "true", "yes", "1"})
	void gate_bindsFromTheConfiguredKey(String configured) {
		// ReflectionTestUtils drives the field in the cases above, so only this pins the @Value key:
		// a mistyped key would leave the gate permanently at its default with every other test green.
		// All four strict-boolean enabling spellings are pinned — "on" is the one value where strict
		// binding and the legacy reader agree, so it alone could not catch a parser regression.
		GroupService fromContext = new GroupService(repositoryManager, principalManager, userService,
				new ReservedNamesService());
		new ApplicationContextRunner()
				.withBean(PropertySourcesPlaceholderConfigurer.class)
				.withBean(GroupService.class, () -> fromContext)
				.withPropertyValues("entrystore.nonadmin.group-context-creation=" + configured)
				.run(context -> assertEquals(Boolean.TRUE,
						ReflectionTestUtils.getField(context.getBean(GroupService.class),
								"nonAdminGroupContextCreation"),
						"the gate must bind from entrystore.nonadmin.group-context-creation"));
	}

	private void gate(boolean open) {
		ReflectionTestUtils.setField(service, "nonAdminGroupContextCreation", open);
	}

	private void authenticatedNonGuest() {
		when(principalManager.getAuthenticatedUserURI()).thenReturn(USER_URI);
		when(principalManager.getGuestUser()).thenReturn(guestUser);
		when(guestUser.getURI()).thenReturn(GUEST_URI);
		// createGroup fetches the ContextManager before the guest check; the mock default (null) is fine,
		// but keep the stub lenient so strict stubbing does not flag tests that throw before using it.
		lenient().when(repositoryManager.getContextManager()).thenReturn(mock(ContextManager.class));
	}
}
