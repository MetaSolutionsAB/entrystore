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

package org.entrystore.rest.springboot.configuration;

import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;
import org.entrystore.rest.springboot.filter.IgnoreAuthFilter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GuestRequestCredentialsHandlerTest {

	private final AtomicReference<Request> passedOn = new AtomicReference<>();

	@ParameterizedTest(name = "{0}")
	@CsvSource(nullValues = "none", textBlock = """
			session cookie between others,          a=1; auth_token=abc.node0; b=2,   a=1; b=2
			session cookie first,                   auth_token=abc.node0; b=2,        b=2
			session cookie alone,                   auth_token=abc.node0,             none
			session cookie in another case,         AUTH_TOKEN=abc.node0; b=2,        b=2
			session cookie twice,                   auth_token=abc; b=2; auth_token=d, b=2
			a cookie whose name only starts alike,  auth_token_old=1; auth_token=abc, auth_token_old=1
			no session cookie,                      a=1; b=2,                         a=1; b=2
			""")
	void withoutCredentials_removesTheSessionCookieOnly(String description, String cookieHeader, String expected) {
		HttpFields headers = HttpFields.build().add(HttpHeader.COOKIE, cookieHeader).add(HttpHeader.ACCEPT, "*/*");

		HttpFields remaining = GuestRequestCredentialsHandler.withoutCredentials(headers, "auth_token");

		assertEquals(expected, remaining.get(HttpHeader.COOKIE));
		assertEquals("*/*", remaining.get(HttpHeader.ACCEPT));
	}

	@Test
	void withoutCredentials_severalCookieFields_removesTheSessionCookieFromEach() {
		HttpFields headers = HttpFields.build()
				.add(HttpHeader.COOKIE, "auth_token=abc.node0")
				.add(HttpHeader.COOKIE, "a=1; auth_token=def.node0")
				.add(HttpHeader.COOKIE, "b=2");

		HttpFields remaining = GuestRequestCredentialsHandler.withoutCredentials(headers, "auth_token");

		assertEquals(List.of("a=1", "b=2"), remaining.getValuesList(HttpHeader.COOKIE));
	}

	@Test
	void requestServedAsGuest_isPassedOnWithoutSessionCookieAndAuthorization() throws Exception {
		Request request = request("GET", "/store/auth/user?callback=cb", HttpFields.build()
				.add(HttpHeader.COOKIE, "auth_token=abc.node0; XSRF-TOKEN=t")
				.add(HttpHeader.AUTHORIZATION, "Basic YWRtaW46YWRtaW5wYXNz")
				.add(HttpHeader.USER_AGENT, "Browser/1"));

		boolean handled = handler(true).handle(request, mock(Response.class), Callback.NOOP);

		assertTrue(handled);
		HttpFields seen = passedOn.get().getHeaders();
		assertEquals("XSRF-TOKEN=t", seen.get(HttpHeader.COOKIE));
		assertNull(seen.get(HttpHeader.AUTHORIZATION));
		assertEquals("Browser/1", seen.get(HttpHeader.USER_AGENT));
	}

	@Test
	void unsafeIgnoreAuthRequest_isPassedOnWithoutSessionCookie() throws Exception {
		Request request = request("POST", "/store/auth/logout?ignoreAuth", HttpFields.build()
				.add(HttpHeader.COOKIE, "auth_token=abc.node0"));

		handler(true).handle(request, mock(Response.class), Callback.NOOP);

		assertNull(passedOn.get().getHeaders().get(HttpHeader.COOKIE));
	}

	@ParameterizedTest(name = "{0}")
	@CsvSource(textBlock = """
			a request without the parameters,   GET,  /store/auth/user,             true
			other parameters only,              GET,  /store/auth/user?format=json, true
			a callback with JSONP off,          GET,  /store/auth/user?callback=cb, false
			a callback on a POST,               POST, /store/auth/user?callback=cb, true
			""")
	void requestNotServedAsGuest_isPassedOnAsItIs(String description, String method, String uri,
			boolean jsonpEnabled) throws Exception {
		Request request = request(method, uri, HttpFields.build()
				.add(HttpHeader.COOKIE, "auth_token=abc.node0")
				.add(HttpHeader.AUTHORIZATION, "Basic YWRtaW46YWRtaW5wYXNz"));

		handler(jsonpEnabled).handle(request, mock(Response.class), Callback.NOOP);

		assertSame(request, passedOn.get());
	}

	/** The handler in front of one that records the request it is given. */
	private GuestRequestCredentialsHandler handler(boolean jsonpEnabled) {
		var handler = new GuestRequestCredentialsHandler(new IgnoreAuthFilter(jsonpEnabled), "auth_token");
		handler.setHandler(new Handler.Abstract() {
			@Override
			public boolean handle(Request request, Response response, Callback callback) {
				passedOn.set(request);
				return true;
			}
		});
		return handler;
	}

	private static Request request(String method, String uri, HttpFields headers) {
		Request request = mock(Request.class);
		when(request.getMethod()).thenReturn(method);
		when(request.getHttpURI()).thenReturn(HttpURI.from("http://localhost" + uri));
		when(request.getHeaders()).thenReturn(headers);
		return request;
	}
}
