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

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.entrystore.Entry;
import org.entrystore.GraphType;
import org.entrystore.repository.RepositoryEvent;
import org.entrystore.repository.RepositoryEventObject;
import org.entrystore.repository.RepositoryListener;
import org.entrystore.repository.RepositoryManager;
import org.entrystore.rest.springboot.service.AuthService;
import org.springframework.stereotype.Component;

/**
 * Ends the form-login sessions of a deleted user on their next request, as 5.x did, instead of when
 * {@link org.entrystore.rest.springboot.filter.ReloadUserPropertiesFilter} next reloads the user. Listens to the
 * repository's deletion events, so every path that removes a user is covered. SSO sessions need no expiry here:
 * {@link org.entrystore.rest.springboot.filter.SetUserURIAfterAuthenticationFilter} looks their user up on every
 * request.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DeletedUserSessionExpirer extends RepositoryListener {

	private final RepositoryManager repositoryManager;
	private final AuthService authService;

	@PostConstruct
	void register() {
		repositoryManager.registerListener(this, RepositoryEvent.EntryDeleted);
	}

	@PreDestroy
	void unregister() {
		repositoryManager.unregisterListener(this, RepositoryEvent.EntryDeleted);
	}

	@Override
	public void repositoryUpdated(RepositoryEventObject eventObject) {
		if (eventObject.getSource() instanceof Entry entry && entry.getGraphType() == GraphType.User) {
			// Thrown from here, it would fail the already committed removal
			try {
				authService.expireUserSessions(entry.getResourceURI(), null);
			} catch (RuntimeException e) {
				log.error("Failed to expire the sessions of deleted user {}", entry.getResourceURI(), e);
			}
		}
	}
}
