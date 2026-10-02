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

import jakarta.servlet.http.Cookie;
import org.entrystore.repository.RepositoryManager;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockServletContext;

import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AuthTokenCookiesTest {

	private static final String EPOCH = "Expires=Thu, 1 Jan 1970 00:00:00 GMT";

	@Test
	void resolveIssuingPath_auto_usesRepositoryUrlPath() {
		assertEquals("/store/", AuthTokenCookies.resolveIssuingPath("auto", url("https://example.org/store/")));
	}

	@Test
	void resolveIssuingPath_unset_usesRepositoryUrlPath() {
		assertEquals("/store/", AuthTokenCookies.resolveIssuingPath("", url("https://example.org/store/")));
	}

	@Test
	void resolveIssuingPath_repositoryUrlWithoutPath_isRoot() {
		assertEquals("/", AuthTokenCookies.resolveIssuingPath("auto", url("https://example.org")));
	}

	@Test
	void resolveIssuingPath_configuredPath_isKept() {
		assertEquals("/api/", AuthTokenCookies.resolveIssuingPath(" /api/ ", url("https://example.org/store/")));
	}

	@Test
	void expiryPaths_baseUrlPathMatchingContextPath_coverBothSlashVariants() {
		assertEquals(List.of("/store", "/store/"), AuthTokenCookies.expiryPaths("/store/", "/store"));
	}

	@Test
	void expiryPaths_proxyPrefixDifferingFromContextPath_coverBothPaths() {
		assertEquals(List.of("/api", "/api/", "/store", "/store/"), AuthTokenCookies.expiryPaths("/api/", "/store"));
	}

	@Test
	void expiryPaths_rootContext_isRootOnly() {
		assertEquals(List.of("/"), AuthTokenCookies.expiryPaths("/", ""));
	}

	@Test
	void expireAll_sameSiteNone_expiresEveryPathWithSecure() {
		var cookies = authTokenCookies("none", false, "");
		var response = new MockHttpServletResponse();

		cookies.expireAll(requestWithAuthToken(), response);

		assertEquals(List.of(
				"auth_token=; Path=/store; Max-Age=0; " + EPOCH + "; Secure; SameSite=None",
				"auth_token=; Path=/store/; Max-Age=0; " + EPOCH + "; Secure; SameSite=None"),
				response.getHeaders("Set-Cookie"));
	}

	@Test
	void expireAll_configuredDomain_expiresDomainAndHostOnlyCookies() {
		var cookies = authTokenCookies("strict", true, "example.org");
		var response = new MockHttpServletResponse();

		cookies.expireAll(requestWithAuthToken(), response);

		assertEquals(List.of(
				"auth_token=; Path=/store; Domain=example.org; Max-Age=0; " + EPOCH + "; HttpOnly; SameSite=Strict",
				"auth_token=; Path=/store; Max-Age=0; " + EPOCH + "; HttpOnly; SameSite=Strict",
				"auth_token=; Path=/store/; Domain=example.org; Max-Age=0; " + EPOCH + "; HttpOnly; SameSite=Strict",
				"auth_token=; Path=/store/; Max-Age=0; " + EPOCH + "; HttpOnly; SameSite=Strict"),
				response.getHeaders("Set-Cookie"));
	}

	@Test
	void expireStale_configuredDomain_expiresHostOnlyCookieOnIssuingPath() {
		var cookies = authTokenCookies("strict", true, "example.org");
		var response = new MockHttpServletResponse();

		cookies.expireStale(requestWithAuthToken(), response);

		assertEquals(List.of(
				"auth_token=; Path=/store; Domain=example.org; Max-Age=0; " + EPOCH + "; HttpOnly; SameSite=Strict",
				"auth_token=; Path=/store; Max-Age=0; " + EPOCH + "; HttpOnly; SameSite=Strict",
				"auth_token=; Path=/store/; Max-Age=0; " + EPOCH + "; HttpOnly; SameSite=Strict"),
				response.getHeaders("Set-Cookie"));
	}

	@Test
	void expireStale_leavesIssuingPath() {
		var cookies = authTokenCookies("none", false, "");
		var response = new MockHttpServletResponse();

		cookies.expireStale(requestWithAuthToken(), response);

		assertEquals(List.of("auth_token=; Path=/store; Max-Age=0; " + EPOCH + "; Secure; SameSite=None"),
				response.getHeaders("Set-Cookie"));
	}

	@Test
	void expireAll_requestWithoutAuthToken_writesNothing() {
		var cookies = authTokenCookies("none", false, "");
		var response = new MockHttpServletResponse();

		cookies.expireAll(new MockHttpServletRequest(), response);

		assertEquals(List.of(), response.getHeaders("Set-Cookie"));
	}

	@Test
	void expireAll_calledTwiceForOneRequest_writesOnce() {
		var cookies = authTokenCookies("none", false, "");
		var request = requestWithAuthToken();
		var response = new MockHttpServletResponse();

		cookies.expireAll(request, response);
		cookies.expireAll(request, response);

		assertEquals(2, response.getHeaders("Set-Cookie").size());
	}

	@Test
	void expireStaleThenExpireAll_expiresIssuingPathOnce() {
		var cookies = authTokenCookies("none", false, "");
		var request = requestWithAuthToken();
		var response = new MockHttpServletResponse();

		cookies.expireStale(request, response);
		cookies.expireAll(request, response);

		assertEquals(List.of(
				"auth_token=; Path=/store; Max-Age=0; " + EPOCH + "; Secure; SameSite=None",
				"auth_token=; Path=/store/; Max-Age=0; " + EPOCH + "; Secure; SameSite=None"),
				response.getHeaders("Set-Cookie"));
	}

	@Test
	void applySessionLifetime_setsSessionIdleTimeoutToCookieMaxAge() {
		var servletContext = new MockServletContext();
		servletContext.getSessionCookieConfig().setMaxAge(3700);
		var request = new MockHttpServletRequest(servletContext);

		authTokenCookies("strict", true, "").applySessionLifetime(request);

		assertEquals(3700, request.getSession().getMaxInactiveInterval());
	}

	@Test
	void applySessionLifetime_negativeCookieMaxAge_keepsContainerIdleTimeout() {
		var servletContext = new MockServletContext();
		servletContext.getSessionCookieConfig().setMaxAge(-1);
		var request = new MockHttpServletRequest(servletContext);
		request.getSession().setMaxInactiveInterval(1800);

		authTokenCookies("strict", true, "").applySessionLifetime(request);

		assertEquals(1800, request.getSession().getMaxInactiveInterval());
	}

	@Test
	void applySessionLifetime_zeroCookieMaxAge_keepsContainerIdleTimeout() {
		var servletContext = new MockServletContext();
		servletContext.getSessionCookieConfig().setMaxAge(0);
		var request = new MockHttpServletRequest(servletContext);
		request.getSession().setMaxInactiveInterval(1800);

		authTokenCookies("strict", true, "").applySessionLifetime(request);

		assertEquals(1800, request.getSession().getMaxInactiveInterval());
	}

	static AuthTokenCookies authTokenCookies(String sameSite, boolean httpOnly, String domain) {
		var repositoryManager = mock(RepositoryManager.class);
		when(repositoryManager.getRepositoryURL()).thenReturn(url("https://example.org/store/"));
		var environment = new MockEnvironment().withProperty("server.servlet.session.cookie.same-site", sameSite);
		return new AuthTokenCookies(repositoryManager, environment, "auth_token", domain, httpOnly, false,
				"/store", "auto");
	}

	static MockHttpServletRequest requestWithAuthToken() {
		var request = new MockHttpServletRequest();
		request.setCookies(new Cookie("auth_token", "unknown"));
		return request;
	}

	private static URL url(String url) {
		try {
			return URI.create(url).toURL();
		} catch (MalformedURLException e) {
			throw new IllegalArgumentException(e);
		}
	}
}
