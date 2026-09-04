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
import lombok.extern.slf4j.Slf4j;
import org.entrystore.Context;
import org.entrystore.ContextManager;
import org.entrystore.Entry;
import org.entrystore.PrincipalManager;
import org.entrystore.User;
import org.entrystore.repository.security.Password;
import org.entrystore.rest.springboot.model.api.GetAuthUserResponse;
import org.entrystore.rest.springboot.model.api.UserSettingsRequestBody;
import org.entrystore.rest.springboot.model.exception.BadRequestException;
import org.entrystore.rest.springboot.model.exception.EntityNotFoundException;
import org.entrystore.rest.springboot.model.exception.ForbiddenException;
import org.entrystore.rest.springboot.service.auth.BasicVerifier;
import org.entrystore.rest.springboot.util.EmailSender;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserService {

	private final PrincipalManager principalManager;
	private final ContextManager contextManager;
	private final AuthService authService;
	private final EmailSender emailSender;
	private final ObjectMapper objectMapper;

	@Value("${entrystore.auth.password.require-current-password:true}")
	@Setter(AccessLevel.PACKAGE)
	private boolean requireCurrentPassword;

	public boolean isAdmin(User user) {
		return principalManager.getAdminUser().getURI().equals(user.getURI()) ||
				principalManager.getAdminGroup().isMember(user);
	}

	public GetAuthUserResponse getUserInfo(String locales, int maxAge) {

		User authenticatedUser = principalManager.getUser(principalManager.getAuthenticatedUserURI());

		if (authenticatedUser == null) {
			throw new EntityNotFoundException("The logged-in user " + principalManager.getAuthenticatedUserURI() + " does not exist anymore");
		}

		Map<String, Double> clientAcceptedLanguages = parseLocalesHeader(locales);

		String homeContext = null;
		Instant authTokenExpires = null;
		if (!authenticatedUser.getURI().equals(principalManager.getGuestUser().getURI())) {
			Context context = authenticatedUser.getHomeContext();
			if (context != null) {
				homeContext = context.getEntry().getId();
			}

			if (maxAge > 0) {
				authTokenExpires = Instant.now().plusSeconds(maxAge);
			}
		}

		GetAuthUserResponse.GetAuthUserResponseBuilder response = GetAuthUserResponse.builder()
				.id(authenticatedUser.getEntry().getId())
				.homeContext(homeContext)
				.user(authenticatedUser.getName())
				.uri(authenticatedUser.getEntry().getEntryURI().toString())
				.language(authenticatedUser.getLanguage())
				.clientAcceptLanguage(clientAcceptedLanguages)
				.externalId(authenticatedUser.getExternalID());

		if (authTokenExpires != null) {
			response.authTokenExpires(LocalDateTime.ofInstant(authTokenExpires, ZoneId.systemDefault()));
		}

		return response.build();
	}

	/**
	 * Applies a partial settings update (JSON body, see {@link UserSettingsRequestBody}) to the user behind the
	 * given entry. A password change requires the current password unless an admin changes another user's password
	 * (configurable via {@code entrystore.auth.password.require-current-password}), expires the user's other
	 * sessions ({@code currentSessionId} is spared when users change their own password) and sends a confirmation
	 * email.
	 */
	public void updateSettings(Entry entry, byte[] requestBody, String currentSessionId) {
		UserSettingsRequestBody settings;
		try {
			settings = objectMapper.readValue(requestBody, UserSettingsRequestBody.class);
		} catch (JacksonException e) {
			// Cause preserved for the log; the parser's own message is not echoed to the client.
			throw new BadRequestException(UserSettingsRequestBody.SYNTAX_ERROR_MESSAGE, e);
		}
		if (settings == null) {
			// The JSON literal `null` deserializes to a null reference instead of throwing, so without
			// this the next dereference answers 500 for a four-byte body any authenticated caller can
			// send.
			throw new BadRequestException(UserSettingsRequestBody.SYNTAX_ERROR_MESSAGE);
		}

		User resourceUser = (User) entry.getResource();
		if (settings.hasName()) {
			String newName = settings.nameValue();
			if (!resourceUser.setName(newName)) {
				throw new BadRequestException("Name is already in use: " + newName);
			}
		}
		if (settings.hasPassword()) {
			String newPassword = settings.passwordValue();

			if (requireCurrentPassword) {
				// we require the current password if:
				// (1) the user is a non-admin user, or
				// (2) the user is an admin user and wants to set his own password
				if (!principalManager.currentUserIsAdminOrAdminGroup()
						|| principalManager.getAuthenticatedUserURI().equals(resourceUser.getURI())) {
					if (!settings.hasCurrentPassword()) {
						throw new ForbiddenException("Current password is required");
					}
					String currentPassword = settings.currentPasswordValue();
					String saltedHashedSecret = BasicVerifier.getSaltedHashedSecret(principalManager,
							resourceUser.getName());
					if (saltedHashedSecret == null || !Password.check(currentPassword, saltedHashedSecret)) {
						throw new ForbiddenException("No password set or incorrect current password provided");
					}
				}
			}

			if (resourceUser.setSecret(newPassword)) {
				// we need to expire sessions of the user, whose password is being changed

				// if it is an admin/admingroup member, who is changing the password of another user, we expire all sessions of that user
				// if it is an admin/admingroup member changing his own password, or user changing his own password,
				// we expire all sessions of that admin/user except the session, through which it is being changed (currentSessionId)

				// the test only asks if the authenticatedUser is the same as the user, whose password is to be changed
				// because no user can change password of another user, only admin
				boolean expireAllSessions = !principalManager.getAuthenticatedUserURI().equals(resourceUser.getURI());
				authService.expireUserSessions(resourceUser, expireAllSessions ? null : currentSessionId);

				emailSender.sendPasswordChangeConfirmation(entry);
			} else {
				throw new BadRequestException("Password must conform to configured rules.");
			}
		}
		if (settings.hasLanguage()) {
			String prefLang = settings.languageValue();
			if (prefLang.isEmpty()) {
				resourceUser.setLanguage(null);
			} else if (!resourceUser.setLanguage(prefLang)) {
				throw new BadRequestException("Preferred language could not be set.");
			}
		}
		if (settings.hasHomeContext()) {
			String homeContext = settings.homeContextValue();
			Entry entryHomeContext = contextManager.get(homeContext);
			if (entryHomeContext != null) {
				if (!(entryHomeContext.getResource() instanceof Context)
						|| !resourceUser.setHomeContext((Context) entryHomeContext.getResource())) {

					throw new BadRequestException("Given homecontext is not a context.");
				}
			}
		}
		if (settings.hasDisabled()) {
			if (entry.getResourceURI().equals(principalManager.getAuthenticatedUserURI())) {
				throw new BadRequestException("Users cannot set their own disabled status.");
			}
			resourceUser.setDisabled(settings.disabledValue());
		}
		if (settings.hasCustomProperties()) {
			resourceUser.setCustomProperties(settings.customPropertiesValue());
		}
	}

	private static Map<String, Double> parseLocalesHeader(String value) {

		Map<String, Double> acceptLanguages = new HashMap<>();

		List<Locale.LanguageRange> ranges = Locale.LanguageRange.parse(value);
		for (Locale.LanguageRange range : ranges) {
			String canonicalTag = Locale.forLanguageTag(range.getRange()).toLanguageTag();
			acceptLanguages.put(canonicalTag, range.getWeight());
		}
		return acceptLanguages;
	}

}
