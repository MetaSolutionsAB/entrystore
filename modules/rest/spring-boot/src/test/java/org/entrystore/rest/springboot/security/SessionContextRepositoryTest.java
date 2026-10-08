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

import jakarta.servlet.Filter;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.entrystore.User;
import org.entrystore.rest.springboot.filter.ReloadUserPropertiesFilter;
import org.entrystore.rest.springboot.model.auth.SessionInfo;
import org.entrystore.rest.springboot.util.ErrorResponseWriter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.core.session.SessionRegistryImpl;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.web.authentication.AbstractAuthenticationProcessingFilter;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.authentication.session.ChangeSessionIdAuthenticationStrategy;
import org.springframework.security.web.authentication.session.CompositeSessionAuthenticationStrategy;
import org.springframework.security.web.authentication.session.ConcurrentSessionControlAuthenticationStrategy;
import org.springframework.security.web.authentication.session.RegisterSessionAuthenticationStrategy;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.session.SessionManagementFilter;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Runs SessionManagementFilter with the production repository and the strategies the session concurrency
 * configuration composes, to show that a session a concurrent request ends never gets a replacement.
 */
class SessionContextRepositoryTest {

	private static final String USERNAME = "https://example.org/store/_principals/resource/7";

	private final SecurityContextRepository repository = SecurityConfig.sessionAndRequestContextRepository();
	private final SessionRegistryImpl sessionRegistry = new SessionRegistryImpl();
	private final ESUserDetailsService userDetailsService = mock(ESUserDetailsService.class);
	private final MockHttpServletResponse response = new MockHttpServletResponse();
	private final RecordingServlet servlet = new RecordingServlet();

	@AfterEach
	void clearContext() {
		SecurityContextHolder.clearContext();
	}

	@Test
	void formSessionEndedDuringTheReload_finishesAsTheUserWithoutANewSession() throws Exception {
		var session = loggedInSession(formAuthentication());
		when(userDetailsService.loadUserByUsername(USERNAME)).thenAnswer(invocation -> {
			session.invalidate();
			return formPrincipal();
		});

		var request = requestOf(session);

		run(request, reloadFilter(), sessionManagementFilter(true));

		assertEquals(HttpStatus.OK.value(), response.getStatus());
		assertInstanceOf(ESUserSessionDetails.class, servlet.authentication.getPrincipal());
		assertNoNewSession(request, formPrincipal());
	}

	@Test
	void formSessionEndedAfterTheReload_finishesAsTheUserWithoutANewSession() throws Exception {
		var session = loggedInSession(formAuthentication());
		when(userDetailsService.loadUserByUsername(USERNAME)).thenReturn(formPrincipal());

		var request = requestOf(session);

		run(request, reloadFilter(), invalidating(session), sessionManagementFilter(true));

		assertEquals(HttpStatus.OK.value(), response.getStatus());
		assertInstanceOf(ESUserSessionDetails.class, servlet.authentication.getPrincipal());
		assertNoNewSession(request, formPrincipal());
	}

	@Test
	void ssoSessionEndedDuringTheRequest_finishesAsTheUserWithoutANewSession() throws Exception {
		var sso = ssoAuthentication();
		var session = loggedInSession(sso);

		var request = requestOf(session);

		run(request, invalidating(session), sessionManagementFilter(true));

		assertEquals(HttpStatus.OK.value(), response.getStatus());
		assertSame(sso, servlet.authentication);
		assertNoNewSession(request, sso.getPrincipal());
	}

	@Test
	void requestNamingAnEndedSession_isAnsweredAsExpired() throws Exception {
		run(requestOfAnEndedSession(), sessionManagementFilter(true));

		assertEquals(HttpStatus.UNAUTHORIZED.value(), response.getStatus());
		assertFalse(servlet.reached);
	}

	@Test
	void requestNamingAnEndedSession_withInvalidTokenErrorOff_isServedUnauthenticated() throws Exception {
		var request = requestOfAnEndedSession();

		run(request, sessionManagementFilter(false));

		assertTrue(servlet.reached);
		assertNull(servlet.authentication);
		assertNull(request.getSession(false));
	}

