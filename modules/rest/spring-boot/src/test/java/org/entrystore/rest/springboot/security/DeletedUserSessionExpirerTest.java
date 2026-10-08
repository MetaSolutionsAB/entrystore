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
import org.entrystore.repository.RepositoryEvent;
import org.entrystore.repository.RepositoryEventObject;
import org.entrystore.repository.RepositoryManager;
import org.entrystore.rest.springboot.service.AuthService;
import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DeletedUserSessionExpirerTest {

	private static final URI USER_URI = URI.create("https://example.org/store/_principals/resource/7");

	private final AuthService authService = mock(AuthService.class);
	private final DeletedUserSessionExpirer expirer =
			new DeletedUserSessionExpirer(mock(RepositoryManager.class), authService);

	@Test
	void deletedUser_hasItsSessionsExpired() {
		expirer.repositoryUpdated(deleted(GraphType.User));

		verify(authService).expireUserSessions(USER_URI, null);
	}

	@Test
	void deletedEntryOfAnotherGraphType_expiresNoSessions() {
		expirer.repositoryUpdated(deleted(GraphType.Group));

		verify(authService, never()).expireUserSessions(any(URI.class), any());
	}

	@Test
	void failureToExpire_doesNotFailTheCommittedRemoval() {
		doThrow(new IllegalStateException("broken")).when(authService).expireUserSessions(USER_URI, null);

		assertDoesNotThrow(() -> expirer.repositoryUpdated(deleted(GraphType.User)));
	}

	private static RepositoryEventObject deleted(GraphType graphType) {
		Entry entry = mock(Entry.class);
		when(entry.getGraphType()).thenReturn(graphType);
		when(entry.getResourceURI()).thenReturn(USER_URI);
		return new RepositoryEventObject(entry, RepositoryEvent.EntryDeleted);
	}
}
