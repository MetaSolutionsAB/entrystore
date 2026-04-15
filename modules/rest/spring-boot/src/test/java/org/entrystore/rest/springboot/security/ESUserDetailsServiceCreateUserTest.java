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

import org.entrystore.Entry;
import org.entrystore.GraphType;
import org.entrystore.PrincipalManager;
import org.entrystore.User;
import org.entrystore.rest.springboot.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ESUserDetailsServiceCreateUserTest {

	private static final URI CURRENT_USER_URI = URI.create("urn:test:current");
	private static final URI ADMIN_URI = URI.create("urn:test:admin");
	private static final URI RESOURCE_URI = URI.create("urn:test:resource:1");
	private static final URI ENTRY_URI = URI.create("urn:test:entry:1");

	@Mock
	private PrincipalManager pm;

	@Mock
	private UserService userService;

	@Mock
	private User adminUser;

	@Mock
	private Entry createdEntry;

	@Mock
	private User createdUser;

	private ESUserDetailsService service;

	@BeforeEach
	void setUp() {
		service = new ESUserDetailsService(pm, userService);
		when(pm.getAuthenticatedUserURI()).thenReturn(CURRENT_USER_URI);
		when(pm.getAdminUser()).thenReturn(adminUser);
		when(adminUser.getURI()).thenReturn(ADMIN_URI);
	}

	@Test
	void happyPathCreatesUserAndSetsName() {
		when(pm.createResource(null, GraphType.User, null, null)).thenReturn(createdEntry);
		when(createdEntry.getResourceURI()).thenReturn(RESOURCE_URI);
		when(pm.setPrincipalName(RESOURCE_URI, "newuser")).thenReturn(true);
		when(createdEntry.getResource()).thenReturn(createdUser);

		User result = service.createUser("newuser");

		assertSame(createdUser, result);
		verify(pm).setAuthenticatedUserURI(ADMIN_URI);
		verify(pm).setAuthenticatedUserURI(CURRENT_USER_URI); // restored in finally
	}

	@Test
	void createResourceReturningNullThrows() {
		when(pm.createResource(null, GraphType.User, null, null)).thenReturn(null);

		var ex = assertThrows(IllegalStateException.class, () -> service.createUser("newuser"));
		assertTrue(ex.getMessage().contains("newuser"));
		verify(pm).setAuthenticatedUserURI(CURRENT_USER_URI); // restored in finally
	}

	@Test
	void nameCollisionCleansUpOrphanAndThrows() {
		when(pm.createResource(null, GraphType.User, null, null)).thenReturn(createdEntry);
		when(createdEntry.getResourceURI()).thenReturn(RESOURCE_URI);
		when(createdEntry.getEntryURI()).thenReturn(ENTRY_URI);
		when(pm.setPrincipalName(RESOURCE_URI, "taken")).thenReturn(false);

		var ex = assertThrows(IllegalStateException.class, () -> service.createUser("taken"));
		assertTrue(ex.getMessage().contains("taken"));
		verify(pm).remove(ENTRY_URI);
		verify(pm).setAuthenticatedUserURI(CURRENT_USER_URI); // restored in finally
	}

	@Test
	void nameCollisionCleanupFailureStillThrows() {
		when(pm.createResource(null, GraphType.User, null, null)).thenReturn(createdEntry);
		when(createdEntry.getResourceURI()).thenReturn(RESOURCE_URI);
		when(createdEntry.getEntryURI()).thenReturn(ENTRY_URI);
		when(pm.setPrincipalName(RESOURCE_URI, "taken")).thenReturn(false);
		// remove() itself throws — verify the original IllegalStateException still propagates
		doThrow(new RuntimeException("RDF store unavailable")).when(pm).remove(ENTRY_URI);

		var ex = assertThrows(IllegalStateException.class, () -> service.createUser("taken"));
		assertTrue(ex.getMessage().contains("taken"));
		verify(pm).setAuthenticatedUserURI(CURRENT_USER_URI); // restored in finally
	}

	@Test
	void adminUriIsAlwaysRestoredOnException() {
		when(pm.createResource(null, GraphType.User, null, null))
				.thenThrow(new RuntimeException("unexpected"));

		assertThrows(RuntimeException.class, () -> service.createUser("newuser"));
		verify(pm).setAuthenticatedUserURI(CURRENT_USER_URI); // restored in finally
	}
}
