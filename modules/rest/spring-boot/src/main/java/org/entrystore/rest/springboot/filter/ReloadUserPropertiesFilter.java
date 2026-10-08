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
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import lombok.extern.slf4j.Slf4j;
import org.entrystore.rest.springboot.model.api.ErrorResponse;
import org.entrystore.rest.springboot.model.auth.SessionInfo;
import org.entrystore.rest.springboot.security.AuthTokenCookies;
import org.entrystore.rest.springboot.security.ESUserDetailsService;
import org.entrystore.rest.springboot.security.ESUserSessionDetails;
import org.entrystore.rest.springboot.util.ErrorResponseWriter;
import org.entrystore.rest.springboot.util.HttpUtil;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.session.SessionInformation;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.WebUtils;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Reloads the user's properties at most once every {@code entrystore.auth.session.reload-interval} seconds per
 * session, and on every HTTP Basic request, which has no session. Between reloads a request uses the session's
 * Authentication as is, so a change of the user's admin role reaches an open session up to the interval late.
 * Disabling or deleting a user, changing their password and deleting a token are not delayed: they expire the session
 * in the SessionRegistry, which ConcurrentSessionFilter enforces on the next request. A disabled or deleted user's
 * session ends and its cookie is expired, as 5.x removed their tokens, so the long-lived cookie does not keep failing.
 * A request whose session a concurrent request ends meanwhile is served as an HTTP Basic request is, without the
 * session, and the session context repository keeps it from getting a new one.
 * <p>
 * A reload replaces the Authentication in the session's own SecurityContext instance, so it lasts without an explicit
 * save; this relies on the in-memory session store of the single EntryStore instance.
 */
@Slf4j
@Component
public class ReloadUserPropertiesFilter extends OncePerRequestFilter {

	/**
	 * Holds, as an {@code AtomicReference<Instant>}, when the session's user was last reloaded or a request claimed
	 * the reload. The login creates it, before any other request can reach the session.
	 */
	public static final String LAST_RELOAD_ATTRIBUTE = ReloadUserPropertiesFilter.class.getName() + ".LAST_RELOAD";

	private final ESUserDetailsService userDetailsService;
	private final SessionRegistry sessionRegistry;
	private final ErrorResponseWriter errorResponseWriter;
	private final AuthTokenCookies authTokenCookies;
	private final Duration reloadInterval;

	/**
	 * @param reloadIntervalSeconds how long a session uses its loaded user before reloading it; 0 reloads on every
	 *                              request
	 * @throws IllegalArgumentException if the interval is negative
	 */
	public ReloadUserPropertiesFilter(ESUserDetailsService userDetailsService, SessionRegistry sessionRegistry,
			ErrorResponseWriter errorResponseWriter, AuthTokenCookies authTokenCookies,
			@Value("${entrystore.auth.session.reload-interval:10}") int reloadIntervalSeconds) {
		if (reloadIntervalSeconds < 0) {
			throw new IllegalArgumentException("entrystore.auth.session.reload-interval must be 0 (every request) or "
					+ "positive, got " + reloadIntervalSeconds);
		}
		this.userDetailsService = userDetailsService;
		this.sessionRegistry = sessionRegistry;
		this.errorResponseWriter = errorResponseWriter;
		this.authTokenCookies = authTokenCookies;
		this.reloadInterval = Duration.ofSeconds(reloadIntervalSeconds);
	}

	@Override
	protected void doFilterInternal(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response,
									@NonNull FilterChain filterChain) throws ServletException, IOException {

		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

		if (authentication != null && authentication.getPrincipal() instanceof ESUserSessionDetails esUserDetails) {
			HttpSession httpSession = request.getSession(false);
			SessionState session = httpSession == null ? null : readSession(httpSession);
			// after reading the reload time, so that a claim made meanwhile is never taken for a clock set back
			Instant now = Instant.now();
			if (session != null && !claimReload(session, now)) {
				esUserDetails.setSessionInfo(sessionInfo(esUserDetails, session, request, now));
				updateRegisteredSession(session.id(), esUserDetails);
				filterChain.doFilter(request, response);
				return;
			}
			try {
				ESUserSessionDetails updatedUser =
						(ESUserSessionDetails) userDetailsService.loadUserByUsername(esUserDetails.getUsername());

				if (!updatedUser.isEnabled()) {
					HttpUtil.clearAuthenticatedSession(request);
					authTokenCookies.expireAll(request, response);
					errorResponseWriter.writeErrorResponseAsJson(response, ErrorResponse.builder()
							.status(HttpStatus.FORBIDDEN.value())
							.path(request.getRequestURI())
							.error("User account is disabled.")
							.build());
					return;
				}

				updatedUser.setSessionInfo(sessionInfo(esUserDetails, session, request, now));

				UsernamePasswordAuthenticationToken newAuth = new UsernamePasswordAuthenticationToken(updatedUser,
						updatedUser.getPassword(), updatedUser.getAuthorities());
				SecurityContextHolder.getContext().setAuthentication(newAuth);
				if (session != null) {
					updateRegisteredSession(session.id(), updatedUser);
				}
			} catch (UsernameNotFoundException e) {
				log.warn("User no longer found during session reload: {}", e.getMessage());
				HttpUtil.clearAuthenticatedSession(request);
				authTokenCookies.expireAll(request, response);
				errorResponseWriter.writeErrorResponseAsJson(response, ErrorResponse.builder()
						.status(HttpStatus.UNAUTHORIZED.value())
						.path(request.getRequestURI())
						.error("User account is not found.")
						.build());
				return;
			} catch (ClassCastException e) {
				log.error("Unexpected principal type during user details reload", e);
				releaseReload(session, now);
				SecurityContextHolder.clearContext();
				errorResponseWriter.writeErrorResponseAsJson(response, ErrorResponse.builder()
						.status(HttpStatus.INTERNAL_SERVER_ERROR.value())
						.path(request.getRequestURI())
						.error("Authentication error.")
						.build());
				return;
			} catch (Exception e) {
				log.error("Failed to reload user details", e);
				releaseReload(session, now);
				SecurityContextHolder.clearContext();
				errorResponseWriter.writeErrorResponseAsJson(response, ErrorResponse.builder()
						.status(HttpStatus.INTERNAL_SERVER_ERROR.value())
						.path(request.getRequestURI())
						.error("Authentication error.")
						.build());
				return;
			}
		}

		filterChain.doFilter(request, response);
	}

