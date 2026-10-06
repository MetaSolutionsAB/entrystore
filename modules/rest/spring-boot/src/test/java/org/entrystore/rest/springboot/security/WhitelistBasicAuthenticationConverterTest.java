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

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.BadCredentialsException;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WhitelistBasicAuthenticationConverterTest {

	private final WhitelistBasicAuthenticationConverter converter =
			new WhitelistBasicAuthenticationConverter(List.of("admin", "user@test.com"));

	@Test
	void whitelistedUsername_isPassedOnForThePasswordCheck() {
		var token = converter.convert(basicRequest("admin", "s3cret"));

		assertEquals("admin", token.getName());
		assertEquals("s3cret", token.getCredentials());
	}

	@Test
	void whitelistMatch_ignoresCase() {
		assertEquals("User@Test.com", converter.convert(basicRequest("User@Test.com", "s3cret")).getName());
	}

	@Test
	void usernameAbsentFromWhitelist_isRejectedAsBadCredentials() {
		assertThrows(BadCredentialsException.class, () -> converter.convert(basicRequest("other", "s3cret")));
	}

	@Test
	void usernameAbsentFromWhitelistWithEmptyPassword_isStillRejectedAsBadCredentials() {
		// Not an IllegalArgumentException from the timing equalisation, which would surface as a 500
		assertThrows(BadCredentialsException.class, () -> converter.convert(basicRequest("other", "")));
	}

	@Test
	void emptyWhitelist_rejectsEveryUsername() {
		var denyAll = new WhitelistBasicAuthenticationConverter(List.of());

		assertThrows(BadCredentialsException.class, () -> denyAll.convert(basicRequest("admin", "s3cret")));
	}

	@Test
	void requestWithoutBasicCredentials_isLeftToTheOtherAuthenticationMechanisms() {
		assertNull(converter.convert(new MockHttpServletRequest("GET", "/auth/user")));
	}

	private static MockHttpServletRequest basicRequest(String username, String password) {
		var request = new MockHttpServletRequest("GET", "/auth/user");
		request.addHeader("Authorization", "Basic " + Base64.getEncoder()
				.encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8)));
		return request;
	}
}
