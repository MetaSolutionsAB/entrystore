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
