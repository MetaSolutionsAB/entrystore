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
import jakarta.servlet.http.HttpSession;
import lombok.Getter;
import org.entrystore.repository.RepositoryManager;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.boot.web.server.Cookie;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.logout.LogoutHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.util.WebUtils;

import java.net.URL;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Owns the session cookie (auth_token): its path and lifetime, the login lifetime of the session behind it, and
 * expiring the cookie when it is unknown, expired or logged out.
 *
 * <p>As in 5.x, a login lasts {@code entrystore.auth.cookie.max-age} (or a shorter {@code auth_maxage}). With
 * {@code entrystore.auth.cookie.refresh-expiration-on-access} on (the default) that is the session's idle timeout,
 * so an active user stays logged in, and the cookie lives 365 days because the container sends it only at login.
 * With it off, the cookie lives max-age and the login ends max-age after it was made, whatever the activity.
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

	private static final String LOGIN_EXPIRY_ATTRIBUTE = AuthTokenCookies.class.getName() + ".LOGIN_EXPIRY";

	private static final int REFRESHED_COOKIE_MAX_AGE = (int) Duration.ofDays(365).toSeconds();

	private final String cookieName;
	private final boolean httpOnly;
	private final boolean secure;
	private final String sameSite;
	@Getter
	private final String issuingPath;
	private final List<Target> allTargets;
	private final List<Target> staleTargets;
	private final int maxAgeSeconds;
	@Getter
	private final boolean refreshExpirationOnAccess;

	/** A cookie identity, which browsers compose of name, domain and path; a null domain is host-only. */
	private record Target(String domain, String path) {}

	public AuthTokenCookies(RepositoryManager repositoryManager, Environment environment,
							@Value("${server.servlet.session.cookie.name:auth_token}") String cookieName,
							@Value("${server.servlet.session.cookie.domain:}") String domain,
							@Value("${server.servlet.session.cookie.http-only:true}") boolean httpOnly,
							@Value("${server.servlet.session.cookie.secure:true}") boolean configuredSecure,
							@Value("${server.servlet.context-path:}") String contextPath,
							@Value("${entrystore.auth.cookie.path:auto}") String configuredPath,
							@Value("${entrystore.auth.cookie.max-age:86400}") String maxAge,
							@Value("${entrystore.auth.cookie.refresh-expiration-on-access:true}") boolean refreshExpirationOnAccess) {
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
		this.maxAgeSeconds = parseMaxAgeSeconds(maxAge);
		this.refreshExpirationOnAccess = refreshExpirationOnAccess;
	}

	/**
	 * @return the cookie's Max-Age: 365 days with refresh-expiration-on-access, otherwise the login max-age
	 */
	public int cookieMaxAgeSeconds() {
		return refreshExpirationOnAccess ? REFRESHED_COOKIE_MAX_AGE : maxAgeSeconds;
	}

	/**
	 * Sets the login lifetime of the request's session: max-age, or {@code requestedMaxAge} if it is positive and
	 * shorter. Call on every successful login, after the session id has changed.
	 *
	 * @param requestedMaxAge the client's {@code auth_maxage} in seconds, or null
	 */
	public void applySessionLifetime(HttpServletRequest request, Integer requestedMaxAge) {
		int lifetime = requestedMaxAge != null && requestedMaxAge > 0
				? Math.min(maxAgeSeconds, requestedMaxAge)
				: maxAgeSeconds;
		HttpSession session = request.getSession();
		session.setMaxInactiveInterval(lifetime);
		if (!refreshExpirationOnAccess) {
			session.setAttribute(LOGIN_EXPIRY_ATTRIBUTE, Instant.now().plusSeconds(lifetime));
		}
	}

	/**
	 * @return when the session's login ends: after its idle timeout from now with refresh-expiration-on-access,
	 * otherwise at the fixed time set at login
	 */
	public Instant expiry(HttpSession session) {
		if (!refreshExpirationOnAccess && session.getAttribute(LOGIN_EXPIRY_ATTRIBUTE) instanceof Instant expiry) {
			return expiry;
		}
		return Instant.now().plusSeconds(session.getMaxInactiveInterval());
	}

	/**
	 * @return whether the session's login has passed its fixed end; always false with refresh-expiration-on-access
	 */
	boolean isLoginExpired(HttpSession session) {
		return !refreshExpirationOnAccess
				&& session.getAttribute(LOGIN_EXPIRY_ATTRIBUTE) instanceof Instant expiry
				&& Instant.now().isAfter(expiry);
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

	/**
	 * Parses max-age as seconds unless it carries a unit (e.g. {@code 1d}), as Spring Boot does for durations.
	 *
	 * @throws IllegalStateException unless it is at least one second and fits a session timeout
	 */
	static int parseMaxAgeSeconds(String maxAge) {
		Duration duration;
		try {
			duration = DurationStyle.detectAndParse(maxAge.trim(), ChronoUnit.SECONDS);
		} catch (IllegalArgumentException e) {
			throw new IllegalStateException("Invalid entrystore.auth.cookie.max-age '" + maxAge + "'", e);
		}
		long seconds = duration.toSeconds();
		if (seconds < 1 || seconds > Integer.MAX_VALUE) {
			throw new IllegalStateException("entrystore.auth.cookie.max-age must be between 1 and "
					+ Integer.MAX_VALUE + " seconds, was '" + maxAge + "'");
		}
		return (int) seconds;
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
