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
import org.entrystore.Entry;
import org.entrystore.PrincipalManager;
import org.entrystore.User;
import org.entrystore.rest.springboot.model.exception.BadRequestException;
import org.entrystore.rest.springboot.util.EmailSender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

	@Mock
	private PrincipalManager principalManager;

	@Mock
	private ContextManager contextManager;

	@Mock
	private AuthService authService;

	@Mock
	private EmailSender emailSender;

	@Mock
	private Entry entry;

	private UserService service;

	@BeforeEach
	void setUp() {
		// The mapper is real, since the settings body is parsed with it and a mock would not exercise the
		// parsing these cases depend on.
		service = new UserService(principalManager, contextManager, authService, emailSender,
				JsonMapper.builder().build());
	}

	@Test
	void updateSettings_passwordChange_sendsTheConfirmationEmail() {
		URI userUri = URI.create("http://example.com/_principals/resource/3");
		User resourceUser = userWithPasswordChangeAllowed(userUri, "Sup3rSecret!", true);
		when(principalManager.getAuthenticatedUserURI()).thenReturn(userUri);

		service.updateSettings(entry, passwordBody("Sup3rSecret!"), "session-1");

		verify(resourceUser).setSecret("Sup3rSecret!");
		// Own password change, so only the other sessions of this user are expired.
		verify(authService).expireUserSessions(resourceUser, "session-1");
		verify(emailSender).sendPasswordChangeConfirmation(entry);
	}

	@Test
	void updateSettings_rejectedPassword_sendsNoConfirmationEmail() {
		// setSecret returning false means the password did not change, so a confirmation would tell the
		// user something untrue.
		// No getAuthenticatedUserURI stub: a rejected password throws before session expiry is considered,
		// which is itself worth knowing — nothing is expired and nothing is mailed.
		userWithPasswordChangeAllowed(URI.create("http://example.com/_principals/resource/3"), "weak", false);

		assertThrows(BadRequestException.class,
				() -> service.updateSettings(entry, passwordBody("weak"), "session-1"));

		verifyNoInteractions(emailSender);
		verifyNoInteractions(authService);
	}

	@Test
	void updateSettings_nullJsonBody_throwsBadRequest() {
		// The JSON literal `null` deserializes to a null reference instead of throwing.
		assertThrows(BadRequestException.class,
				() -> service.updateSettings(entry, "null".getBytes(StandardCharsets.UTF_8), "session-1"));

		verify(entry, never()).getResource();
	}

	@Test
	void updateSettings_ownDisabledFlag_throwsBadRequest() {
		URI userUri = URI.create("http://example.com/_principals/resource/3");
		User resourceUser = mock(User.class);
		when(entry.getResource()).thenReturn(resourceUser);
		when(entry.getResourceURI()).thenReturn(userUri);
		when(principalManager.getAuthenticatedUserURI()).thenReturn(userUri);

		assertThrows(BadRequestException.class, () -> service.updateSettings(entry,
				"{\"disabled\":true}".getBytes(StandardCharsets.UTF_8), "session-1"));

		verify(resourceUser, never()).setDisabled(anyBoolean());
	}

	private User userWithPasswordChangeAllowed(URI userUri, String newPassword, boolean accepted) {
		User resourceUser = mock(User.class);
		lenient().when(resourceUser.getURI()).thenReturn(userUri);
		lenient().when(resourceUser.setSecret(newPassword)).thenReturn(accepted);
		when(entry.getResource()).thenReturn(resourceUser);
		// Skips the current-password challenge, which is a separate branch with its own coverage.
		service.setRequireCurrentPassword(false);
		return resourceUser;
	}

	private static byte[] passwordBody(String password) {
		return ("{\"password\":\"" + password + "\"}").getBytes(StandardCharsets.UTF_8);
	}
}
