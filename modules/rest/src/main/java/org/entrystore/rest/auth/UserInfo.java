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

import java.time.LocalDateTime;

public class UserInfo {

	final String userName;
	final LocalDateTime loginTime;

	LocalDateTime loginExpiration;
	LocalDateTime lastAccessTime;
	String lastUsedIpAddress;
	String lastUsedUserAgent;

	public UserInfo(String userName, LocalDateTime loginTime) {
		this.userName = userName;
		this.loginTime = loginTime;
	}

	public UserInfo(String userName, LocalDateTime loginTime, LocalDateTime loginExpiration, LocalDateTime lastAccessTime,
			String clientIp, String lastUsedUserAgent) {

		this.userName = userName;
		this.loginTime = loginTime;
		this.loginExpiration = loginExpiration;
		this.lastAccessTime = lastAccessTime;
		this.lastUsedIpAddress = clientIp;
		this.lastUsedUserAgent = lastUsedUserAgent;
	}

	public String getUserName() {
		return userName;
	}

	public LocalDateTime getLoginTime() {
		return loginTime;
	}

	public LocalDateTime getLoginExpiration() {
		return loginExpiration;
	}

	public void setLoginExpiration(LocalDateTime loginExpiration) {
		this.loginExpiration = loginExpiration;
	}

	public LocalDateTime getLastAccessTime() {
		return lastAccessTime;
	}

	public void setLastAccessTime(LocalDateTime lastAccessTime) {
		this.lastAccessTime = lastAccessTime;
	}

	public String getLastUsedIpAddress() {
		return lastUsedIpAddress;
	}

	public void setLastUsedIpAddress(String lastUsedIpAddress) {
		this.lastUsedIpAddress = lastUsedIpAddress;
	}

	public String getLastUsedUserAgent() {
		return lastUsedUserAgent;
	}

	public void setLastUsedUserAgent(String lastUsedUserAgent) {
		this.lastUsedUserAgent = lastUsedUserAgent;
	}
}