	@Test
	void httpBasic_staysAuthenticatedWithoutASession() throws Exception {
		var basic = formAuthentication();
		var request = new MockHttpServletRequest();
		// as BasicAuthenticationFilter keeps its Authentication
		Filter basicAuthentication = (req, res, chain) -> {
			var context = new SecurityContextImpl(basic);
			SecurityContextHolder.setContext(context);
			new RequestAttributeSecurityContextRepository().saveContext(context, (HttpServletRequest) req,
					(HttpServletResponse) res);
			chain.doFilter(req, res);
		};

		run(request, basicAuthentication, sessionManagementFilter(true));

		assertSame(basic, servlet.authentication);
		assertNull(request.getSession(false));
	}

	@Test
	void formLogin_startsAndRegistersItsSession() throws Exception {
		var login = new UsernamePasswordAuthenticationFilter(authenticatingAs(formAuthentication()));
		var request = new MockHttpServletRequest("POST", "/login");
		request.setParameter("username", "alice");
		request.setParameter("password", "secret");

		runLogin(request, login);

		assertLoggedInSession(request, formPrincipal());
	}

	@Test
	void ssoLogin_startsAndRegistersItsSession() throws Exception {
		var sso = ssoAuthentication();
		// as the OAuth2 and SAML login filters, which share this base class
		var login = new AbstractAuthenticationProcessingFilter("/login/oauth2/code/keycloak") {
			@Override
			public Authentication attemptAuthentication(HttpServletRequest request, HttpServletResponse response) {
				return sso;
			}
		};
		var request = new MockHttpServletRequest("GET", "/login/oauth2/code/keycloak");

		runLogin(request, login);

		assertLoggedInSession(request, sso.getPrincipal());
	}

	@Test
	void sessionEndedBetweenLookupAndRead_loadsAnEmptyContext() {
		var request = requestWhoseSessionEndsAfterLookup(1);

		var context = new SessionContextRepository().loadDeferredContext(request);

		assertTrue(context.isGenerated());
		assertNull(context.get().getAuthentication());
	}

	@Test
	void sessionEndedBetweenLookupAndRead_containsNoContext() {
		var request = requestWhoseSessionEndsAfterLookup(Integer.MAX_VALUE);

		assertFalse(new SessionContextRepository().containsContext(request));
	}

	private void run(MockHttpServletRequest request, Filter... filters) throws Exception {
		var chain = new Filter[filters.length + 1];
		chain[0] = new SecurityContextHolderFilter(repository);
		System.arraycopy(filters, 0, chain, 1, filters.length);
		new MockFilterChain(servlet, chain).doFilter(request, response);
	}

	private void runLogin(MockHttpServletRequest request, AbstractAuthenticationProcessingFilter login)
			throws Exception {
		login.setSecurityContextRepository(repository);
		login.setSessionAuthenticationStrategy(sessionAuthenticationStrategy());
		login.setAuthenticationSuccessHandler((req, res, auth) -> res.setStatus(HttpStatus.OK.value()));
		run(request, login, sessionManagementFilter(true));
	}

	/** Only the ended session of the earlier login is registered, and none was started. */
	private void assertNoNewSession(MockHttpServletRequest request, Object principal) {
		assertNull(request.getSession(false));
		assertEquals(1, sessionRegistry.getAllSessions(principal, true).size());
	}

