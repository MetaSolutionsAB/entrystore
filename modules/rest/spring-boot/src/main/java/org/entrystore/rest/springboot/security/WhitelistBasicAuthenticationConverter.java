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

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.entrystore.repository.security.Password;
import org.entrystore.rest.springboot.util.HttpUtil;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.web.authentication.AuthenticationConverter;
import org.springframework.security.web.authentication.www.BasicAuthenticationConverter;

import java.util.List;

/**
 * Applies the password-login whitelist ({@code entrystore.auth.password=whitelist}) to HTTP Basic, as 5.x did.
 * {@code BasicAuthenticationFilter} parses the credentials before any password check, so a rejection here also
 * precedes the Basic credential cache, and it takes the path of bad credentials: 401 with a Basic challenge.
 */
@Slf4j
class WhitelistBasicAuthenticationConverter implements AuthenticationConverter {

	private final BasicAuthenticationConverter delegate = new BasicAuthenticationConverter();
	private final List<String> whitelist;

	WhitelistBasicAuthenticationConverter(List<String> whitelist) {
		this.whitelist = List.copyOf(whitelist);
	}

	@Override
	public UsernamePasswordAuthenticationToken convert(HttpServletRequest request) {
		UsernamePasswordAuthenticationToken token = delegate.convert(request);
		if (token != null && whitelist.stream().noneMatch(name -> name.equalsIgnoreCase(token.getName()))) {
			log.warn("User {} is not on the password login whitelist", HttpUtil.sanitizeForLog(token.getName()));
			equalizeTiming(String.valueOf(token.getCredentials()));
			throw new BadCredentialsException("Bad credentials");
		}
		return token;
	}

	/** Costs what a password check costs, so timing does not tell which usernames are on the whitelist. */
	private static void equalizeTiming(String password) {
		try {
			Password.getSaltedHash(password);
		} catch (IllegalArgumentException e) {
			// An empty or overlong password is rejected without hashing on the regular path as well
		}
	}
}
