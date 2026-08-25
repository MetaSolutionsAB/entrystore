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

package org.entrystore.rest.springboot.service;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.entrystore.Entry;
import org.entrystore.User;
import org.entrystore.rest.springboot.configuration.SignupWhitelistProperties;
import org.entrystore.rest.springboot.model.auth.SignupInfo;
import org.entrystore.rest.springboot.service.auth.EmailValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.security.core.session.SessionInformation;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.RejectedExecutionException;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

	@Mock
	private AsyncTaskExecutor executor;

	private MeterRegistry meterRegistry;
	private AuthService authService;

	@BeforeEach
	void setUp() {
		// Only meterRegistry and the executor are touched by submitPasswordResetDispatch, so the other
		// collaborators stay null — except the stateless EmailValidator, which is cheap to pass for
		// real, and the whitelist properties, which the constructor reads. The empty whitelist suits
		// this shared instance; the tests that need entries build their own via the same factory.
		meterRegistry = new SimpleMeterRegistry();
		authService = authServiceWithSessionRegistry(null);
	}

	@Test
	void submitPasswordResetDispatch_rejectedExecution_incrementsCounterAndSwallows() {
		// Pins the security contract of finding #6: a saturated queue (or shutting-down executor)
		// must (a) not propagate to the caller — otherwise the active-user branch surfaces 500 while
		// nonexistent/disabled stay 200, re-opening the enumeration oracle, and (b) increment the
		// Micrometer counter so operators can alert on sustained drops via monitoring instead of
		// log scraping.
		doThrow(new RejectedExecutionException("queue saturated")).when(executor).execute(any());
		SignupInfo ci = new SignupInfo();
		ci.setEmail("alice@example.com");

		assertDoesNotThrow(() -> authService.submitPasswordResetDispatch(ci, "anyPassword"));

		assertEquals(1.0, meterRegistry.counter("auth.pwreset.rejected").count(),
				"RejectedExecutionException must increment the auth.pwreset.rejected counter");
	}

	@Test
	void submitPasswordResetDispatch_happyPath_doesNotIncrementCounter() {
		// Negative: a normal execute() must not increment the rejection counter. A regression
		// that always increments would fire spurious alerts on every reset. Default Mockito stub
		// for execute() is a no-op, so the runnable is never run.
		SignupInfo ci = new SignupInfo();
		ci.setEmail("alice@example.com");

		assertDoesNotThrow(() -> authService.submitPasswordResetDispatch(ci, "anyPassword"));

		assertEquals(0.0, meterRegistry.counter("auth.pwreset.rejected").count(),
				"Happy path must not increment the rejection counter");
	}

	@Test
	void expireUserSessions_nullExceptSessionId_expiresAllSessions() {
		SessionRegistry sessionRegistry = mock(SessionRegistry.class);
		AuthService service = authServiceWithSessionRegistry(sessionRegistry);
		UserDetails principal = principalFor("http://example.com/_principals/resource/42");
		SessionInformation first = mock(SessionInformation.class);
		SessionInformation second = mock(SessionInformation.class);
		when(sessionRegistry.getAllPrincipals()).thenReturn(List.of(principal));
		when(sessionRegistry.getAllSessions(principal, false)).thenReturn(List.of(first, second));

		service.expireUserSessions(userWithResourceUri("http://example.com/_principals/resource/42"), null);

		verify(first).expireNow();
		verify(second).expireNow();
	}

	@Test
	void expireUserSessions_exceptSessionId_sparesThatSession() {
		SessionRegistry sessionRegistry = mock(SessionRegistry.class);
		AuthService service = authServiceWithSessionRegistry(sessionRegistry);
		UserDetails principal = principalFor("http://example.com/_principals/resource/42");
		SessionInformation current = mock(SessionInformation.class);
		SessionInformation other = mock(SessionInformation.class);
		when(current.getSessionId()).thenReturn("current-session");
		when(other.getSessionId()).thenReturn("other-session");
		when(sessionRegistry.getAllPrincipals()).thenReturn(List.of(principal));
		when(sessionRegistry.getAllSessions(principal, false)).thenReturn(List.of(current, other));

		service.expireUserSessions(userWithResourceUri("http://example.com/_principals/resource/42"), "current-session");

		verify(other).expireNow();
		verify(current, never()).expireNow();
	}

	@Test
	void expireUserSessions_noMatchingPrincipal_isNoop() {
		SessionRegistry sessionRegistry = mock(SessionRegistry.class);
		AuthService service = authServiceWithSessionRegistry(sessionRegistry);
		UserDetails otherPrincipal = principalFor("http://example.com/_principals/resource/somebody-else");
		when(sessionRegistry.getAllPrincipals()).thenReturn(List.of(otherPrincipal));

		service.expireUserSessions(userWithResourceUri("http://example.com/_principals/resource/42"), null);

		verify(sessionRegistry, never()).getAllSessions(any(), anyBoolean());
	}

	@Test
	@SuppressWarnings("unchecked")
	void constructor_signupWhitelist_isLowerCasedSoMixedCaseConfigStillMatches() {
		// The domain comparison at the end of signup() is an exact Set lookup against a lower-cased
		// email domain, so a whitelist entry configured as "Example.COM" would reject every
		// alice@example.com sign-up if this normalisation were dropped. Asserted on the field because
		// the comparison itself sits deep inside signup(), behind collaborators this test has no use for.
		AuthService service = new AuthService(null, null, null, null, null, null, new EmailValidator(),
				null, null, null, null, meterRegistry,
				new SignupWhitelistProperties(Map.of("1", "Example.COM", "2", "OTHER.example.org")), executor);

		var whitelist = (Set<String>) ReflectionTestUtils.getField(service, "domainWhitelist");

		assertEquals(Set.of("example.com", "other.example.org"), whitelist);
	}

	private AuthService authServiceWithSessionRegistry(SessionRegistry sessionRegistry) {
		return new AuthService(null, null, null, null, null, null, new EmailValidator(),
				null, sessionRegistry, null, null, meterRegistry,
				new SignupWhitelistProperties(Map.of()), executor);
	}

	private static UserDetails principalFor(String username) {
		UserDetails principal = mock(UserDetails.class);
		when(principal.getUsername()).thenReturn(username);
		return principal;
	}

	private static User userWithResourceUri(String resourceUri) {
		User user = mock(User.class);
		Entry entry = mock(Entry.class);
		when(user.getEntry()).thenReturn(entry);
		when(entry.getResourceURI()).thenReturn(URI.create(resourceUri));
		return user;
	}
}
