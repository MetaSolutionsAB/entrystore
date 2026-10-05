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

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.entrystore.Context;
import org.entrystore.PrincipalManager;
import org.entrystore.User;
import org.entrystore.rest.springboot.model.api.GetAuthUserResponse;
import org.entrystore.rest.springboot.model.exception.EntityNotFoundException;
import org.springframework.stereotype.Service;

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

	public boolean isAdmin(User user) {
		return principalManager.getAdminUser().getURI().equals(user.getURI()) ||
				principalManager.getAdminGroup().isMember(user);
	}

	/**
	 * @param authTokenExpires when the caller's login ends, or null without a session; ignored for the guest
	 */
	public GetAuthUserResponse getUserInfo(String locales, Instant authTokenExpires) {

		User authenticatedUser = principalManager.getUser(principalManager.getAuthenticatedUserURI());

		if (authenticatedUser == null) {
			throw new EntityNotFoundException("The logged-in user " + principalManager.getAuthenticatedUserURI() + " does not exist anymore");
		}

		Map<String, Double> clientAcceptedLanguages = parseLocalesHeader(locales);

		String homeContext = null;
		boolean guest = authenticatedUser.getURI().equals(principalManager.getGuestUser().getURI());
		if (!guest) {
			Context context = authenticatedUser.getHomeContext();
			if (context != null) {
				homeContext = context.getEntry().getId();
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

		if (!guest && authTokenExpires != null) {
			response.authTokenExpires(LocalDateTime.ofInstant(authTokenExpires, ZoneId.systemDefault()));
		}

		return response.build();
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
