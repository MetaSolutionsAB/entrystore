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

import org.entrystore.Context;
import org.entrystore.ContextManager;
import org.entrystore.Entry;
import org.entrystore.GraphType;
import org.entrystore.PrincipalManager;
import org.entrystore.Resource;
import org.entrystore.User;
import org.entrystore.repository.security.Password;
import org.entrystore.rest.springboot.model.api.UserSettingsRequestBody;
import org.entrystore.rest.springboot.model.exception.BadRequestException;
import org.entrystore.rest.springboot.model.exception.ForbiddenException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserSettingsServiceTest {

	private static final URI TARGET = URI.create("http://example.com/_principals/resource/target");
	private static final URI CALLER = URI.create("http://example.com/_principals/resource/caller");
	private static final URI ADMIN = URI.create("http://example.com/_principals/resource/_admin");
	private static final String SESSION = "session-1";
	private static final String NEW_PASSWORD = "Sup3rSecret!";

	@Mock
	private PrincipalManager principalManager;

	@Mock
	private ContextManager contextManager;

	@Mock
	private AuthService authService;

	@Mock
	private Entry entry;

	@Mock
	private User user;

	private UserSettingsService service;

	@BeforeEach
	void setUp() {
		// The mapper is real: parsing the body is part of the behaviour under test.
		service = new UserSettingsService(principalManager, contextManager, authService, JsonMapper.builder().build());
		// @Value is not processed here, so the production default is set explicitly; tests that turn the
		// challenge off say so themselves.
		service.setRequireCurrentPassword(true);
	}

	@Test
	void updateUser_malformedJson_isRejectedWithTheSyntaxMessage() {
		BadRequestException ex = assertThrows(BadRequestException.class,
				() -> service.updateUser(entry, body("{\"name\":"), SESSION));

		assertEquals(UserSettingsRequestBody.SYNTAX_ERROR_MESSAGE, ex.getMessage());
		verifyNoInteractions(user, authService);
	}

	@Test
	void updateUser_jsonNullLiteral_isRejectedWithTheSyntaxMessage() {
		// `null` deserializes to a null reference rather than failing, so it needs its own guard.
		BadRequestException ex = assertThrows(BadRequestException.class,
				() -> service.updateUser(entry, body("null"), SESSION));

		assertEquals(UserSettingsRequestBody.SYNTAX_ERROR_MESSAGE, ex.getMessage());
		verifyNoInteractions(user, authService);
	}

	@Test
	void updateUser_name_isAppliedToTheUser() {
		userBehindEntry(TARGET, CALLER);
		when(user.setName("alice")).thenReturn(true);

		assertDoesNotThrow(() -> service.updateUser(entry, body("{\"name\":\"alice\"}"), SESSION));

		verify(user).setName("alice");
	}

	@Test
	void updateUser_nameAlreadyInUse_isRejectedNamingTheName() {
		userBehindEntry(TARGET, CALLER);
		when(user.setName("alice")).thenReturn(false);

		BadRequestException ex = assertThrows(BadRequestException.class,
				() -> service.updateUser(entry, body("{\"name\":\"alice\"}"), SESSION));

		assertEquals("Name is already in use: alice", ex.getMessage());
	}

	@Test
	void updateUser_nonAdminWithoutCurrentPassword_isForbidden() {
		userBehindEntry(TARGET, TARGET);
		when(principalManager.currentUserIsAdminOrAdminGroup()).thenReturn(false);

		ForbiddenException ex = assertThrows(ForbiddenException.class,
				() -> service.updateUser(entry, body("{\"password\":\"" + NEW_PASSWORD + "\"}"), SESSION));

		assertEquals("Current password is required", ex.getMessage());
		verify(user, never()).setSecret(any());
		verifyNoInteractions(authService);
	}

	@Test
	void updateUser_adminChangingOwnPasswordWithoutCurrentPassword_isForbidden() {
		// Admin rights do not exempt the admin's own account from the challenge.
		userBehindEntry(TARGET, TARGET);
		when(principalManager.currentUserIsAdminOrAdminGroup()).thenReturn(true);

		ForbiddenException ex = assertThrows(ForbiddenException.class,
				() -> service.updateUser(entry, body("{\"password\":\"" + NEW_PASSWORD + "\"}"), SESSION));

		assertEquals("Current password is required", ex.getMessage());
		verify(user, never()).setSecret(any());
	}

	@Test
	void updateUser_adminChangingAnotherUsersPassword_needsNoCurrentPasswordAndExpiresAllSessions() {
		userBehindEntry(TARGET, CALLER);
		when(principalManager.currentUserIsAdminOrAdminGroup()).thenReturn(true);
		when(user.setSecret(NEW_PASSWORD)).thenReturn(true);

		service.updateUser(entry, body("{\"password\":\"" + NEW_PASSWORD + "\"}"), SESSION);

		verify(principalManager, never()).getPrincipalEntry(any());
		// Somebody else's sessions: none of them is the caller's, so none is spared.
		verify(authService).completePasswordChange(user, null);
	}

	@Test
	void updateUser_wrongCurrentPassword_isForbidden() {
		userBehindEntry(TARGET, TARGET);
		when(principalManager.currentUserIsAdminOrAdminGroup()).thenReturn(false);
		storedPasswordIs("correct");

		ForbiddenException ex = assertThrows(ForbiddenException.class, () -> service.updateUser(entry,
				body("{\"password\":\"" + NEW_PASSWORD + "\",\"currentPassword\":\"wrong\"}"), SESSION));

		assertEquals("No password set or incorrect current password provided", ex.getMessage());
		verify(user, never()).setSecret(any());
		verifyNoInteractions(authService);
	}

	@Test
	void updateUser_correctCurrentPassword_changesThePasswordAndSparesTheCallersSession() {
		userBehindEntry(TARGET, TARGET);
		when(principalManager.currentUserIsAdminOrAdminGroup()).thenReturn(false);
		storedPasswordIs("correct");
		when(user.setSecret(NEW_PASSWORD)).thenReturn(true);

		service.updateUser(entry,
				body("{\"password\":\"" + NEW_PASSWORD + "\",\"currentPassword\":\"correct\"}"), SESSION);

		verify(authService).completePasswordChange(user, SESSION);
	}

	@Test
	void updateUser_currentPasswordNotRequiredByConfig_skipsTheChallenge() {
		userBehindEntry(TARGET, TARGET);
		service.setRequireCurrentPassword(false);
		when(user.setSecret(NEW_PASSWORD)).thenReturn(true);

		service.updateUser(entry, body("{\"password\":\"" + NEW_PASSWORD + "\"}"), SESSION);

		verify(principalManager, never()).getPrincipalEntry(any());
		verify(authService).completePasswordChange(user, SESSION);
	}

	@Test
	void updateUser_rejectedPassword_isBadRequestWithNoSessionOrMailSideEffects() {
		// setSecret returning false means the policy refused the password: nothing changed, so nothing may be
		// expired or announced.
		userBehindEntry(TARGET, TARGET);
		service.setRequireCurrentPassword(false);
		when(user.setSecret("weak")).thenReturn(false);

		BadRequestException ex = assertThrows(BadRequestException.class,
				() -> service.updateUser(entry, body("{\"password\":\"weak\"}"), SESSION));

		assertEquals("Password must conform to configured rules.", ex.getMessage());
		verifyNoInteractions(authService);
	}

	@Test
	void updateUser_emptyLanguage_clearsTheLanguage() {
		userBehindEntry(TARGET, CALLER);

		service.updateUser(entry, body("{\"language\":\"\"}"), SESSION);

		verify(user).setLanguage(null);
	}

	@Test
	void updateUser_language_isAppliedToTheUser() {
		userBehindEntry(TARGET, CALLER);
		when(user.setLanguage("pl")).thenReturn(true);

		assertDoesNotThrow(() -> service.updateUser(entry, body("{\"language\":\"pl\"}"), SESSION));

		verify(user).setLanguage("pl");
	}

	@Test
	void updateUser_languageRefusedByTheUser_isBadRequest() {
		userBehindEntry(TARGET, CALLER);
		when(user.setLanguage("xx")).thenReturn(false);

		BadRequestException ex = assertThrows(BadRequestException.class,
				() -> service.updateUser(entry, body("{\"language\":\"xx\"}"), SESSION));

		assertEquals("Preferred language could not be set.", ex.getMessage());
	}

	@Test
	void updateUser_homeContext_isAppliedWhenTheIdResolvesToAContext() {
		userBehindEntry(TARGET, CALLER);
		Context home = mock(Context.class);
		Entry homeEntry = entryWithResource(home);
		when(contextManager.get("home")).thenReturn(homeEntry);
		when(user.setHomeContext(home)).thenReturn(true);

		assertDoesNotThrow(() -> service.updateUser(entry, body("{\"homecontext\":\"home\"}"), SESSION));

		verify(user).setHomeContext(home);
	}

	@Test
	void updateUser_homeContextResolvingToSomethingElse_isBadRequest() {
		userBehindEntry(TARGET, CALLER);
		Entry notesEntry = entryWithResource(mock(Resource.class));
		when(contextManager.get("notes")).thenReturn(notesEntry);

		BadRequestException ex = assertThrows(BadRequestException.class,
				() -> service.updateUser(entry, body("{\"homecontext\":\"notes\"}"), SESSION));

		assertEquals("Given homecontext is not a context.", ex.getMessage());
		verify(user, never()).setHomeContext(any());
	}

	@Test
	void updateUser_unknownHomeContext_isIgnored() {
		// Long-standing wire behaviour: an id that resolves to nothing leaves the setting alone and answers 2xx.
		userBehindEntry(TARGET, CALLER);
		when(contextManager.get("nowhere")).thenReturn(null);

		assertDoesNotThrow(() -> service.updateUser(entry, body("{\"homecontext\":\"nowhere\"}"), SESSION));

		verify(user, never()).setHomeContext(any());
	}

	@Test
	void updateUser_disablingOwnAccount_isBadRequest() {
		userBehindEntry(TARGET, TARGET);

		BadRequestException ex = assertThrows(BadRequestException.class,
				() -> service.updateUser(entry, body("{\"disabled\":true}"), SESSION));

		assertEquals("Users cannot set their own disabled status.", ex.getMessage());
		verify(user, never()).setDisabled(anyBoolean());
	}

	@Test
	void updateUser_disablingAnotherAccount_isApplied() {
		// The string form is what existing clients send; it must end up as the boolean the core API takes.
		userBehindEntry(TARGET, CALLER);

		service.updateUser(entry, body("{\"disabled\":\"true\"}"), SESSION);

		verify(user).setDisabled(true);
	}

	@Test
	void updateUser_customProperties_areAppliedAsAStringMap() {
		userBehindEntry(TARGET, CALLER);

		service.updateUser(entry, body("{\"customProperties\":{\"k\":\"v\"}}"), SESSION);

		verify(user).setCustomProperties(Map.of("k", "v"));
	}

	/**
	 * Wires {@code entry} to {@code user} at {@code userUri} and makes {@code callerUri} the authenticated
	 * caller. The URI stubs are lenient because only the password and disabled branches consult them.
	 */
	private void userBehindEntry(URI userUri, URI callerUri) {
		when(entry.getResource()).thenReturn(user);
		lenient().when(user.getURI()).thenReturn(userUri);
		lenient().when(entry.getResourceURI()).thenReturn(userUri);
		lenient().when(principalManager.getAuthenticatedUserURI()).thenReturn(callerUri);
	}

	/**
	 * Makes the real {@code BasicVerifier} lookup find a stored hash of {@code password} for the user: it runs
	 * as admin (so the admin user must resolve) and reads the principal entry by the user's name.
	 */
	private void storedPasswordIs(String password) {
		when(user.getName()).thenReturn("alice");
		User admin = mock(User.class);
		when(admin.getURI()).thenReturn(ADMIN);
		when(principalManager.getAdminUser()).thenReturn(admin);
		User stored = mock(User.class);
		when(stored.getSaltedHashedSecret()).thenReturn(Password.getSaltedHash(password));
		Entry principalEntry = entryWithResource(stored);
		when(principalEntry.getGraphType()).thenReturn(GraphType.User);
		when(principalManager.getPrincipalEntry("alice")).thenReturn(principalEntry);
	}

	private static Entry entryWithResource(Resource resource) {
		Entry resolved = mock(Entry.class);
		when(resolved.getResource()).thenReturn(resource);
		return resolved;
	}

	private static byte[] body(String json) {
		return json.getBytes(StandardCharsets.UTF_8);
	}
}
