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

import org.entrystore.User;
import org.entrystore.rest.springboot.model.auth.SessionInfo;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.session.SessionRegistryImpl;
import org.springframework.security.web.session.HttpSessionIdChangedEvent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class SessionHandleTest {

	@Test
	void generate_returns22Base64UrlCharacters() {
		String handle = SessionHandle.generate();

		assertTrue(handle.matches("[A-Za-z0-9_-]{22}"), handle);
	}

	@Test
	void generate_returnsANewHandleEachTime() {
		assertNotEquals(SessionHandle.generate(), SessionHandle.generate());
	}

	// The handle is kept on the registered details because the registry moves them to the new id as they are
	@Test
	void sessionIdChange_keepsTheHandleOfTheRegisteredSession() {
		var registry = new SessionRegistryImpl();
		var session = new MockHttpSession();
		String oldSessionId = session.getId();
		var springUser = org.springframework.security.core.userdetails.User
				.withUsername("https://example.org/store/_principals/resource/7").password("N/A").build();
		var details = new ESUserSessionDetails(springUser, mock(User.class), SessionInfo.builder().build());
		details.setSessionHandle("handle-of-the-login");
		registry.registerNewSession(oldSessionId, details);
		session.changeSessionId();

		registry.onApplicationEvent(new HttpSessionIdChangedEvent(session, oldSessionId));

		assertNull(registry.getSessionInformation(oldSessionId));
		var registered = (ESUserSessionDetails) registry.getSessionInformation(session.getId()).getPrincipal();
		assertEquals("handle-of-the-login", registered.getSessionHandle());
	}
}
