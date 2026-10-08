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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
	void expireAfterFailedLogin_requestWithoutCookie_expiresEveryPath() {
		var cookies = authTokenCookies("none", false, "");
		var response = new MockHttpServletResponse();

		cookies.expireAfterFailedLogin(new MockHttpServletRequest(), response);

		assertEquals(List.of(
				"auth_token=; Path=/store; Max-Age=0; " + EPOCH + "; Secure; SameSite=None",
				"auth_token=; Path=/store/; Max-Age=0; " + EPOCH + "; Secure; SameSite=None"),
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
	void cookieMaxAge_refreshExpirationOnAccess_is365Days() {
		assertEquals(365 * 24 * 3600, authTokenCookies("3600", true).cookieMaxAgeSeconds());
	}

	@Test
	void cookieMaxAge_fixedExpiration_isMaxAge() {
		assertEquals(3600, authTokenCookies("3600", false).cookieMaxAgeSeconds());
	}

	@ParameterizedTest(name = "max-age \"{0}\" -> {1} s")
	@CsvSource(delimiter = '|', value = {
			"3600   | 3600",
			"86400s | 86400",
			"1d     | 86400",
			"' 60 ' | 60"
	})
	void parseMaxAgeSeconds_acceptsSecondsAndUnits(String maxAge, int expectedSeconds) {
		assertEquals(expectedSeconds, AuthTokenCookies.parseMaxAgeSeconds(maxAge));
	}

	@ParameterizedTest(name = "max-age \"{0}\" is rejected")
	@ValueSource(strings = {"0", "-5", "500ms", "abc", "3000000000"})
	void parseMaxAgeSeconds_rejectsValuesThatAreNotAPositiveTimeout(String maxAge) {
		assertThrows(IllegalStateException.class, () -> AuthTokenCookies.parseMaxAgeSeconds(maxAge));
	}

	@ParameterizedTest(name = "auth_maxage {0} -> idle timeout {1} s")
	@CsvSource(nullValues = "null", value = {
			"null, 3600",
			"600,  600",
			"7200, 3600",
			"0,    3600",
			"-1,   3600"
	})
	void applySessionLifetime_requestedMaxAgeCanOnlyShortenTheLifetime(Integer requestedMaxAge, int expectedSeconds) {
		var request = new MockHttpServletRequest();

		authTokenCookies("3600", true).applySessionLifetime(request, requestedMaxAge);

		assertEquals(expectedSeconds, request.getSession().getMaxInactiveInterval());
	}

	@Test
	void expiry_refreshExpirationOnAccess_isIdleTimeoutFromNow() {
		var cookies = authTokenCookies("3600", true);
		var request = new MockHttpServletRequest();
		cookies.applySessionLifetime(request, null);

		Instant expiry = cookies.expiry(request.getSession());

		assertTrue(Duration.between(Instant.now().plusSeconds(3600), expiry).abs().toSeconds() <= 1);
		assertFalse(cookies.isLoginExpired(request.getSession()));
	}

	@Test
	void expiry_fixedExpiration_isLoginPlusLifetime() {
		var cookies = authTokenCookies("3600", false);
		var request = new MockHttpServletRequest();
		cookies.applySessionLifetime(request, null);

		Instant expiry = cookies.expiry(request.getSession());

		assertTrue(Duration.between(Instant.now().plusSeconds(3600), expiry).abs().toSeconds() <= 1);
		assertEquals(expiry, request.getSession().getAttribute(AuthTokenCookies.LOGIN_EXPIRY_ATTRIBUTE));
		assertFalse(cookies.isLoginExpired(request.getSession()));
	}

	@Test
	void isLoginExpired_fixedExpiration_pastLoginExpiry_isTrue() {
		var cookies = authTokenCookies("3600", false);
		var request = new MockHttpServletRequest();
		cookies.applySessionLifetime(request, null);
		request.getSession().setAttribute(AuthTokenCookies.LOGIN_EXPIRY_ATTRIBUTE, Instant.now().minusSeconds(1));

		assertTrue(cookies.isLoginExpired(request.getSession()));
	}

	static AuthTokenCookies authTokenCookies(String sameSite, boolean httpOnly, String domain) {
		return authTokenCookies(sameSite, httpOnly, domain, "86400", true);
	}

	static AuthTokenCookies authTokenCookies(String maxAge, boolean refreshExpirationOnAccess) {
		return authTokenCookies("strict", true, "", maxAge, refreshExpirationOnAccess);
	}

	private static AuthTokenCookies authTokenCookies(String sameSite, boolean httpOnly, String domain, String maxAge,
			boolean refreshExpirationOnAccess) {
		var repositoryManager = mock(RepositoryManager.class);
		when(repositoryManager.getRepositoryURL()).thenReturn(url("https://example.org/store/"));
		var environment = new MockEnvironment().withProperty("server.servlet.session.cookie.same-site", sameSite);
		return new AuthTokenCookies(repositoryManager, environment, "auth_token", domain, httpOnly, false,
				"/store", "auto", maxAge, refreshExpirationOnAccess);
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
