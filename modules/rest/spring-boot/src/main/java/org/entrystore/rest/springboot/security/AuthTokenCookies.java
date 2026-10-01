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

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.Getter;
import org.entrystore.repository.RepositoryManager;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.server.Cookie;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.logout.LogoutHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.util.WebUtils;

import java.net.URL;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Owns the path of the session cookie (auth_token), matches the session's idle timeout to the cookie's lifetime,
 * and expires the cookie when it is unknown, expired or logged out.
 *
 * <p>The cookie is issued on {@code entrystore.auth.cookie.path}; its default {@code auto} is the path of the
 * repository base URL (e.g. {@code /store/}), as in 5.x. Browsers identify a cookie by name, domain and path, and may
 * hold one on the base-URL path (5.x) and one on the servlet context path (earlier 6.0 builds), each host-only (5.x)
 * or on the configured domain. The client often cannot remove them (HttpOnly, cross-site), so each is expired with
 * the SameSite, Secure and HttpOnly the container uses ({@code server.servlet.session.cookie.*}); without
 * SameSite=None a browser drops a cross-site expiry.
 */
@Component
public class AuthTokenCookies implements LogoutHandler {

	private static final String EXPIRED_ATTRIBUTE = AuthTokenCookies.class.getName() + ".EXPIRED";

	private final String cookieName;
	private final boolean httpOnly;
	private final boolean secure;
	private final String sameSite;
	@Getter
	private final String issuingPath;
	private final List<Target> allTargets;
	private final List<Target> staleTargets;

	/** A cookie identity, which browsers compose of name, domain and path; a null domain is host-only. */
	private record Target(String domain, String path) {}

	public AuthTokenCookies(RepositoryManager repositoryManager, Environment environment,
							@Value("${server.servlet.session.cookie.name:auth_token}") String cookieName,
							@Value("${server.servlet.session.cookie.domain:}") String domain,
							@Value("${server.servlet.session.cookie.http-only:true}") boolean httpOnly,
							@Value("${server.servlet.session.cookie.secure:true}") boolean configuredSecure,
							@Value("${server.servlet.context-path:}") String contextPath,
							@Value("${entrystore.auth.cookie.path:auto}") String configuredPath) {
		Cookie.SameSite resolvedSameSite = SecurityConfig.resolveSessionCookieSameSite(environment);
		this.cookieName = cookieName;
		this.httpOnly = httpOnly;
		this.secure = SecurityConfig.requiresSecureCookie(configuredSecure, resolvedSameSite);
		this.sameSite = resolvedSameSite.attributeValue();
		this.issuingPath = resolveIssuingPath(configuredPath, repositoryManager.getRepositoryURL());
		// 5.x never set a Domain, so its host-only cookie must be expired even when a Domain is configured
		String issuingDomain = domain.isBlank() ? null : domain;
		var domains = issuingDomain == null ? Arrays.asList((String) null) : Arrays.asList(issuingDomain, null);
		this.allTargets = expiryPaths(issuingPath, contextPath).stream()
				.flatMap(path -> domains.stream().map(d -> new Target(d, path)))
				.toList();
		var issued = new Target(issuingDomain, issuingPath);
		this.staleTargets = allTargets.stream().filter(target -> !target.equals(issued)).toList();
	}

	/**
	 * Lets the session idle as long as the cookie lives ({@code server.servlet.session.cookie.max-age}) instead of
	 * the container's 30-minute default.
	 */
	public void applySessionLifetime(HttpServletRequest request) {
		request.getSession().setMaxInactiveInterval(request.getServletContext().getSessionCookieConfig().getMaxAge());
	}

	/** Expires the cookie on all paths it may have been issued on, if the request carries it. */
	public void expireAll(HttpServletRequest request, HttpServletResponse response) {
		expire(request, response, allTargets);
	}

	/**
	 * Expires the cookie everywhere except where the container issues it, if the request carries it. Used on login,
	 * where the container sets the new session cookie on the issuing path in the same response.
	 */
	public void expireStale(HttpServletRequest request, HttpServletResponse response) {
		expire(request, response, staleTargets);
	}

	@Override
	public void logout(HttpServletRequest request, HttpServletResponse response, Authentication authentication) {
		expireAll(request, response);
	}

	private void expire(HttpServletRequest request, HttpServletResponse response, List<Target> targets) {
		if (WebUtils.getCookie(request, cookieName) == null) {
			return;
		}
		// With invalid-token-error off, ConcurrentSessionFilter's logout and InvalidSessionCookieFilter both expire it
		@SuppressWarnings("unchecked")
		var expired = (Set<Target>) request.getAttribute(EXPIRED_ATTRIBUTE);
		if (expired == null) {
			expired = new HashSet<>();
			request.setAttribute(EXPIRED_ATTRIBUTE, expired);
		}
		for (Target target : targets) {
			if (!expired.add(target)) {
				continue;
			}
			var cookie = ResponseCookie.from(cookieName, "")
					.domain(target.domain())
					.path(target.path())
					.maxAge(0)
					.httpOnly(httpOnly)
					.secure(secure)
					.sameSite(sameSite);
			response.addHeader(HttpHeaders.SET_COOKIE, cookie.build().toString());
		}
	}

	static String resolveIssuingPath(String configuredPath, URL repositoryUrl) {
		if (configuredPath != null && !configuredPath.isBlank() && !"auto".equalsIgnoreCase(configuredPath.trim())) {
			return configuredPath.trim();
		}
		String path = repositoryUrl.getPath();
		return path.isEmpty() ? "/" : path;
	}

	/** Returns each of the issuing and the context path both without and with a trailing slash. */
	static List<String> expiryPaths(String issuingPath, String contextPath) {
		var paths = new LinkedHashSet<String>();
		for (String path : List.of(issuingPath, contextPath.isBlank() ? "/" : contextPath)) {
			String withoutSlash = path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
			paths.add(withoutSlash.isEmpty() ? "/" : withoutSlash);
			paths.add(withoutSlash + "/");
		}
		return List.copyOf(paths);
	}
}
