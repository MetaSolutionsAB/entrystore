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

import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;
import org.entrystore.rest.springboot.filter.IgnoreAuthFilter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.jetty.servlet.JettyServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.StringJoiner;

/**
 * Removes the session cookie and the {@code Authorization} header from a request that {@link IgnoreAuthFilter} serves
 * as guest ({@code ignoreAuth}, JSONP), so that it neither uses nor renews the caller's session nor sends its
 * credentials on. Jetty resolves the session from the cookie and renews it before any servlet filter runs, so only a
 * handler in front of the context can do this. {@code POST /auth/logout} keeps both, so that it still ends the session.
 */
@Component
public class IgnoreAuthHandlerCustomizer implements WebServerFactoryCustomizer<JettyServletWebServerFactory> {

	private final String sessionCookieName;
	private final boolean jsonpEnabled;
	private final String logoutPath;

	public IgnoreAuthHandlerCustomizer(
			@Value("${server.servlet.session.cookie.name:auth_token}") String sessionCookieName,
			@Value("${entrystore.jsonp:true}") boolean jsonpEnabled,
			@Value("${server.servlet.context-path:}") String contextPath) {
		this.sessionCookieName = sessionCookieName;
		this.jsonpEnabled = jsonpEnabled;
		// Spring Boot serves the context path without a trailing slash
		this.logoutPath = StringUtils.trimTrailingCharacter(contextPath, '/') + "/auth/logout";
	}

	@Override
	public void customize(JettyServletWebServerFactory factory) {
		factory.addServerCustomizers(server -> server.setHandler(
				new IgnoreAuthHandler(server.getHandler(), sessionCookieName, jsonpEnabled, logoutPath)));
	}

	/** Hands a request that is served as guest on without its session cookie and {@code Authorization} header. */
	static class IgnoreAuthHandler extends Handler.Wrapper {

		private final String sessionCookieName;
		private final boolean jsonpEnabled;
		private final String logoutPath;

		IgnoreAuthHandler(Handler handler, String sessionCookieName, boolean jsonpEnabled, String logoutPath) {
			super(handler);
			this.sessionCookieName = sessionCookieName;
			this.jsonpEnabled = jsonpEnabled;
			this.logoutPath = logoutPath;
		}

		@Override
		public boolean handle(Request request, Response response, Callback callback) throws Exception {
			return super.handle(servesAsGuest(request) ? new CredentiallessRequest(request, sessionCookieName)
					: request, response, callback);
		}

		private boolean servesAsGuest(Request request) {
			if ("POST".equals(request.getMethod()) && logoutPath.equals(request.getHttpURI().getCanonicalPath())) {
				return false;
			}
			return IgnoreAuthFilter.isRequested(request.getMethod(), request.getHttpURI().getQuery(), jsonpEnabled);
		}
	}

	/**
	 * A request without {@code Authorization} and without the session cookie. The cookies Jetty parses from its
	 * headers are cached on this wrapper, so that neither a list parsed from the original headers is used nor this
	 * one reaches the original request.
	 */
	static final class CredentiallessRequest extends Request.Wrapper {

		private final HttpFields headers;
		private Object cookies;

		CredentiallessRequest(Request request, String sessionCookieName) {
			super(request);
			this.headers = withoutCredentials(request.getHeaders(), sessionCookieName);
		}

		@Override
		public HttpFields getHeaders() {
			return headers;
		}

		@Override
		public Object getAttribute(String name) {
			return Request.COOKIE_ATTRIBUTE.equals(name) ? cookies : super.getAttribute(name);
		}

		@Override
		public Object setAttribute(String name, Object value) {
			if (Request.COOKIE_ATTRIBUTE.equals(name)) {
				Object previous = cookies;
				cookies = value;
				return previous;
			}
			return super.setAttribute(name, value);
		}

		@Override
		public Object removeAttribute(String name) {
			return Request.COOKIE_ATTRIBUTE.equals(name) ? setAttribute(name, null) : super.removeAttribute(name);
		}

		private static HttpFields withoutCredentials(HttpFields headers, String sessionCookieName) {
			HttpFields.Mutable kept = HttpFields.build(headers.size());
			for (HttpField field : headers) {
				if (field.is(HttpHeader.AUTHORIZATION.asString())) {
					continue;
				}
				if (field.is(HttpHeader.COOKIE.asString())) {
					String cookies = withoutCookie(field.getValue(), sessionCookieName);
					if (!cookies.isEmpty()) {
						kept.add(new HttpField(HttpHeader.COOKIE, cookies));
					}
					continue;
				}
				kept.add(field);
			}
			return kept.asImmutable();
		}

		/**
		 * @return the {@code Cookie} header value without each {@code ;}-separated part that names the cookie, matched
		 * ignoring case as Jetty's session lookup does; a part is also split on commas, as Jetty's cookie parsing may,
		 * and is then dropped whole
		 */
		static String withoutCookie(String cookieHeader, String cookieName) {
			StringJoiner kept = new StringJoiner("; ");
			for (String cookie : cookieHeader.split(";")) {
				if (!cookie.isBlank() && !namesCookie(cookie, cookieName)) {
					kept.add(cookie.trim());
				}
			}
			return kept.toString();
		}

		private static boolean namesCookie(String cookie, String cookieName) {
			for (String piece : cookie.split(",")) {
				int eq = piece.indexOf('=');
				if ((eq < 0 ? piece : piece.substring(0, eq)).trim().equalsIgnoreCase(cookieName)) {
					return true;
				}
			}
			return false;
		}
	}
}
