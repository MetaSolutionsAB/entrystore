/*
 * Copyright (c) 2007-2017 MetaSolutions AB
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

package org.entrystore.rest.auth;

import org.entrystore.config.Config;
import org.entrystore.rest.util.HttpUtil;
import org.restlet.Request;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Map.Entry;
import java.util.stream.Collectors;

import static com.google.common.base.Preconditions.checkArgument;
import static org.entrystore.repository.config.Settings.*;

/**
 * @author Hannes Ebner
 */
public class LoginTokenCache extends TokenCache<String, UserInfo> {

	private static final Logger log = LoggerFactory.getLogger(LoginTokenCache.class);
	private static final int DEFAULT_MAX_AGE_IN_SECONDS = (int) Duration.ofDays(7).toSeconds();

	private final boolean configCookieUpdateExpiry;
	private final int configCookieMaxAgeInSeconds;

	public LoginTokenCache(Config config) {
		this.configCookieUpdateExpiry = config.getBoolean(AUTH_COOKIE_UPDATE_EXPIRY, false);
		this.configCookieMaxAgeInSeconds = config.getInt(AUTH_TOKEN_MAX_AGE, config.getInt(AUTH_COOKIE_MAX_AGE, DEFAULT_MAX_AGE_IN_SECONDS));
	}

	public boolean isConfigCookieUpdateExpiry() {
		return configCookieUpdateExpiry;
	}

	public int getConfigCookieMaxAgeInSeconds() {
		return configCookieMaxAgeInSeconds;
	}

	public void cleanup() {
		synchronized (tokenCache) {
			tokenCache.entrySet().removeIf(userInfo -> userInfo.getValue().getLoginExpiration().isBefore(LocalDateTime.now()));
		}
	}

	public void removeTokens(String userName) {
		checkArgument(userName != null, "userName must not be null");
		synchronized (tokenCache) {
			tokenCache.entrySet().removeIf(userInfo -> userName.equals(userInfo.getValue().getUserName()));
		}
	}

	public void removeToken(String token) {
		checkArgument(token != null, "token must not be null");
		synchronized (tokenCache) {
			if (tokenCache.containsKey(token)) {
				tokenCache.remove(token);
			} else {
				log.warn("Token not found in cache");
			}
		}
	}

	public void removeTokensButOne(String token) {
		if (token == null) {
			log.warn("Token is null, authenticated via HTTP Basic?");
			return;
		}
		synchronized (tokenCache) {
			if (tokenCache.containsKey(token)) {
				String userName = tokenCache.get(token).getUserName();
				tokenCache.entrySet().removeIf(userInfo ->
						(userName.equals(userInfo.getValue().getUserName()) && !token.equals(userInfo.getKey()))
				);
			} else {
				log.warn("Token not found in cache");
			}
		}
	}

	public void renameUser(String oldUserName, String newUserName) {
		if (oldUserName == null || newUserName == null) {
			throw new IllegalArgumentException("Username must not be null");
		}
		synchronized (tokenCache) {
			for (Entry<String, UserInfo> e : tokenCache.entrySet()) {
				UserInfo oldUi = e.getValue();
				if (oldUserName.equals(oldUi.userName)) {
					UserInfo newUi = new UserInfo(newUserName, oldUi.getLoginTime());
					newUi.setLoginExpiration(oldUi.getLoginExpiration());
					newUi.setLastAccessTime(oldUi.getLastAccessTime());
					newUi.setLastUsedIpAddress(oldUi.getLastUsedIpAddress());
					newUi.setLastUsedUserAgent(oldUi.getLastUsedUserAgent());
					tokenCache.put(e.getKey(), newUi);
				}
			}
		}
	}

	public Map<String, UserInfo> getTokens(String userName) {
		checkArgument(userName != null, "Username must not be null");
		synchronized (tokenCache) {
			return tokenCache.entrySet().stream()
					.filter(entry -> userName.equals(entry.getValue().getUserName()))
					.collect(Collectors.toMap(Entry::getKey, Entry::getValue));
		}
	}

	public UserInfo registerUserInteraction(String token, Request request) {
		checkArgument(token != null, "Token must not be null");
		UserInfo userInfo = getTokenValue(token);
		if (userInfo == null) {
			log.debug("Call to registerUserInteraction with non-existent token [{}]", token);
			return null;
		}

		userInfo.setLastUsedUserAgent(request.getClientInfo().getAgent());
		userInfo.setLastAccessTime(LocalDateTime.now());
		userInfo.setLastUsedIpAddress(HttpUtil.getClientIpAddress(request));
		if (configCookieUpdateExpiry) {
			userInfo.setLoginExpiration(userInfo.getLastAccessTime().plusSeconds(configCookieMaxAgeInSeconds));
		}
		return userInfo;
	}

}