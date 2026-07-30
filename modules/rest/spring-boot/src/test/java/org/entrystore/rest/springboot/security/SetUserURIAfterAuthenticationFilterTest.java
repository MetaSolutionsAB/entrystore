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

import jakarta.servlet.http.HttpServletResponse;
import org.apereo.cas.client.validation.Assertion;
import org.entrystore.PrincipalManager;
import org.entrystore.User;
import org.entrystore.rest.springboot.model.auth.SessionInfo;
import org.entrystore.rest.springboot.util.ErrorResponseWriter;
import tools.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.cas.authentication.CasAuthenticationToken;
import org.springframework.security.core.AuthenticatedPrincipal;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.saml2.provider.service.authentication.Saml2Authentication;

import java.net.URI;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SetUserURIAfterAuthenticationFilterTest {

	private static final URI GUEST_URI = URI.create("urn:test:_principals/resource/_guest");
	private static final URI ALICE_URI = URI.create("urn:test:_principals/resource/alice");
	private static final URI BOB_URI = URI.create("urn:test:_principals/resource/bob");

	@Mock
	private PrincipalManager pm;

	@Mock
	private ESUserDetailsService userDetailsService;

	@Mock
	private User guestUser;

	@Mock
	private User aliceUser;

	@Mock
	private User bobUser;

	private SetUserURIAfterAuthenticationFilter filter;

	@BeforeEach
	void setUp() {
		filter = new SetUserURIAfterAuthenticationFilter(pm, userDetailsService,
				new ErrorResponseWriter(JsonMapper.builder().build()));
		// Tests that need a missing-guest scenario override pm.getGuestUser() explicitly;
		// keep these baseline stubs lenient so unused-stub failures don't mask the override.
		lenient().when(pm.getGuestUser()).thenReturn(guestUser);
		lenient().when(guestUser.getURI()).thenReturn(GUEST_URI);
	}

	@AfterEach
	void clearSecurityContext() {
		SecurityContextHolder.clearContext();
	}

	@Test
	void nullAuth_setsGuestURIAndCallsChain() throws Exception {
		SecurityContextHolder.clearContext();
		var chain = new MockFilterChain();

		filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), chain);

		verify(pm).setAuthenticatedUserURI(GUEST_URI);
		assertNotNull(chain.getRequest(), "Chain must be invoked for null-auth requests");
	}

	@Test
	void notAuthenticated_setsGuestURIAndCallsChain() throws Exception {
		Authentication auth = mock(Authentication.class);
		when(auth.isAuthenticated()).thenReturn(false);
		SecurityContextHolder.getContext().setAuthentication(auth);
		var chain = new MockFilterChain();

		filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), chain);

		verify(pm).setAuthenticatedUserURI(GUEST_URI);
		assertNotNull(chain.getRequest(), "Chain must be invoked when auth is not authenticated");
	}

	@Test
	void anonymousToken_setsGuestURIAndCallsChain() throws Exception {
		List<GrantedAuthority> roles = List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS"));
		SecurityContextHolder.getContext().setAuthentication(
				new AnonymousAuthenticationToken("key", "anonymousUser", roles));
		var chain = new MockFilterChain();

		filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), chain);

		verify(pm).setAuthenticatedUserURI(GUEST_URI);
		assertNotNull(chain.getRequest(), "Chain must be invoked for anonymous requests");
	}

	@Test
	void guestUserMissing_writes500WithoutTouchingChain() throws Exception {
		when(pm.getGuestUser()).thenReturn(null);
		SecurityContextHolder.clearContext();
		var request = new MockHttpServletRequest("GET", "/some/resource");
		var response = new MockHttpServletResponse();
		var chain = new MockFilterChain();

		filter.doFilter(request, response, chain);

		assertEquals(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, response.getStatus());
		assertEquals("application/json", response.getContentType());
		assertTrue(response.getContentAsString().contains("PrincipalManager is not initialized"),
				"500 body must name the unavailable-guest cause");
		assertNull(chain.getRequest(),
				"Chain must NOT be invoked when the PrincipalManager has no guest user");
	}

	@Test
	void guestUserHasNullURI_writes500WithoutTouchingChain() throws Exception {
		// Distinct from getGuestUser() == null: the User exists but its URI was never set.
		// Same 500 contract — both branches route through guestUserUri() and short-circuit
		// before touching the SecurityContext, so neither can leak a stale ThreadLocal.
		when(guestUser.getURI()).thenReturn(null);
		SecurityContextHolder.clearContext();
		var request = new MockHttpServletRequest("GET", "/some/resource");
		var response = new MockHttpServletResponse();
		var chain = new MockFilterChain();

		filter.doFilter(request, response, chain);

		assertEquals(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, response.getStatus());
		assertEquals("application/json", response.getContentType());
		assertTrue(response.getContentAsString().contains("PrincipalManager is not initialized"),
				"500 body must name the unavailable-guest cause");
		assertNull(chain.getRequest(),
				"Chain must NOT be invoked when the guest user has no URI");
	}

	@Test
	void samlAuthenticated_userFound_setsUserURIAndCallsChain() throws Exception {
		when(aliceUser.getURI()).thenReturn(ALICE_URI);
		when(userDetailsService.loadUser("alice")).thenReturn(aliceUser);
		SecurityContextHolder.getContext().setAuthentication(samlAuth("alice"));
		var chain = new MockFilterChain();

		filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), chain);

		InOrder order = inOrder(pm);
		order.verify(pm).setAuthenticatedUserURI(GUEST_URI);
		order.verify(pm).setAuthenticatedUserURI(ALICE_URI);
		assertNotNull(chain.getRequest(), "Chain must be invoked after a successful SAML user lookup");
	}

	@Test
	void samlAuthenticated_userNotFound_writes403AndStopsChain() throws Exception {
		when(userDetailsService.loadUser("ghost")).thenReturn(null);
		SecurityContextHolder.getContext().setAuthentication(samlAuth("ghost"));
		var request = new MockHttpServletRequest("GET", "/some/resource");
		request.getSession(true);
		var response = new MockHttpServletResponse();
		var chain = new MockFilterChain();

		filter.doFilter(request, response, chain);

		// Guest reset must happen before the chain is short-circuited; pinning this with InOrder
		// catches a regression that moves the reset below setForbiddenResponse.
		InOrder order = inOrder(pm);
		order.verify(pm).setAuthenticatedUserURI(GUEST_URI);
		order.verifyNoMoreInteractions();
		assertEquals(HttpServletResponse.SC_FORBIDDEN, response.getStatus());
		assertEquals("application/json", response.getContentType());
		assertTrue(response.getContentAsString().contains("SAML user not found"),
				"Forbidden body must name the SAML branch so operators can correlate the diagnostic");
		assertNull(SecurityContextHolder.getContext().getAuthentication(),
				"Security context must be cleared on SAML user-not-found");
		assertNull(chain.getRequest(),
				"Chain must NOT be invoked when SAML user is not found in EntryStore");
	}

	@Test
	void samlAuthenticated_userHasNoURI_writes403AndStopsChain() throws Exception {
		when(aliceUser.getURI()).thenReturn(null);
		when(userDetailsService.loadUser("alice")).thenReturn(aliceUser);
		SecurityContextHolder.getContext().setAuthentication(samlAuth("alice"));
		var request = new MockHttpServletRequest("GET", "/some/resource");
		var response = new MockHttpServletResponse();
		var chain = new MockFilterChain();

		filter.doFilter(request, response, chain);

		InOrder order = inOrder(pm);
		order.verify(pm).setAuthenticatedUserURI(GUEST_URI);
		order.verifyNoMoreInteractions();
		assertEquals(HttpServletResponse.SC_FORBIDDEN, response.getStatus());
		assertTrue(response.getContentAsString().contains("has no URI"),
				"Forbidden body must name the missing-URI cause so operators see why a found user was still denied");
		assertNull(chain.getRequest(),
				"Chain must NOT be invoked when the resolved user has no URI");
	}

	@Test
	void samlAuthenticated_loadUserThrows_writes500AndStopsChain() throws Exception {
		// Models a store-layer failure (e.g. RDF4J briefly unavailable). Without the try/catch
		// the exception would escape doFilterInternal — and because servlet filters run before
		// the DispatcherServlet, AppExceptionHandler cannot convert it into the JSON deny
		// contract. The filter must catch it, clear the session, and write a 500 itself.
		when(userDetailsService.loadUser("alice")).thenThrow(new RuntimeException("store unavailable"));
		SecurityContextHolder.getContext().setAuthentication(samlAuth("alice"));
		var request = new MockHttpServletRequest("GET", "/some/resource");
		request.getSession(true);
		var response = new MockHttpServletResponse();
		var chain = new MockFilterChain();

		filter.doFilter(request, response, chain);

		InOrder order = inOrder(pm);
		order.verify(pm).setAuthenticatedUserURI(GUEST_URI);
		order.verifyNoMoreInteractions();
		assertEquals(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, response.getStatus());
		assertEquals("application/json", response.getContentType());
		assertTrue(response.getContentAsString().contains("SAML user lookup failed"),
				"500 body must name the SAML branch so operators can correlate the diagnostic");
		assertNull(SecurityContextHolder.getContext().getAuthentication(),
				"Security context must be cleared on store-layer failure so a stuck token does not repeat the 500");
		assertNull(chain.getRequest(),
				"Chain must NOT be invoked when loadUser throws");
	}

	@Test
	void casAuthenticated_userFound_setsUserURIAndCallsChain() throws Exception {
		when(bobUser.getURI()).thenReturn(BOB_URI);
		when(userDetailsService.loadUser("bob")).thenReturn(bobUser);
		SecurityContextHolder.getContext().setAuthentication(casAuth("bob"));
		var chain = new MockFilterChain();

		filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), chain);

		InOrder order = inOrder(pm);
		order.verify(pm).setAuthenticatedUserURI(GUEST_URI);
		order.verify(pm).setAuthenticatedUserURI(BOB_URI);
		assertNotNull(chain.getRequest(), "Chain must be invoked after a successful CAS user lookup");
	}

	@Test
	void casAuthenticated_userNotFound_writes403AndStopsChain() throws Exception {
		when(userDetailsService.loadUser("ghost")).thenReturn(null);
		SecurityContextHolder.getContext().setAuthentication(casAuth("ghost"));
		var request = new MockHttpServletRequest("GET", "/some/resource");
		request.getSession(true);
		var response = new MockHttpServletResponse();
		var chain = new MockFilterChain();

		filter.doFilter(request, response, chain);

		InOrder order = inOrder(pm);
		order.verify(pm).setAuthenticatedUserURI(GUEST_URI);
		order.verifyNoMoreInteractions();
		assertEquals(HttpServletResponse.SC_FORBIDDEN, response.getStatus());
		assertEquals("application/json", response.getContentType());
		assertTrue(response.getContentAsString().contains("CAS user not found"),
				"Forbidden body must name the CAS branch so operators can correlate the diagnostic");
		assertNull(SecurityContextHolder.getContext().getAuthentication(),
				"Security context must be cleared on CAS user-not-found");
		assertNull(chain.getRequest(),
				"Chain must NOT be invoked when CAS user is not found in EntryStore");
	}

	@Test
	void cookieAuth_setsUserURIAndCallsChain() throws Exception {
		when(aliceUser.getURI()).thenReturn(ALICE_URI);
		var sessionDetails = newSessionDetails("alice", aliceUser);
		var token = new UsernamePasswordAuthenticationToken(
				sessionDetails, sessionDetails.getPassword(), sessionDetails.getAuthorities());
		SecurityContextHolder.getContext().setAuthentication(token);
		var chain = new MockFilterChain();

		filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), chain);

		InOrder order = inOrder(pm);
		order.verify(pm).setAuthenticatedUserURI(GUEST_URI);
		order.verify(pm).setAuthenticatedUserURI(ALICE_URI);
		assertNotNull(chain.getRequest(), "Chain must be invoked after a successful cookie-auth lookup");
	}

	@Test
	void cookieAuth_esUserMissing_writes403AndStopsChain() throws Exception {
		// Realistic scenario: the user was deleted between session creation and the next request,
		// leaving ESUserSessionDetails with a null inner User reference. Without an explicit branch
		// this would silently fall into "Unrecognized principal type" — wrong diagnostic.
		var sessionDetails = newSessionDetails("alice", null);
		var token = new UsernamePasswordAuthenticationToken(
				sessionDetails, sessionDetails.getPassword(), sessionDetails.getAuthorities());
		SecurityContextHolder.getContext().setAuthentication(token);
		var request = new MockHttpServletRequest("GET", "/some/resource");
		var response = new MockHttpServletResponse();
		var chain = new MockFilterChain();

		filter.doFilter(request, response, chain);

		InOrder order = inOrder(pm);
		order.verify(pm).setAuthenticatedUserURI(GUEST_URI);
		order.verifyNoMoreInteractions();
		assertEquals(HttpServletResponse.SC_FORBIDDEN, response.getStatus());
		assertTrue(response.getContentAsString().contains("Cookie-authenticated user no longer exists"),
				"Forbidden body must name the deleted-cookie-user cause, not the unrelated unrecognized-principal cause");
		assertNull(SecurityContextHolder.getContext().getAuthentication(),
				"Security context must be cleared when the cookie-auth user has no EntryStore record");
		assertNull(chain.getRequest(),
				"Chain must NOT be invoked when the cookie-auth user is gone");
	}

	@Test
	void unrecognizedPrincipalType_writes403AndCleansUpAndStillResetsToGuestFirst() throws Exception {
		Authentication auth = mock(Authentication.class);
		when(auth.isAuthenticated()).thenReturn(true);
		when(auth.getPrincipal()).thenReturn("not-a-known-principal-type");
		SecurityContextHolder.getContext().setAuthentication(auth);
		var request = new MockHttpServletRequest("GET", "/some/resource");
		request.getSession(true);
		var response = new MockHttpServletResponse();
		var chain = new MockFilterChain();

		filter.doFilter(request, response, chain);

		InOrder order = inOrder(pm);
		order.verify(pm).setAuthenticatedUserURI(GUEST_URI);
		order.verifyNoMoreInteractions();
		assertEquals(HttpServletResponse.SC_FORBIDDEN, response.getStatus());
		assertEquals("application/json", response.getContentType());
		assertTrue(response.getContentAsString().contains("Unrecognized principal type"),
				"Forbidden body must name the unrecognized-principal branch");
		assertNull(SecurityContextHolder.getContext().getAuthentication(),
				"Security context must be cleared so a stateful session does not repeat this 403 forever");
		assertNull(chain.getRequest(),
				"Chain must NOT be invoked when the principal type is unrecognized");
	}

	@Test
	void nullPrincipalOnAuthenticatedToken_writes403WithoutNPE() throws Exception {
		// Spring's Authentication contract permits authenticated == true with principal == null.
		// Without an explicit null-coalesce, log.warn would NPE inside the filter and bypass
		// AppExceptionHandler entirely.
		Authentication auth = mock(Authentication.class);
		when(auth.isAuthenticated()).thenReturn(true);
		when(auth.getPrincipal()).thenReturn(null);
		SecurityContextHolder.getContext().setAuthentication(auth);
		var request = new MockHttpServletRequest("GET", "/some/resource");
		var response = new MockHttpServletResponse();
		var chain = new MockFilterChain();

		filter.doFilter(request, response, chain);

		assertEquals(HttpServletResponse.SC_FORBIDDEN, response.getStatus());
		assertTrue(response.getContentAsString().contains("Unrecognized principal type"),
				"A null principal is treated as unrecognized — never NPE the filter");
		assertNull(chain.getRequest(),
				"Chain must NOT be invoked when the principal is null");
	}

	@Test
	void twoSuccessiveAuthenticatedRequests_secondRequestStartsFromGuest() throws Exception {
		// pm is shared between calls — it stands in for the per-thread state that a real
		// PrincipalManagerImpl keeps in a ThreadLocal. Both invocations run on the JUnit thread,
		// so the threading is not what is under test; what IS under test is that the filter sets
		// the guest URI on pm as its first interaction on each invocation, regardless of what the
		// previous invocation left on pm.
		when(aliceUser.getURI()).thenReturn(ALICE_URI);
		when(userDetailsService.loadUser("alice")).thenReturn(aliceUser);
		SecurityContextHolder.getContext().setAuthentication(samlAuth("alice"));
		filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), new MockFilterChain());

		// Second request: a different authenticated user. The filter must reset to guest BEFORE
		// overwriting with bob — otherwise bob's request would briefly observe alice's URI.
		when(bobUser.getURI()).thenReturn(BOB_URI);
		when(userDetailsService.loadUser("bob")).thenReturn(bobUser);
		SecurityContextHolder.getContext().setAuthentication(casAuth("bob"));
		filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), new MockFilterChain());

		InOrder order = inOrder(pm);
		// Request 1: reset to guest, then overwrite to alice.
		order.verify(pm).setAuthenticatedUserURI(GUEST_URI);
		order.verify(pm).setAuthenticatedUserURI(ALICE_URI);
		// Request 2: reset to guest (this is the contamination guard), then overwrite to bob.
		order.verify(pm).setAuthenticatedUserURI(GUEST_URI);
		order.verify(pm).setAuthenticatedUserURI(BOB_URI);
		order.verifyNoMoreInteractions();
	}

	private static Saml2Authentication samlAuth(String username) {
		var principal = mock(AuthenticatedPrincipal.class);
		when(principal.getName()).thenReturn(username);
		return new Saml2Authentication(principal, "<saml-response/>",
				List.of(new SimpleGrantedAuthority("ROLE_USER")));
	}

	private static CasAuthenticationToken casAuth(String username) {
		UserDetails userDetails = springUser(username);
		var assertion = mock(Assertion.class);
		return new CasAuthenticationToken("cas-key", userDetails, "ticket",
				List.of(new SimpleGrantedAuthority("ROLE_USER")), userDetails, assertion);
	}

	private static ESUserSessionDetails newSessionDetails(String username, User esUser) {
		var sessionInfo = SessionInfo.builder().userName(username).build();
		return new ESUserSessionDetails(springUser(username), esUser, sessionInfo);
	}

	private static UserDetails springUser(String username) {
		return new org.springframework.security.core.userdetails.User(
				username, "", List.of(new SimpleGrantedAuthority("ROLE_USER")));
	}
}
