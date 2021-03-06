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

import java.util.Date;
import java.util.Map;

/**
 * @author Hannes Ebner
 */
public class LoginTokenCache extends TokenCache<String, UserInfo> {

	private static LoginTokenCache instance;

	private LoginTokenCache() {}

	public synchronized static LoginTokenCache getInstance() {
		if (instance == null) {
			instance = new LoginTokenCache();
		}
		return instance;
	}

	public void cleanup() {
		synchronized (tokenCache) {
			tokenCache.entrySet().removeIf(userInfo -> userInfo.getValue().getLoginExpiration().before(new Date()));
		}
	}

	public void removeTokens(String userName) {
		if (userName == null) {
			throw new IllegalArgumentException("Username must not be null");
		}
		synchronized (tokenCache) {
			tokenCache.entrySet().removeIf(userInfo -> userName.equals(userInfo.getValue().getUserName()));
		}
	}

	public void renameUser(String oldUserName, String newUserName) {
		if (oldUserName == null || newUserName == null) {
			throw new IllegalArgumentException("Usernames must not be null");
		}
		synchronized (tokenCache) {
			for (Map.Entry<String, UserInfo> e : tokenCache.entrySet()) {
				UserInfo ui = e.getValue();
				if (oldUserName.equals(ui.userName)) {
					ui.setUserName(newUserName);
					tokenCache.put(e.getKey(), ui);
				}
			}
		}
	}

}