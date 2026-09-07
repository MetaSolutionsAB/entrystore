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

import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import org.entrystore.Context;
import org.entrystore.ContextManager;
import org.entrystore.Entry;
import org.entrystore.PrincipalManager;
import org.entrystore.User;
import org.entrystore.repository.security.Password;
import org.entrystore.rest.springboot.model.api.UserSettingsRequestBody;
import org.entrystore.rest.springboot.model.exception.BadRequestException;
import org.entrystore.rest.springboot.model.exception.ForbiddenException;
import org.entrystore.rest.springboot.service.auth.BasicVerifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Applies the settings a client may change on a user resource: name, password, preferred language, home
 * context, the disabled flag and custom properties. The body format and its absent/null/wrong-type rules are
 * those of {@link UserSettingsRequestBody}. The password rules live here rather than in {@link AuthService}
 * because they are what makes a resource PUT differ from a password reset: the caller proves knowledge of the
 * current password unless an admin changes someone else's, and the caller's own session survives the change.
 */
@Service
@RequiredArgsConstructor
public class UserSettingsService {

	private final PrincipalManager principalManager;
	private final ContextManager contextManager;
	private final AuthService authService;
	private final ObjectMapper objectMapper;

	/**
	 * Whether a password change must be accompanied by the current password. Applies to every non-admin caller
	 * and to an admin changing their own password; an admin changing another user's password is exempt.
	 */
	@Value("${entrystore.auth.password.require-current-password:true}")
	@Setter(AccessLevel.PACKAGE)
	private boolean requireCurrentPassword;

	/**
	 * Applies a user-settings body to the user behind {@code entry}. Every member is independent and applied
	 * in order; a rejected member aborts with the client-visible message existing clients assert on, leaving
	 * the members already applied in place. {@code currentSessionId} is the caller's own session, the only one
	 * spared when a user changes their own password.
	 */
	public void updateUser(Entry entry, byte[] requestBody, String currentSessionId) {
		UserSettingsRequestBody settings = parseSettings(requestBody);
		User user = (User) entry.getResource();
		if (settings.hasName()) {
			applyName(user, settings.nameValue());
		}
		if (settings.hasPassword()) {
			applyPassword(user, settings, currentSessionId);
		}
		if (settings.hasLanguage()) {
			applyLanguage(user, settings.languageValue());
		}
		if (settings.hasHomeContext()) {
			applyHomeContext(user, settings.homeContextValue());
		}
		if (settings.hasDisabled()) {
			applyDisabled(entry, user, settings);
		}
		if (settings.hasCustomProperties()) {
			user.setCustomProperties(settings.customPropertiesValue());
		}
	}

	private UserSettingsRequestBody parseSettings(byte[] requestBody) {
		UserSettingsRequestBody settings;
		try {
			settings = objectMapper.readValue(requestBody, UserSettingsRequestBody.class);
		} catch (JacksonException e) {
			// Cause preserved for the log; the parser's own message is not echoed to the client.
			throw new BadRequestException(UserSettingsRequestBody.SYNTAX_ERROR_MESSAGE, e);
		}
		if (settings == null) {
			// The JSON literal `null` deserializes to a null reference instead of throwing; without this guard
			// the next dereference answers 500 for a four-byte body any authenticated caller can send.
			throw new BadRequestException(UserSettingsRequestBody.SYNTAX_ERROR_MESSAGE);
		}
		return settings;
	}

	private static void applyName(User user, String newName) {
		if (!user.setName(newName)) {
			throw new BadRequestException("Name is already in use: " + newName);
		}
	}

	/**
	 * A user changing their own password keeps the session they are doing it from and loses every other one;
	 * an admin changing somebody else's password has no session of that user to spare, so all of them go.
	 */
	private void applyPassword(User user, UserSettingsRequestBody settings, String currentSessionId) {
		String newPassword = settings.passwordValue();
		boolean ownAccount = principalManager.getAuthenticatedUserURI().equals(user.getURI());
		if (requireCurrentPassword && (!principalManager.currentUserIsAdminOrAdminGroup() || ownAccount)) {
			verifyCurrentPassword(user, settings);
		}
		if (!user.setSecret(newPassword)) {
			throw new BadRequestException("Password must conform to configured rules.");
		}
		authService.completePasswordChange(user, ownAccount ? currentSessionId : null);
	}

	private void verifyCurrentPassword(User user, UserSettingsRequestBody settings) {
		if (!settings.hasCurrentPassword()) {
			throw new ForbiddenException("Current password is required");
		}
		String saltedHashedSecret = BasicVerifier.getSaltedHashedSecret(principalManager, user.getName());
		if (saltedHashedSecret == null || !Password.check(settings.currentPasswordValue(), saltedHashedSecret)) {
			throw new ForbiddenException("No password set or incorrect current password provided");
		}
	}

	private static void applyLanguage(User user, String language) {
		if (language.isEmpty()) {
			user.setLanguage(null);
		} else if (!user.setLanguage(language)) {
			throw new BadRequestException("Preferred language could not be set.");
		}
	}

	/** An id that resolves to nothing is ignored, as it always has been; only a resolvable non-context is rejected. */
	private void applyHomeContext(User user, String homeContextId) {
		Entry homeContextEntry = contextManager.get(homeContextId);
		if (homeContextEntry == null) {
			return;
		}
		if (!(homeContextEntry.getResource() instanceof Context homeContext) || !user.setHomeContext(homeContext)) {
			throw new BadRequestException("Given homecontext is not a context.");
		}
	}

	private void applyDisabled(Entry entry, User user, UserSettingsRequestBody settings) {
		if (entry.getResourceURI().equals(principalManager.getAuthenticatedUserURI())) {
			throw new BadRequestException("Users cannot set their own disabled status.");
		}
		user.setDisabled(settings.disabledValue());
	}
}
