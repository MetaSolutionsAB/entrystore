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

import jakarta.servlet.http.HttpSession;
import org.entrystore.User;
import org.entrystore.rest.springboot.model.auth.SessionInfo;
import org.entrystore.rest.springboot.security.AuthTokenCookies;
import org.entrystore.rest.springboot.security.ESUserDetailsService;
import org.entrystore.rest.springboot.security.ESUserSessionDetails;
import org.entrystore.rest.springboot.util.ErrorResponseWriter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.session.SessionInformation;
import org.springframework.security.core.session.SessionRegistryImpl;

import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReloadUserPropertiesFilterTest {

	private static final String USERNAME = "https://example.org/store/_principals/resource/7";
	private static final String LAST_RELOAD = ReloadUserPropertiesFilter.LAST_RELOAD_ATTRIBUTE;

	private final ESUserDetailsService userDetailsService = mock(ESUserDetailsService.class);
	private final SessionRegistryImpl sessionRegistry = new SessionRegistryImpl();
	private final AuthTokenCookies authTokenCookies = mock(AuthTokenCookies.class);
	private final ReloadUserPropertiesFilter filter = filterWithReloadInterval(10);
	private MockHttpServletRequest request;
	private String sessionId;

	@BeforeEach
	void logIn() {
		lenient().when(authTokenCookies.expiry(any())).thenReturn(Instant.now().plusSeconds(3600));
		ESUserSessionDetails loggedIn = sessionDetails();
		request = new MockHttpServletRequest();
		sessionId = request.getSession().getId();
		sessionRegistry.registerNewSession(sessionId, loggedIn);
		SecurityContextHolder.getContext().setAuthentication(
				new UsernamePasswordAuthenticationToken(loggedIn, "", loggedIn.getAuthorities()));
	}

	@AfterEach
	void clearContext() {
		SecurityContextHolder.clearContext();
	}

	@Test
	void request_updatesTheSessionInfoOfTheRegisteredSession() throws Exception {
		SessionInformation registered = sessionRegistry.getSessionInformation(sessionId);
		ESUserSessionDetails reloaded = sessionDetails();
		when(userDetailsService.loadUserByUsername(USERNAME)).thenReturn(reloaded);
		var chain = new MockFilterChain();

		filter.doFilter(request, new MockHttpServletResponse(), chain);

		assertSame(registered, sessionRegistry.getSessionInformation(sessionId));
		var registeredUser = (ESUserSessionDetails) registered.getPrincipal();
		assertSame(reloaded.getSessionInfo(), registeredUser.getSessionInfo());
		assertNotNull(registeredUser.getSessionInfo().lastAccessTime());
		assertFalse(registered.isExpired());
		assertNotNull(chain.getRequest());
	}

	@Test
	void sessionRevokedWhileTheRequestRuns_staysRevoked() throws Exception {
		// e.g. an admin disables and re-enables the user, or a concurrent request deletes the token
		when(userDetailsService.loadUserByUsername(USERNAME)).thenAnswer(invocation -> {
			sessionRegistry.getSessionInformation(sessionId).expireNow();
			return sessionDetails();
		});

		filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

		assertTrue(sessionRegistry.getSessionInformation(sessionId).isExpired());
	}

	@Test
	void unregisteredSession_isRegistered() throws Exception {
		sessionRegistry.removeSessionInformation(sessionId);
		ESUserSessionDetails reloaded = sessionDetails();
		when(userDetailsService.loadUserByUsername(USERNAME)).thenReturn(reloaded);

		filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

		assertSame(reloaded, sessionRegistry.getSessionInformation(sessionId).getPrincipal());
	}

	@Test
	void sessionEndedByAConcurrentRequest_isServedWithoutTheSession() throws Exception {
		var session = new MockHttpSession();
		session.invalidate();
		// the session was looked up before the concurrent request invalidated it
		var racingRequest = new MockHttpServletRequest() {
			@Override
			public HttpSession getSession(boolean create) {
				return session;
			}
		};
		when(userDetailsService.loadUserByUsername(USERNAME)).thenReturn(sessionDetails());
		var response = new MockHttpServletResponse();
		var chain = new MockFilterChain();

		filter.doFilter(racingRequest, response, chain);

		assertEquals(HttpStatus.OK.value(), response.getStatus());
		assertNotNull(chain.getRequest());
		assertNull(sessionRegistry.getSessionInformation(session.getId()));
	}

	@Test
	void requestWithinTheReloadInterval_keepsTheSessionsUserAndUpdatesTheSessionInfo() throws Exception {
		var loggedIn = SecurityContextHolder.getContext().getAuthentication();
		reloadedAt(Instant.now().minusSeconds(5));
		var chain = new MockFilterChain();

		filter.doFilter(request, new MockHttpServletResponse(), chain);

		verify(userDetailsService, never()).loadUserByUsername(any());
		assertSame(loggedIn, SecurityContextHolder.getContext().getAuthentication());
		var registeredUser = (ESUserSessionDetails) sessionRegistry.getSessionInformation(sessionId).getPrincipal();
		assertNotNull(registeredUser.getSessionInfo().lastAccessTime());
		assertNotNull(chain.getRequest());
	}

	@Test
	void requestAfterTheReloadInterval_reloadsTheUser() throws Exception {
		Instant lastReload = Instant.now().minusSeconds(10);
		var reloadClock = reloadedAt(lastReload);
		when(userDetailsService.loadUserByUsername(USERNAME)).thenReturn(sessionDetails("ROLE_ADMIN"));

		filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

		var authorities = SecurityContextHolder.getContext().getAuthentication().getAuthorities();
		assertEquals(List.of(new SimpleGrantedAuthority("ROLE_ADMIN")), List.copyOf(authorities));
		assertTrue(reloadClock.get().isAfter(lastReload));
	}

	@Test
	void reloadedUser_isNotReloadedByTheNextRequestOfTheSession() throws Exception {
		when(userDetailsService.loadUserByUsername(USERNAME)).thenReturn(sessionDetails());
		filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());
		var nextRequest = new MockHttpServletRequest();
		nextRequest.setSession(request.getSession());

		filter.doFilter(nextRequest, new MockHttpServletResponse(), new MockFilterChain());

		verify(userDetailsService, times(1)).loadUserByUsername(USERNAME);
	}

	@Test
	void requestArrivingWhileTheSessionsUserIsReloaded_skipsTheReload() throws Exception {
		var parallelRequest = new MockHttpServletRequest();
		parallelRequest.setSession(request.getSession());
		when(userDetailsService.loadUserByUsername(USERNAME)).thenAnswer(invocation -> {
			filter.doFilter(parallelRequest, new MockHttpServletResponse(), new MockFilterChain());
			return sessionDetails();
		});

		filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

		verify(userDetailsService, times(1)).loadUserByUsername(USERNAME);
	}

	@Test
	void requestReachingTheClaimAfterAnotherReadTheReloadTime_doesNotReloadAgain() throws Exception {
		var reloadClock = reloadedAt(Instant.now().minusSeconds(10));
		var parallelRequest = new MockHttpServletRequest();
		var parallelClaim = new AtomicReference<Instant>();
		var parallelRequestRan = new AtomicBoolean();
		var session = new MockHttpSession() {
			// read right after the reload time, before the claim
			@Override
			public int getMaxInactiveInterval() {
				if (!parallelRequestRan.getAndSet(true)) {
					runParallel(filter, parallelRequest);
					parallelClaim.set(reloadClock.get());
				}
				return super.getMaxInactiveInterval();
			}
		};
		session.setAttribute(LAST_RELOAD, reloadClock);
		request.setSession(session);
		parallelRequest.setSession(session);
		when(userDetailsService.loadUserByUsername(USERNAME)).thenReturn(sessionDetails());

		filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

		verify(userDetailsService, times(1)).loadUserByUsername(USERNAME);
		assertSame(parallelClaim.get(), reloadClock.get());
	}

	@Test
	void reloadClaimedJustBeforeTheReloadTimeIsRead_isNotTakenForAClockSetBack() throws Exception {
		var reloadClock = reloadedAt(Instant.now().minusSeconds(10));
		var parallelRequest = new MockHttpServletRequest();
		var parallelClaim = new AtomicReference<Instant>();
		var parallelRequestRan = new AtomicBoolean();
		var session = new MockHttpSession() {
			@Override
			public Object getAttribute(String name) {
				if (LAST_RELOAD.equals(name) && !parallelRequestRan.getAndSet(true)) {
					runParallel(filter, parallelRequest);
					parallelClaim.set(reloadClock.get());
				}
				return super.getAttribute(name);
			}
		};
		session.setAttribute(LAST_RELOAD, reloadClock);
		request.setSession(session);
		parallelRequest.setSession(session);
		when(userDetailsService.loadUserByUsername(USERNAME)).thenReturn(sessionDetails());

		filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

		verify(userDetailsService, times(1)).loadUserByUsername(USERNAME);
		assertSame(parallelClaim.get(), reloadClock.get());
	}

	@Test
	void failedReload_isRetriedByTheNextRequest() throws Exception {
		Instant lastReload = Instant.now().minusSeconds(10);
		var reloadClock = reloadedAt(lastReload);
		when(userDetailsService.loadUserByUsername(USERNAME)).thenThrow(new IllegalStateException("store down"));
		var response = new MockHttpServletResponse();

		filter.doFilter(request, response, new MockFilterChain());

		assertEquals(HttpStatus.INTERNAL_SERVER_ERROR.value(), response.getStatus());
		assertSame(lastReload, reloadClock.get());
	}

	@Test
	void failedReload_keepsANewerClaim() throws Exception {
		var reloadClock = reloadedAt(Instant.now().minusSeconds(10));
		Instant newerClaim = Instant.now().plusSeconds(1);
		when(userDetailsService.loadUserByUsername(USERNAME)).thenAnswer(invocation -> {
			reloadClock.set(newerClaim);
			throw new IllegalStateException("store down");
		});

		filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

		assertSame(newerClaim, reloadClock.get());
	}

	@Test
	void failedFirstReload_leavesNoReloadTime() throws Exception {
		when(userDetailsService.loadUserByUsername(USERNAME)).thenThrow(new IllegalStateException("store down"));

		filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

		assertNull(((AtomicReference<?>) request.getSession().getAttribute(LAST_RELOAD)).get());
	}

	@Test
	void reloadIntervalZero_reloadsOnEveryRequestAlsoWhenTheyOverlap() throws Exception {
		var reloadClock = reloadedAt(Instant.now());
		var reloadOnEveryRequest = filterWithReloadInterval(0);
		var parallelRequest = new MockHttpServletRequest();
		var parallelRequestRan = new AtomicBoolean();
		var session = new MockHttpSession() {
			// read right after the reload time, before the claim
			@Override
			public int getMaxInactiveInterval() {
				if (!parallelRequestRan.getAndSet(true)) {
					runParallel(reloadOnEveryRequest, parallelRequest);
				}
				return super.getMaxInactiveInterval();
			}
		};
		session.setAttribute(LAST_RELOAD, reloadClock);
		request.setSession(session);
		parallelRequest.setSession(session);
		when(userDetailsService.loadUserByUsername(USERNAME)).thenReturn(sessionDetails());

		reloadOnEveryRequest.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

		verify(userDetailsService, times(2)).loadUserByUsername(USERNAME);
	}

	@Test
	void requestWithoutSession_reloadsTheUserWithoutCreatingASession() throws Exception {
		// HTTP Basic
		var basicRequest = new MockHttpServletRequest();
		when(userDetailsService.loadUserByUsername(USERNAME)).thenReturn(sessionDetails());

		filter.doFilter(basicRequest, new MockHttpServletResponse(), new MockFilterChain());

		verify(userDetailsService).loadUserByUsername(USERNAME);
		assertNull(basicRequest.getSession(false));
	}

	@Test
	void negativeReloadInterval_isRejected() {
		var e = assertThrows(IllegalArgumentException.class, () -> filterWithReloadInterval(-1));
		assertTrue(e.getMessage().contains("entrystore.auth.session.reload-interval"));
	}

	/** Runs a request of the same session while the test's request is in the filter. */
	private static void runParallel(ReloadUserPropertiesFilter filter, MockHttpServletRequest parallelRequest) {
		try {
			filter.doFilter(parallelRequest, new MockHttpServletResponse(), new MockFilterChain());
		} catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}

	/** Gives the session the reload clock a login creates, set to the given time. */
	private AtomicReference<Instant> reloadedAt(Instant lastReload) {
		var reloadClock = new AtomicReference<>(lastReload);
		request.getSession().setAttribute(LAST_RELOAD, reloadClock);
		return reloadClock;
	}

	private ReloadUserPropertiesFilter filterWithReloadInterval(int seconds) {
		return new ReloadUserPropertiesFilter(userDetailsService, sessionRegistry,
				new ErrorResponseWriter(JsonMapper.builder().build()), authTokenCookies, seconds);
	}

	private static ESUserSessionDetails sessionDetails() {
		return sessionDetails("ROLE_USER");
	}

	private static ESUserSessionDetails sessionDetails(String role) {
		var springUser = new org.springframework.security.core.userdetails.User(
				USERNAME, "", List.of(new SimpleGrantedAuthority(role)));
		return new ESUserSessionDetails(springUser, mock(User.class), SessionInfo.builder().userName("alice").build());
	}
}
