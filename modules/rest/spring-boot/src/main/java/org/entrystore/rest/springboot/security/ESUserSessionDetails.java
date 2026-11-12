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

package org.entrystore.rest.springboot.security;

import lombok.Getter;
import lombok.Setter;
import org.entrystore.User;
import org.entrystore.rest.springboot.model.auth.SessionInfo;
import org.springframework.security.core.userdetails.UserDetails;

import java.security.SecureRandom;
import java.util.Base64;

@Getter
@Setter
public class ESUserSessionDetails extends org.springframework.security.core.userdetails.User {

	private static final SecureRandom RANDOM = new SecureRandom();
	private static final int SESSION_HANDLE_BYTES = 16;

	private final User esUser;
	// volatile: ReloadUserPropertiesFilter updates it on the registered principal, which /auth/tokens reads concurrently
	private volatile SessionInfo sessionInfo;
	/**
	 * Identifies the registered session to its user in /auth/tokens, in place of the session id. Set when the login
	 * completes; null before that and on HTTP Basic requests, which register no session.
	 */
	private volatile String sessionHandle;

	public ESUserSessionDetails(UserDetails userDt, User esUser, SessionInfo sessionInfo) {

		super(userDt.getUsername(), userDt.getPassword(), userDt.isEnabled(), userDt.isAccountNonExpired(),
				userDt.isCredentialsNonExpired(), userDt.isAccountNonLocked(), userDt.getAuthorities());
		this.esUser = esUser;
		this.sessionInfo = sessionInfo;
	}

	/**
	 * @return a new session handle: 128 random bits, base64url without padding (22 characters), unrelated to the
	 * session id
	 */
	public static String newSessionHandle() {
		byte[] bytes = new byte[SESSION_HANDLE_BYTES];
		RANDOM.nextBytes(bytes);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}
}
