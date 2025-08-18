package org.entrystore.rest.standalone.springboot.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.tomcat.util.http.parser.AcceptLanguage;
import org.entrystore.Context;
import org.entrystore.PrincipalManager;
import org.entrystore.User;
import org.entrystore.rest.standalone.springboot.model.api.GetAuthUserResponse;
import org.entrystore.rest.standalone.springboot.model.auth.UserInfo;
import org.entrystore.rest.standalone.springboot.model.exception.EntityNotFoundException;
import org.entrystore.rest.standalone.springboot.service.auth.LoginTokenCache;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.StringReader;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserService {

	private final PrincipalManager principalManager;
	private final LoginTokenCache loginTokenCache;

	public boolean isAdmin(User user) {
		return principalManager.getAdminUser().getURI().equals(user.getURI()) ||
			principalManager.getAdminGroup().isMember(user);
	}

	public GetAuthUserResponse getUserInfo(String locales, String authToken) {

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

			// TODO: migrate CookieLoginResource
			if (authToken != null) {
				UserInfo ui = loginTokenCache.getTokenValue(authToken);
				if (ui != null && ui.getLoginExpiration() != null) {
					authTokenExpires = ui.getLoginExpiration().toString();
				}
			}
		}

		return new GetAuthUserResponse(user.getEntry().getId(), homeContext, user.getName(), user.getEntry().getEntryURI().toString(), user.getLanguage(), clientAcceptedLanguages, user.getExternalID(), authTokenExpires);
	}

	private Map<String, Double> parseLocalesHeader(String value) {

		Map<String, Double> acceptLanguages = new HashMap<>();
		try {
			List<AcceptLanguage> parsedLanguages = AcceptLanguage.parse(new StringReader(value));
			for (AcceptLanguage language : parsedLanguages) {
				acceptLanguages.put(language.getLocale().toLanguageTag(), language.getQuality());
			}
		} catch (IOException e) {
			log.error("Cannot parse Accept-Language header {}", value);
			return null;
		}

		return acceptLanguages;
	}

}
