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

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;

class IgnoreAuthFilterTest {

	private final Authentication loggedIn = new UsernamePasswordAuthenticationToken("alice", "",
			List.of(new SimpleGrantedAuthority("ROLE_USER")));

	@AfterEach
	void clearContext() {
		SecurityContextHolder.clearContext();
	}

	@ParameterizedTest(name = "{0}")
	@CsvSource(textBlock = """
			ignoreAuth on a GET,                    GET,  ignoreAuth,              true,  true
			ignoreAuth on a POST,                   POST, ignoreAuth,              true,  true
			ignoreAuth with JSONP off,              GET,  ignoreAuth,              false, true
			JSONP callback on a GET,                GET,  callback=cb,             true,  true
			bare JSONP callback on a GET,           GET,  callback,                true,  true
			JSONP callback after other parameters,  GET,  format=json&callback=cb, true,  true
			JSONP callback with JSONP off,          GET,  callback=cb,             false, false
			JSONP callback on a POST,               POST, callback=cb,             true,  false
			JSONP callback with a malformed value,  GET,  callback=%zz,            true,  false
			other parameters only,                  GET,  format=json,             true,  false
			""")
	void isRequested(String description, String method, String queryString, boolean jsonpEnabled, boolean expected) {
		var request = new MockHttpServletRequest(method, "/auth/user");
		request.setQueryString(queryString);

		assertEquals(expected, new IgnoreAuthFilter(jsonpEnabled).isRequested(request));
	}

	@Test
	void isRequested_requestWithoutQueryString_isNot() {
		assertFalse(new IgnoreAuthFilter(true).isRequested(new MockHttpServletRequest("GET", "/auth/user")));
	}

	@Test
	void jsonpRequest_runsTheChainAsGuestAndRestoresTheLogin() throws Exception {
		SecurityContextHolder.getContext().setAuthentication(loggedIn);
		var request = new MockHttpServletRequest("GET", "/auth/user");
		request.setQueryString("callback=cb");
		var seenByTheChain = new AtomicReference<Authentication>();
		FilterChain chain = (_, _) -> seenByTheChain.set(SecurityContextHolder.getContext().getAuthentication());

		new IgnoreAuthFilter(true).doFilter(request, new MockHttpServletResponse(), chain);

		assertInstanceOf(AnonymousAuthenticationToken.class, seenByTheChain.get());
		assertSame(loggedIn, SecurityContextHolder.getContext().getAuthentication());
	}

	@Test
	void jsonpRequestWithJsonpOff_runsTheChainWithTheLogin() throws Exception {
		SecurityContextHolder.getContext().setAuthentication(loggedIn);
		var request = new MockHttpServletRequest("GET", "/auth/user");
		request.setQueryString("callback=cb");
		var seenByTheChain = new AtomicReference<Authentication>();
		FilterChain chain = (_, _) -> seenByTheChain.set(SecurityContextHolder.getContext().getAuthentication());

		new IgnoreAuthFilter(false).doFilter(request, new MockHttpServletResponse(), chain);

		assertSame(loggedIn, seenByTheChain.get());
	}
}
