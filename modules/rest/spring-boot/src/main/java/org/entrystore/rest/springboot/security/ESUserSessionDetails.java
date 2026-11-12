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

@Getter
@Setter
public class ESUserSessionDetails extends org.springframework.security.core.userdetails.User {

	private final User esUser;
	// volatile: ReloadUserPropertiesFilter updates it on the registered principal, which /auth/tokens reads concurrently
	private volatile SessionInfo sessionInfo;
	/**
	 * The {@link SessionHandle} of the session these details are registered for, set by the login. The details that
	 * a reload replaces them with take it over.
	 */
	private volatile String sessionHandle;

	public ESUserSessionDetails(UserDetails userDt, User esUser, SessionInfo sessionInfo) {

		super(userDt.getUsername(), userDt.getPassword(), userDt.isEnabled(), userDt.isAccountNonExpired(),
				userDt.isCredentialsNonExpired(), userDt.isAccountNonLocked(), userDt.getAuthorities());
		this.esUser = esUser;
		this.sessionInfo = sessionInfo;
	}
}
