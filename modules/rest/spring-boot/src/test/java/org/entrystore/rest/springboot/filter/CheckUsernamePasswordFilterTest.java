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

package org.entrystore.rest.springboot.filter;

import org.entrystore.rest.springboot.configuration.PasswordLoginConfiguration;
import org.entrystore.rest.springboot.configuration.PasswordLoginListProperties;
import org.entrystore.rest.springboot.configuration.PasswordLoginMode;
import org.entrystore.rest.springboot.service.auth.LoginAttemptService;
import org.entrystore.rest.springboot.util.ErrorResponseWriter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.support.PropertySourcesPlaceholderConfigurer;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.when;

/**
 * Pins the whitelist/blacklist gate: the direct-construction tests pin the mode logic, and
 * {@code whitelistMode_bindsFromTheConfiguredKey} pins that {@code entrystore.auth.password} resolves
 * from a real {@code Environment}.
 */
@ExtendWith(MockitoExtension.class)
class CheckUsernamePasswordFilterTest {

	private static final String PASSWORD = "s3cretPassw0rd!";

	@Mock
	private LoginAttemptService loginAttemptService;

	@Test
	void whitelistMode_bindsFromTheConfiguredKey() {
		// A key that stops resolving would leave whitelist enforcement silently off
		new ApplicationContextRunner()
				.withBean(PropertySourcesPlaceholderConfigurer.class)
				.withUserConfiguration(PasswordLoginConfiguration.class)
				.withBean(LoginAttemptService.class, () -> loginAttemptService)
				.withBean(ErrorResponseWriter.class, () -> new ErrorResponseWriter(JsonMapper.builder().build()))
				.withBean(PasswordLoginListProperties.class,
						() -> new PasswordLoginListProperties(Map.of("1", "admin"), Map.of()))
				.withBean(CheckUsernamePasswordFilter.class)
				.withPropertyValues("entrystore.auth.password=whitelist")
				.run(context -> {
					var response = new MockHttpServletResponse();

					context.getBean(CheckUsernamePasswordFilter.class)
							.doFilter(loginRequest("other@test.com"), response, new MockFilterChain());

					assertEquals(HttpStatus.UNAUTHORIZED.value(), response.getStatus(),
							"a non-whitelisted username must be denied when the mode resolves from the key");
				});
	}

	@Test
	void whitelistedUsername_reachesTheFilterChain() throws Exception {
		when(loginAttemptService.isLockedOut("admin")).thenReturn(false);
		var filter = filter(PasswordLoginMode.WHITELIST, Map.of("1", "admin"), Map.of());
		var response = new MockHttpServletResponse();
		var chain = new MockFilterChain();

		filter.doFilter(loginRequest("admin"), response, chain);

		assertNotNull(chain.getRequest(), "a whitelisted username must proceed to authentication");
	}

	@Test
	void usernameAbsentFromWhitelist_getsTheUnified401WithoutReachingTheChain() throws Exception {
		when(loginAttemptService.isLockedOut("other@test.com")).thenReturn(false);
		var filter = filter(PasswordLoginMode.WHITELIST, Map.of("1", "admin"), Map.of());
		var response = new MockHttpServletResponse();
		var chain = new MockFilterChain();

		filter.doFilter(loginRequest("other@test.com"), response, chain);

		assertNull(chain.getRequest(), "a non-whitelisted username must never reach authentication");
		assertEquals(HttpStatus.UNAUTHORIZED.value(), response.getStatus());
	}

	@Test
	void blacklistedUsername_getsTheUnified401WithoutReachingTheChain() throws Exception {
		when(loginAttemptService.isLockedOut("blocked@test.com")).thenReturn(false);
		var filter = filter(PasswordLoginMode.ON, Map.of(), Map.of("1", "blocked@test.com"));
		var response = new MockHttpServletResponse();
		var chain = new MockFilterChain();

		filter.doFilter(loginRequest("blocked@test.com"), response, chain);

		assertNull(chain.getRequest(), "a blacklisted username must never reach authentication");
		assertEquals(HttpStatus.UNAUTHORIZED.value(), response.getStatus());
	}

	@Test
	void whitelistModeWithEmptyWhitelist_constructsAndDeniesEveryLogin() throws Exception {
		// Fails closed rather than failing startup: this shape is both the misspelt-key mistake and
		// the only way this layer can express "no local password logins at all", so the constructor
		// logs an ERROR and keeps the deny-all behaviour instead of aborting the boot.
		when(loginAttemptService.isLockedOut("admin")).thenReturn(false);
		var filter = assertDoesNotThrow(() -> filter(PasswordLoginMode.WHITELIST, Map.of(), Map.of()));
		var response = new MockHttpServletResponse();
		var chain = new MockFilterChain();

		filter.doFilter(loginRequest("admin"), response, chain);

		assertNull(chain.getRequest());
		assertEquals(HttpStatus.UNAUTHORIZED.value(), response.getStatus());
	}

	private CheckUsernamePasswordFilter filter(PasswordLoginMode passwordLoginMode, Map<String, String> whitelist,
			Map<String, String> blacklist) {
		// A real writer, not a stub: the 401 assertions read the status it writes.
		return new CheckUsernamePasswordFilter(loginAttemptService,
				new ErrorResponseWriter(JsonMapper.builder().build()), passwordLoginMode,
				new PasswordLoginListProperties(whitelist, blacklist));
	}

	private static MockHttpServletRequest loginRequest(String username) {
		var request = new MockHttpServletRequest("POST", "/auth/cookie");
		request.setParameter("auth_username", username);
		request.setParameter("auth_password", PASSWORD);
		return request;
	}
}
