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
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

	private static final URI ADMIN_URI = URI.create("http://example.com/_principals/resource/_admin");

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
	void updateSettings_passwordChangeOfAnotherUser_expiresAllSessionsOfThatUser() {
		URI userUri = URI.create("http://example.com/_principals/resource/3");
		User resourceUser = userWithPasswordChangeAllowed(userUri, "Sup3rSecret!", true);
		when(principalManager.getAuthenticatedUserURI()).thenReturn(ADMIN_URI);

		service.updateSettings(entry, passwordBody("Sup3rSecret!"), "session-1");

		// The session making the change is the admin's, so none of the user's sessions is spared.
		verify(authService).expireUserSessions(resourceUser, null);
	}

	@Test
	void updateSettings_disablingAUser_expiresAllSessionsOfThatUser() {
		User resourceUser = mock(User.class);
		when(entry.getResource()).thenReturn(resourceUser);
		when(entry.getResourceURI()).thenReturn(URI.create("http://example.com/_principals/resource/3"));
		when(principalManager.getAuthenticatedUserURI()).thenReturn(ADMIN_URI);

		service.updateSettings(entry, "{\"disabled\":true}".getBytes(StandardCharsets.UTF_8), "session-1");

		verify(resourceUser).setDisabled(true);
		verify(authService).expireUserSessions(resourceUser, null);
	}

	@Test
	void updateSettings_enablingAUser_expiresNoSessions() {
		User resourceUser = mock(User.class);
		when(entry.getResource()).thenReturn(resourceUser);
		when(entry.getResourceURI()).thenReturn(URI.create("http://example.com/_principals/resource/3"));
		when(principalManager.getAuthenticatedUserURI()).thenReturn(ADMIN_URI);

		service.updateSettings(entry, "{\"disabled\":false}".getBytes(StandardCharsets.UTF_8), "session-1");

		verify(resourceUser).setDisabled(false);
		verifyNoInteractions(authService);
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

	@ParameterizedTest(name = "{0}")
	@MethodSource("acceptLanguageHeaders")
	void parseAcceptLanguage_skipsWhatCannotBeParsed(String header, Map<String, Double> expected) {
		assertEquals(expected, UserService.parseAcceptLanguage(header));
	}

	static Stream<Arguments> acceptLanguageHeaders() {
		return Stream.of(
				Arguments.of(Named.of("no header", null), Map.of()),
				Arguments.of(Named.of("empty header", ""), Map.of()),
				Arguments.of("fr-CH;q=0.9, EN-us;q=0.7", Map.of("fr-CH", 0.9, "en-US", 0.7)),
				Arguments.of("en;q=0.9;q=0.9", Map.of("en", 0.9)),
				Arguments.of("sv,en;q=0.8;q=0.9", Map.of("sv", 1.0, "en", 0.8)),
				Arguments.of("en;level=1;q=0.4", Map.of("en", 0.4)),
				Arguments.of("en;q =0.4", Map.of("en", 0.4)),
				Arguments.of("en_US,de;q=0.5", Map.of("de", 0.5)),
				Arguments.of("en;q=abc,de", Map.of("de", 1.0)),
				Arguments.of("de-CH, en;q=1.5", Map.of("de-CH", 1.0)),
				Arguments.of(";", Map.of()),
				Arguments.of("en,;,,de", Map.of("en", 1.0, "de", 1.0)),
				Arguments.of("en;q=0.9,en;q=0.5", Map.of("en", 0.9)),
				Arguments.of("en;q=0.9,en-*;q=0.3", Map.of("en", 0.3)));
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