	/** The session values this filter uses, read together so that a concurrent invalidation cannot interrupt it. */
	private record SessionState(String id, Instant loginExpiry, int maxInactiveInterval,
								AtomicReference<Instant> reloadClock, Instant lastReload) {}

	/**
	 * @return the session's values, or null if a concurrent request has just ended it, after which the request is
	 * served without the session
	 */
	private SessionState readSession(HttpSession session) {
		try {
			AtomicReference<Instant> reloadClock = reloadClock(session);
			Instant lastReload = reloadClock.get();
			return new SessionState(session.getId(), authTokenCookies.expiry(session),
					session.getMaxInactiveInterval(), reloadClock, lastReload);
		} catch (IllegalStateException endedByAConcurrentRequest) {
			return null;
		}
	}

	/**
	 * @return whether the interval has passed since the last reload; a reload time ahead of {@code now} counts as
	 * recent, unless it is more than the interval ahead, which means the clock was set back
	 */
	private boolean isReloadDue(Instant lastReload, Instant now) {
		if (lastReload == null) {
			return true;
		}
		Duration sinceReload = Duration.between(lastReload, now);
		if (sinceReload.isNegative()) {
			return sinceReload.negated().compareTo(reloadInterval) > 0;
		}
		return sinceReload.compareTo(reloadInterval) >= 0;
	}

	/**
	 * @return the session's reload clock; created here only for a session whose login did not create it
	 */
	@SuppressWarnings("unchecked")
	private static AtomicReference<Instant> reloadClock(HttpSession session) {
		if (session.getAttribute(LAST_RELOAD_ATTRIBUTE) instanceof AtomicReference<?> clock) {
			return (AtomicReference<Instant>) clock;
		}
		synchronized (WebUtils.getSessionMutex(session)) {
			if (session.getAttribute(LAST_RELOAD_ATTRIBUTE) instanceof AtomicReference<?> clock) {
				return (AtomicReference<Instant>) clock;
			}
			var clock = new AtomicReference<Instant>();
			session.setAttribute(LAST_RELOAD_ATTRIBUTE, clock);
			return clock;
		}
	}

	/**
	 * Claims the session's reload if it is due, so that of the session's parallel requests exactly one reloads. With
	 * an interval of 0 every request reloads.
	 *
	 * @return whether this request is to reload the user
	 */
	private boolean claimReload(SessionState session, Instant now) {
		if (reloadInterval.isZero()) {
			return true;
		}
		return isReloadDue(session.lastReload(), now) && session.reloadClock().compareAndSet(session.lastReload(), now);
	}

	/** Releases the claim of a failed reload, unless a newer one replaced it, so that the next request retries. */
	private static void releaseReload(SessionState session, Instant claimed) {
		if (session != null) {
			session.reloadClock().compareAndSet(claimed, session.lastReload());
		}
	}

	/** @return the session info that /auth/tokens reports, as of this request */
	private static SessionInfo sessionInfo(ESUserSessionDetails loggedIn, SessionState session,
										   HttpServletRequest request, Instant now) {
		return SessionInfo.builder()
				.userName(loggedIn.getSessionInfo().userName())
				.loginTime(loggedIn.getSessionInfo().loginTime())
				.loginExpiration(session != null
						? LocalDateTime.ofInstant(session.loginExpiry(), ZoneId.systemDefault())
						: null)
				.lastAccessTime(LocalDateTime.ofInstant(now, ZoneId.systemDefault()))
				.lastUsedIpAddress(request.getRemoteAddr())
				.lastUsedUserAgent(request.getHeader("User-Agent"))
				.loginTokenMaxAge(session != null ? session.maxInactiveInterval() : 0)
				.build();
	}

	/**
	 * Updates the session info that /auth/tokens reports on the registered session. The registered entry is kept, not
	 * registered anew, because registering replaces it with an unexpired one and would undo a revocation (user
	 * disabled, password changed, token deleted) made by a concurrent request.
	 */
	private void updateRegisteredSession(String sessionId, ESUserSessionDetails updatedUser) {
		SessionInformation registered = sessionRegistry.getSessionInformation(sessionId);
		if (registered == null) {
			sessionRegistry.registerNewSession(sessionId, updatedUser);
			return;
		}
		if (registered.getPrincipal() instanceof ESUserSessionDetails registeredUser) {
			registeredUser.setSessionInfo(updatedUser.getSessionInfo());
		}
		registered.refreshLastRequest();
	}
}
