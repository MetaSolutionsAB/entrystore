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

import org.entrystore.rest.springboot.util.ErrorResponseWriter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import tools.jackson.databind.json.JsonMapper;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;

class IgnoreAuthFilterTest {

	@AfterEach
	void clearContext() {
		SecurityContextHolder.clearContext();
	}

	@ParameterizedTest(name = "{0}")
	@CsvSource(delimiter = '|', textBlock = """
			ignoreAuth on a GET                | GET  | ignoreAuth  | true  | true
			ignoreAuth on a POST               | POST | ignoreAuth  | true  | true
			ignoreAuth with JSONP disabled     | GET  | ignoreAuth  | false | true
			JSONP callback on a GET            | GET  | callback=cb | true  | true
			JSONP callback with JSONP disabled | GET  | callback=cb | false | false
			JSONP callback on a POST           | POST | callback=cb | true  | false
			neither parameter                  | GET  | format=json | true  | false
			""")
	void servesAsGuest(String description, String method, String queryString, boolean jsonpEnabled, boolean expected) {
		var request = new MockHttpServletRequest(method, "/search");
		request.setQueryString(queryString);

		assertEquals(expected, filter(jsonpEnabled).servesAsGuest(request));
	}

	@Test
	void callbackInTheBodyOnly_isNotServedAsGuest() {
		var request = new MockHttpServletRequest("GET", "/search");
		request.setParameter("callback", "cb");

		assertFalse(filter(true).servesAsGuest(request));
	}

	@Test
	void jsonpRequest_runsTheChainAsGuestAndRestoresTheLogin() throws Exception {
		var login = new UsernamePasswordAuthenticationToken("alice", "",
				AuthorityUtils.createAuthorityList("ROLE_USER"));
		SecurityContextHolder.getContext().setAuthentication(login);
		var request = new MockHttpServletRequest("GET", "/search");
		request.setQueryString("callback=cb");
		var inChain = new AtomicReference<Authentication>();

		filter(true).doFilter(request, new MockHttpServletResponse(),
				(_, _) -> inChain.set(SecurityContextHolder.getContext().getAuthentication()));

		assertInstanceOf(AnonymousAuthenticationToken.class, inChain.get());
		assertSame(login, SecurityContextHolder.getContext().getAuthentication());
	}

	private static IgnoreAuthFilter filter(boolean jsonpEnabled) {
		return new IgnoreAuthFilter(
				new JsonpCallbackFilter(jsonpEnabled, new ErrorResponseWriter(JsonMapper.builder().build())));
	}
}