	private void assertLoggedInSession(MockHttpServletRequest request, Object principal) {
		var session = request.getSession(false);
		assertNotNull(session);
		assertNotNull(session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY));
		assertNotNull(sessionRegistry.getSessionInformation(session.getId()));
		assertEquals(principal, sessionRegistry.getSessionInformation(session.getId()).getPrincipal());
	}

	/** As SecurityConfig configures it, with or without entrystore.auth.cookie.invalid-token-error. */
	private SessionManagementFilter sessionManagementFilter(boolean invalidTokenError) {
		var filter = new SessionManagementFilter(repository, sessionAuthenticationStrategy());
		if (invalidTokenError) {
			filter.setInvalidSessionStrategy((request, response) ->
					response.setStatus(HttpStatus.UNAUTHORIZED.value()));
		}
		return filter;
	}

	/** As the session concurrency configuration composes it. */
	private SessionAuthenticationStrategy sessionAuthenticationStrategy() {
		var concurrency = new ConcurrentSessionControlAuthenticationStrategy(sessionRegistry);
		concurrency.setMaximumSessions(-1);
		return new CompositeSessionAuthenticationStrategy(List.of(concurrency,
				new ChangeSessionIdAuthenticationStrategy(),
				new RegisterSessionAuthenticationStrategy(sessionRegistry)));
	}

	private ReloadUserPropertiesFilter reloadFilter() {
		return new ReloadUserPropertiesFilter(userDetailsService, sessionRegistry,
				new ErrorResponseWriter(JsonMapper.builder().build()),
				AuthTokenCookiesTest.authTokenCookies("3600", true), 10);
	}

	/** A session holding the Authentication of an earlier login, registered as that login did. */
	private MockHttpSession loggedInSession(Authentication authentication) {
		var session = new MockHttpSession();
		session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
				new SecurityContextImpl(authentication));
		sessionRegistry.registerNewSession(session.getId(), authentication.getPrincipal());
		return session;
	}

	private static MockHttpServletRequest requestOf(MockHttpSession session) {
		var request = new MockHttpServletRequest();
		request.setSession(session);
		request.setRequestedSessionId(session.getId());
		return request;
	}

	private static MockHttpServletRequest requestOfAnEndedSession() {
		var request = new MockHttpServletRequest();
		request.setRequestedSessionId("ended");
		request.setRequestedSessionIdValid(false);
		return request;
	}

	/**
	 * A request whose session a concurrent request invalidates right after each of its first {@code lookups} lookups,
	 * as Jetty returns it; later lookups find no session.
	 */
	private static MockHttpServletRequest requestWhoseSessionEndsAfterLookup(int lookups) {
		var session = loggedInSessionWithoutRegistration();
		session.invalidate();
		return new MockHttpServletRequest() {
			private int remaining = lookups;

			@Override
			public HttpSession getSession(boolean create) {
				return remaining-- > 0 ? session : null;
			}
		};
	}

	private static MockHttpSession loggedInSessionWithoutRegistration() {
		var session = new MockHttpSession();
		session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
				new SecurityContextImpl(formAuthentication()));
		return session;
	}

	/** Ends the session as a concurrent logout would, after the request has loaded its context. */
	private static Filter invalidating(MockHttpSession session) {
		return (request, response, chain) -> {
			SecurityContextHolder.getContext().getAuthentication();
			session.invalidate();
			chain.doFilter(request, response);
		};
	}

	private static AuthenticationManager authenticatingAs(Authentication authentication) {
		var manager = mock(AuthenticationManager.class);
		when(manager.authenticate(any())).thenReturn(authentication);
		return manager;
	}

	private static Authentication formAuthentication() {
		var principal = formPrincipal();
		return new UsernamePasswordAuthenticationToken(principal, "", principal.getAuthorities());
	}

	private static ESUserSessionDetails formPrincipal() {
		var springUser = new org.springframework.security.core.userdetails.User(
				USERNAME, "", List.of(new SimpleGrantedAuthority("ROLE_USER")));
		return new ESUserSessionDetails(springUser, mock(User.class),
				SessionInfo.builder().userName("alice").build());
	}

	private static OAuth2AuthenticationToken ssoAuthentication() {
		var authorities = List.of(new SimpleGrantedAuthority("OIDC_USER"));
		var user = new DefaultOAuth2User(authorities, Map.of("preferred_username", "alice"), "preferred_username");
		return new OAuth2AuthenticationToken(user, authorities, "keycloak");
	}

	/** Records whether and with which Authentication a request reaches the application. */
	private static class RecordingServlet extends HttpServlet {

		private boolean reached;
		private Authentication authentication;

		@Override
		protected void service(HttpServletRequest request, HttpServletResponse response) {
			reached = true;
			authentication = SecurityContextHolder.getContext().getAuthentication();
		}
	}
}
