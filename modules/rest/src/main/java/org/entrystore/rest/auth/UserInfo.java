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

import java.net.InetAddress;
import java.time.LocalDateTime;

public class UserInfo {

	String userName;
	LocalDateTime loginExpiration;
	LocalDateTime loginTime;
	LocalDateTime lastAccess;
	InetAddress inetAddress;
	String userAgent;

	public UserInfo() {}

	public UserInfo(String userName, LocalDateTime loginExpiration) {
		this.userName = userName;
		this.loginExpiration = loginExpiration;
	}

	public UserInfo(String userName, LocalDateTime loginExpiration, LocalDateTime loginTime, LocalDateTime lastAccess,
			InetAddress inetAddress, String userAgent) {

		this.userName = userName;
		this.loginExpiration = loginExpiration;
		this.loginTime = loginTime;
		this.lastAccess = lastAccess;
		this.inetAddress = inetAddress;
		this.userAgent = userAgent;
	}

	public String getUserName() {
		return userName;
	}

	public void setUserName(String userName) {
		this.userName = userName;
	}

	public LocalDateTime getLoginExpiration() {
		return loginExpiration;
	}

	public void setLoginExpiration(LocalDateTime loginExpiration) {
		this.loginExpiration = loginExpiration;
	}

	public LocalDateTime getLoginTime() {
		return loginTime;
	}

	public void setLoginTime(LocalDateTime loginTime) {
		this.loginTime = loginTime;
	}

	public LocalDateTime getLastAccess() {
		return lastAccess;
	}

	public void setLastAccess(LocalDateTime lastAccess) {
		this.lastAccess = lastAccess;
	}

	public InetAddress getInetAddress() {
		return inetAddress;
	}

	public void setInetAddress(InetAddress inetAddress) {
		this.inetAddress = inetAddress;
	}

	public String getUserAgent() {
		return userAgent;
	}

	public void setUserAgent(String userAgent) {
		this.userAgent = userAgent;
	}
}
