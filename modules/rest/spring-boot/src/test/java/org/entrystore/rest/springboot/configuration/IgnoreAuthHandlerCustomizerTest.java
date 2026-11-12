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

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.eclipse.jetty.ee11.servlet.ServletContextHandler;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.Callback;
import org.entrystore.rest.springboot.configuration.IgnoreAuthHandlerCustomizer.CredentiallessRequest;
import org.entrystore.rest.springboot.configuration.IgnoreAuthHandlerCustomizer.IgnoreAuthHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.jetty.JettyServerCustomizer;
import org.springframework.boot.jetty.servlet.JettyServletWebServerFactory;

import java.io.IOException;
import java.util.Arrays;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class IgnoreAuthHandlerCustomizerTest {

	private static final String BASIC = "Authorization: Basic dXNlcjpwYXNzd29yZA==";

	private Server server;
	private LocalConnector connector;

	@AfterEach
	void stopServer() throws Exception {
		// withoutCookie_keepsTheOtherCookies starts no server
		if (server != null) {
			server.stop();
		}
	}

	@Test
	void customize_wrapsTheServerHandler() {
		JettyServletWebServerFactory factory = mock(JettyServletWebServerFactory.class);
		new IgnoreAuthHandlerCustomizer("auth_token", true, "/store").customize(factory);
		var customizer = ArgumentCaptor.forClass(JettyServerCustomizer.class);
		verify(factory).addServerCustomizers(customizer.capture());
		server = new Server();
		Handler context = new ServletContextHandler("/store", ServletContextHandler.NO_SESSIONS);
		server.setHandler(context);

		customizer.getValue().customize(server);

		var wrapper = assertInstanceOf(IgnoreAuthHandler.class, server.getHandler());
		assertSame(context, wrapper.getHandler());
	}

	@ParameterizedTest(name = "{0}")
	@CsvSource(delimiter = '|', textBlock = """
			ignoreAuth on a GET        | GET  | /store/x?ignoreAuth     | other=1; auth_token=SESSION            | other
			ignoreAuth on a POST       | POST | /store/x?ignoreAuth     | other=1; auth_token=SESSION            | other
			JSONP callback             | GET  | /store/x?callback=cb    | auth_token=SESSION; other=1            | other
			bare JSONP callback        | GET  | /store/x?callback       | auth_token=SESSION                     | ''
			malformed ignoreAuth value | GET  | /store/x?ignoreAuth=%zz | auth_token=SESSION                     | ''
			malformed JSONP callback   | GET  | /store/x?callback=%zz   | auth_token=SESSION                     | ''
			cookie name in upper case  | GET  | /store/x?ignoreAuth     | AUTH_TOKEN=SESSION; other=1            | other
			session cookie twice       | GET  | /store/x?ignoreAuth     | auth_token=SESSION; auth_token=SESSION | ''
			""")
	void guestServedRequest_reachesTheContextWithoutSessionAndCredentials(String description, String method,
			String uri, String cookieHeader, String expectedCookies) throws Exception {
		startServer(true);
		String session = logIn(1800);

		HttpTester.Response response = send(method, uri, "Cookie: " + cookieHeader.replace("SESSION", session), BASIC);

		assertEquals("session=false\nauthorization=null\ncookies=" + expectedCookies, response.getContent());
		assertNull(response.getField(HttpHeader.SET_COOKIE));
	}

	@ParameterizedTest(name = "{0}")
	@CsvSource(delimiter = '|', textBlock = """
			no parameter                | GET  | /store/x
			JSONP callback on a POST    | POST | /store/x?callback=cb
			logout with ignoreAuth      | POST | /store/auth/logout?ignoreAuth
			longer parameter name       | GET  | /store/x?ignoreAuthX
			""")
	void otherRequest_keepsSessionAndCredentials(String description, String method, String uri) throws Exception {
		startServer(true);
		String session = logIn(1800);

		HttpTester.Response response = send(method, uri, "Cookie: auth_token=" + session + "; other=1", BASIC);

		assertEquals("session=true\nauthorization=Basic dXNlcjpwYXNzd29yZA==\ncookies=auth_token,other",
				response.getContent());
	}

	@Test
	void jsonpCallbackWithJsonpOff_keepsSessionAndCredentials() throws Exception {
		startServer(false);
		String session = logIn(1800);

		HttpTester.Response response = send("GET", "/store/x?callback=cb", "Cookie: auth_token=" + session, BASIC);

		assertEquals("session=true\nauthorization=Basic dXNlcjpwYXNzd29yZA==\ncookies=auth_token",
				response.getContent());
	}

	@Test
	void logoutUnderAContextPathWithTrailingSlash_keepsSessionAndCredentials() throws Exception {
		startServer(new IgnoreAuthHandlerCustomizer("auth_token", true, "/store/"));
		String session = logIn(1800);

		HttpTester.Response response = send("POST", "/store/auth/logout?ignoreAuth", "Cookie: auth_token=" + session,
				BASIC);

		assertEquals("session=true\nauthorization=Basic dXNlcjpwYXNzd29yZA==\ncookies=auth_token",
				response.getContent());
	}

	@Test
	void guestServedRequestWithTheSessionCookieInItsOwnField_reachesTheContextWithoutIt() throws Exception {
		startServer(true);
		String session = logIn(1800);

		HttpTester.Response response = send("GET", "/store/x?ignoreAuth", "Cookie: other=1",
				"Cookie: auth_token=" + session, BASIC);

		assertEquals("session=false\nauthorization=null\ncookies=other", response.getContent());
		assertNull(response.getField(HttpHeader.SET_COOKIE));
	}

	@Test
	void guestServedRequests_doNotKeepAnIdleSessionAlive() throws Exception {
		startServer(true);
		String session = logIn(1);

		for (int i = 0; i < 4; i++) {
			Thread.sleep(400);
			send("GET", "/store/x?callback=cb", "Cookie: auth_token=" + session);
		}
		HttpTester.Response response = send("GET", "/store/x", "Cookie: auth_token=" + session);

		assertTrue(response.getContent().startsWith("session=false"), response.getContent());
	}

	@Test
	void otherRequests_keepAnIdleSessionAlive() throws Exception {
		startServer(true);
		String session = logIn(1);

		for (int i = 0; i < 4; i++) {
			Thread.sleep(400);
			send("GET", "/store/x", "Cookie: auth_token=" + session);
		}
		HttpTester.Response response = send("GET", "/store/x", "Cookie: auth_token=" + session);

		assertTrue(response.getContent().startsWith("session=true"), response.getContent());
	}

	@Test
	void cookiesParsedBeforeTheHandler_areNotUsed() throws Exception {
		startServer(true, handler -> new Handler.Wrapper(handler) {
			@Override
			public boolean handle(Request request, Response response, Callback callback) throws Exception {
				Request.getCookies(request);
				return super.handle(request, response, callback);
			}
		});
		String session = logIn(1800);

		HttpTester.Response response = send("GET", "/store/x?ignoreAuth", "Cookie: auth_token=" + session);

		assertTrue(response.getContent().startsWith("session=false"), response.getContent());
	}

	@Test
	void withoutCookie_keepsTheOtherCookies() {
		assertEquals("a=1; b=2", CredentiallessRequest.withoutCookie("a=1; auth_token=x.node0; b=2", "auth_token"));
		assertEquals("a=1", CredentiallessRequest.withoutCookie(" AUTH_TOKEN=x ;a=1;", "auth_token"));
		assertEquals("", CredentiallessRequest.withoutCookie("auth_token=x", "auth_token"));
		assertEquals("b=2", CredentiallessRequest.withoutCookie("a=1, auth_token=x; b=2", "auth_token"));
	}

	private void startServer(boolean jsonpEnabled) throws Exception {
		startServer(jsonpEnabled, Function.identity());
	}

	private void startServer(boolean jsonpEnabled, Function<Handler, Handler> outer) throws Exception {
		server = new Server();
		connector = new LocalConnector(server);
		server.addConnector(connector);
		var handler = new IgnoreAuthHandler(probeContext(), "auth_token", jsonpEnabled, "/store/auth/logout");
		server.setHandler(outer.apply(handler));
		server.start();
	}

	/** Starts the server with the handler that the customizer installs, as Spring Boot applies it. */
	private void startServer(IgnoreAuthHandlerCustomizer customizer) throws Exception {
		JettyServletWebServerFactory factory = mock(JettyServletWebServerFactory.class);
		customizer.customize(factory);
		var serverCustomizer = ArgumentCaptor.forClass(JettyServerCustomizer.class);
		verify(factory).addServerCustomizers(serverCustomizer.capture());
		server = new Server();
		connector = new LocalConnector(server);
		server.addConnector(connector);
		server.setHandler(probeContext());
		serverCustomizer.getValue().customize(server);
		server.start();
	}

	/** @return the context at {@code /store} with sessions on the auth_token cookie and the probe servlet */
	private static ServletContextHandler probeContext() {
		var context = new ServletContextHandler("/store", ServletContextHandler.SESSIONS);
		context.getSessionHandler().setSessionCookie("auth_token");
		context.addServlet(new ProbeServlet(), "/*");
		return context;
	}

	/** @return the value of the session cookie of a new session with the given idle timeout */
	private String logIn(int maxInactiveSeconds) throws Exception {
		HttpTester.Response response = send("GET", "/store/login?maxInactive=" + maxInactiveSeconds);
		String setCookie = response.get(HttpHeader.SET_COOKIE);
		return setCookie.substring("auth_token=".length()).split(";")[0];
	}

	private HttpTester.Response send(String method, String uri, String... headers) throws Exception {
		var raw = new StringBuilder(method + " " + uri + " HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n");
		for (String header : headers) {
			raw.append(header).append("\r\n");
		}
		if (!"GET".equals(method)) {
			raw.append("Content-Length: 0\r\n");
		}
		return HttpTester.parseResponse(connector.getResponse(raw.append("\r\n").toString()));
	}

	/** Starts a session at {@code /login}; anywhere else reports what of the session and credentials it sees. */
	private static class ProbeServlet extends HttpServlet {

		@Override
		protected void service(HttpServletRequest request, HttpServletResponse response) throws IOException {
			if ("/login".equals(request.getPathInfo())) {
				HttpSession session = request.getSession(true);
				session.setMaxInactiveInterval(Integer.parseInt(request.getParameter("maxInactive")));
				return;
			}
			Cookie[] cookies = request.getCookies();
			String names = cookies == null ? "" : Arrays.stream(cookies).map(Cookie::getName).sorted()
					.collect(Collectors.joining(","));
			response.getWriter().write("session=" + (request.getSession(false) != null)
					+ "\nauthorization=" + request.getHeader("Authorization")
					+ "\ncookies=" + names);
		}
	}
}
