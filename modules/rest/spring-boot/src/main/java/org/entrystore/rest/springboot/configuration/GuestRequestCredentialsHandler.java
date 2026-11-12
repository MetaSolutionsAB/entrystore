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

import java.util.ArrayList;
import java.util.List;

/**
 * Hides the session cookie and the {@code Authorization} header of a request that {@link IgnoreAuthFilter} serves
 * as guest, before Jetty resolves the session. Such a request must not touch the caller's session: without the
 * cookie Jetty finds no session, so its idle timeout is not renewed, nothing about it is recorded or ended, and
 * the response sets no session cookie. A servlet filter is too late for this, as the session is resolved and its
 * last access renewed before the filter chain runs.
 * <p>
 * The request's other cookies and headers are passed on as they are, and so is every other request.
 */
class GuestRequestCredentialsHandler extends Handler.Wrapper {

	private final IgnoreAuthFilter ignoreAuthFilter;
	private final String sessionCookieName;

	GuestRequestCredentialsHandler(IgnoreAuthFilter ignoreAuthFilter, String sessionCookieName) {
		this.ignoreAuthFilter = ignoreAuthFilter;
		this.sessionCookieName = sessionCookieName;
	}

	@Override
	public boolean handle(Request request, Response response, Callback callback) throws Exception {
		if (!ignoreAuthFilter.isRequested(request.getMethod(), request.getHttpURI().getQuery())) {
			return super.handle(request, response, callback);
		}
		HttpFields headers = withoutCredentials(request.getHeaders(), sessionCookieName);
		return super.handle(new Request.Wrapper(request) {
			@Override
			public HttpFields getHeaders() {
				return headers;
			}
		}, response, callback);
	}

	/**
	 * @return the headers without {@code Authorization} and without the session cookie in any {@code Cookie}
	 * field; a {@code Cookie} field that held only the session cookie is left out
	 */
	static HttpFields withoutCredentials(HttpFields headers, String sessionCookieName) {
		HttpFields.Mutable remaining = HttpFields.build();
		for (HttpField field : headers) {
			if (field.getHeader() == HttpHeader.AUTHORIZATION) {
				continue;
			}
			if (field.getHeader() != HttpHeader.COOKIE) {
				remaining.add(field);
				continue;
			}
			String otherCookies = withoutCookie(field.getValue(), sessionCookieName);
			if (!otherCookies.isEmpty()) {
				remaining.add(HttpHeader.COOKIE, otherCookies);
			}
		}
		return remaining.asImmutable();
	}

	/** @return the pairs of a Cookie header value whose name is not the given one; Jetty compares it ignoring case */
	private static String withoutCookie(String cookieHeaderValue, String cookieName) {
		if (cookieHeaderValue == null) {
			return "";
		}
		List<String> kept = new ArrayList<>();
		for (String pair : cookieHeaderValue.split(";")) {
			String trimmed = pair.trim();
			int eq = trimmed.indexOf('=');
			String name = eq < 0 ? trimmed : trimmed.substring(0, eq).trim();
			if (!trimmed.isEmpty() && !name.equalsIgnoreCase(cookieName)) {
				kept.add(trimmed);
			}
		}
		return String.join("; ", kept);
	}
}
