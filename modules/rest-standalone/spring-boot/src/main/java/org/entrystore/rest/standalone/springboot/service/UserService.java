package org.entrystore.rest.standalone.springboot.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.entrystore.Context;
import org.entrystore.PrincipalManager;
import org.entrystore.User;
import org.entrystore.rest.standalone.springboot.model.api.GetAuthUserResponse;
import org.entrystore.rest.standalone.springboot.model.exception.EntityNotFoundException;
import org.springframework.stereotype.Service;

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

	public GetAuthUserResponse getUserInfo(String locales) {

		User user = principalManager.getUser(principalManager.getAuthenticatedUserURI());

		if (user == null) {
			throw new EntityNotFoundException("The logged-in user " + principalManager.getAuthenticatedUserURI() + " does not exist anymore");
		}

		Map<String, Double> clientAcceptedLanguages = parseLocalesHeader(locales);

		String homeContext = null;
		String authTokenExpires = null;
		if (!user.getURI().equals(principalManager.getGuestUser().getURI())) {
			Context context = user.getHomeContext();
			if (context != null) {
				homeContext = context.getEntry().getId();
			}
		}

		return new GetAuthUserResponse(user.getEntry().getId(), homeContext, user.getName(),
				user.getEntry().getEntryURI().toString(), user.getLanguage(), clientAcceptedLanguages,
				user.getExternalID(), authTokenExpires);
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
