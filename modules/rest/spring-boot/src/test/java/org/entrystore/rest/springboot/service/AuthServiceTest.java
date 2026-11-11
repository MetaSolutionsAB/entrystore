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

import com.github.benmanes.caffeine.cache.Ticker;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import org.apache.logging.log4j.Level;
import org.eclipse.rdf4j.model.Model;
import org.eclipse.rdf4j.model.impl.LinkedHashModel;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.entrystore.Entry;
import org.entrystore.GraphType;
import org.entrystore.PrincipalManager;
import org.entrystore.User;
import org.entrystore.impl.RepositoryManagerImpl;
import org.entrystore.repository.RepositoryException;
import org.entrystore.repository.security.Password;
import org.entrystore.repository.util.NS;
import org.entrystore.rest.springboot.configuration.AuthEmailExecutorConfiguration.QueuedDispatch;
import org.entrystore.rest.springboot.configuration.ConfirmationUrlProperties;
import org.entrystore.rest.springboot.model.api.PwResetRequestBody;
import org.entrystore.rest.springboot.model.api.SignupRequestBody;
import org.entrystore.rest.springboot.model.auth.SignupInfo;
import org.entrystore.rest.springboot.model.exception.BadRequestHtmlException;
import org.entrystore.rest.springboot.model.exception.CustomResponseException;
import org.entrystore.rest.springboot.model.exception.ExpectationFailedHtmlException;
import org.entrystore.rest.springboot.security.ESUserSessionDetails;
import org.entrystore.rest.springboot.service.auth.ConfirmationLinkBuilder;
import org.entrystore.rest.springboot.service.auth.EmailValidator;
import org.entrystore.rest.springboot.service.auth.PasswordResetRateLimiter;
import org.entrystore.rest.springboot.service.auth.RecaptchaSettings;
import org.entrystore.rest.springboot.service.auth.SignupRateLimiter;
import org.entrystore.rest.springboot.service.auth.SignupTokenCache;
import org.entrystore.rest.springboot.util.CapturingAppender;
import org.entrystore.rest.springboot.util.EmailSender;
import org.entrystore.rest.springboot.util.EmailSender.NoticeOutcome;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.http.HttpStatus;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.session.SessionInformation;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.core.session.SessionRegistryImpl;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.saml2.provider.service.authentication.DefaultSaml2AuthenticatedPrincipal;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

	private static final String ADA = "ada@example.org";
	private static final URI USER_URI = URI.create("https://example.org/store/_principals/resource/7");
	private static final URI OTHER_USER_URI = URI.create("https://example.org/store/_principals/resource/8");
	// Meets the default password rules and any plausible custom one: long, mixed case, digits, no whitespace.
	private static final String PASSWORD = "Secret-Pass12345";

	@Mock
	private AsyncTaskExecutor executor;

	private static ValidatorFactory validatorFactory;
	private static EmailValidator emailValidator;

	private MeterRegistry meterRegistry;
	private AuthService authService;

	@BeforeAll
	static void buildValidator() {
		validatorFactory = Validation.buildDefaultValidatorFactory();
		emailValidator = new EmailValidator(validatorFactory.getValidator());
	}

	@AfterAll
	static void closeFactory() {
		validatorFactory.close();
	}

	@BeforeEach
	void setUp() {
		meterRegistry = new SimpleMeterRegistry();
		authService = authServiceWithSessionRegistry(null);
	}

	@AfterEach
	void tearDown() {
		meterRegistry.close();
	}

	@ParameterizedTest(name = "{0}")
	@ValueSource(strings = {
			"user@localhost", "user@example.c", "user@example..com", "user@[999.999.999.999]"
	})
	void pwReset_invalidEmail_reportsTheRejectedAddress(String email) {
		var body = new PwResetRequestBody(email, null, null, null, null);

		// request is null: validateAndSetEmail runs before the rate limiter reads request.getRemoteAddr()
		BadRequestHtmlException thrown = assertThrows(BadRequestHtmlException.class,
				() -> authService.pwReset(null, body, "Password reset"));

		assertEquals("Invalid email address: " + email + ".", thrown.getMessage());
		assertEquals("Password reset", thrown.getTitle());
	}

	static Stream<Arguments> passwordsBreakingTheDefaultRules() {
		return Stream.of(
				Arguments.of("shorter than 10 characters", "Abcdefgh1"),
				Arguments.of("no number", "Abcdefghij"),
				// The rules apply to the trimmed value, which is the one that gets hashed.
				Arguments.of("long enough only with its padding", "  Abcdefg1   "),
				Arguments.of("longer than the maximum", "Aa1" + "a".repeat(Password.PASSWORD_MAX_LENGTH)));
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("passwordsBreakingTheDefaultRules")
	void pwReset_legacyModePasswordBreakingTheRules_isRejectedBeforeAnythingIsDispatched(String rule, String password) {
		ReflectionTestUtils.setField(authService, "confirmationLegacy", true);
		var body = new PwResetRequestBody(ADA, password, null, null, null);

		// request is null: the password is checked before the rate limiter reads request.getRemoteAddr()
		BadRequestHtmlException thrown = assertThrows(BadRequestHtmlException.class,
				() -> authService.pwReset(null, body, "Password reset"));

		assertEquals("The password must conform to the configured rules.", thrown.getMessage());
		verifyNoInteractions(executor);
	}

	@Test
	void confirmPassword_passwordBreakingTheRules_leavesTheTokenAndItsAttemptsUntouched() throws Exception {
		SignupTokenCache tokens = new SignupTokenCache(Ticker.systemTicker());
		SignupInfo pending = new SignupInfo();
		pending.setEmail(ADA);
		pending.setExpirationDate(new Date(System.currentTimeMillis() + Duration.ofDays(1).toMillis()));
		tokens.putToken("token", pending);
		AuthService service = passwordResetService(null, tokens);

		BadRequestHtmlException thrown = assertThrows(BadRequestHtmlException.class,
				() -> service.confirmPassword(new MockHttpServletRequest(), "token", ADA, "Abcdefgh1",
						"Password reset"));

		assertEquals("The password must conform to the configured rules.", thrown.getMessage());
		assertSame(pending, tokens.getTokenValue("token"));
		assertEquals(0, pending.getConfirmationAttempts());
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
	void submitPasswordResetDispatch_confirmationNotSent_removesTheTokenAndCountsAPasswordResetFailure()
			throws Exception {
		SignupTokenCache tokens = new SignupTokenCache(Ticker.systemTicker());
		EmailSender emailSender = mock(EmailSender.class);
		AtomicReference<String> link = new AtomicReference<>();
		when(emailSender.sendPasswordResetConfirmation(eq(ADA), anyString())).thenAnswer(invocation -> {
			link.set(invocation.getArgument(1));
			return false;
		});
		SignupInfo ci = new SignupInfo();
		ci.setEmail(ADA);
		ci.setExpirationDate(new Date(System.currentTimeMillis() + Duration.ofDays(1).toMillis()));

		passwordResetService(emailSender, tokens).submitPasswordResetDispatch(ci, PASSWORD);

		assertNull(tokens.getTokenValue(tokenOf(link.get())));
		assertEquals(1.0, meterRegistry.counter("auth.pwreset.failed").count());
		assertEquals(0.0, meterRegistry.counter("auth.signup.failed").count());
	}

	@Test
	void expireUserSessions_noExceptSession_expiresEveryLoginOfTheUserOnly() {
		SessionRegistryImpl sessions = new SessionRegistryImpl();
		AuthService service = sessionExpiryService(principalManagerNaming(USER_URI, "alice"), sessions);
		sessions.registerNewSession("form", formLogin(USER_URI));
		// The SSO login matches the user's name case-insensitively
		sessions.registerNewSession("cas", springUser("ALICE"));
		sessions.registerNewSession("saml", new DefaultSaml2AuthenticatedPrincipal("alice", Map.of()));
		sessions.registerNewSession("oidc", oidcLogin("Alice"));
		sessions.registerNewSession("other-form", formLogin(OTHER_USER_URI));
		sessions.registerNewSession("other-saml", new DefaultSaml2AuthenticatedPrincipal("bob", Map.of()));

		service.expireUserSessions(userAt(USER_URI), null);

		assertEquals(Set.of("form", "cas", "saml", "oidc"), expiredSessionIds(sessions));
	}

	@Test
	void expireUserSessions_exceptSession_sparesOnlyThatSession() {
		SessionRegistryImpl sessions = new SessionRegistryImpl();
		AuthService service = sessionExpiryService(principalManagerNaming(USER_URI, "alice"), sessions);
		sessions.registerNewSession("form", formLogin(USER_URI));
		// The two SAML logins are one registry principal, so only the session id tells them apart
		sessions.registerNewSession("saml-current", new DefaultSaml2AuthenticatedPrincipal("alice", Map.of()));
		sessions.registerNewSession("saml-other", new DefaultSaml2AuthenticatedPrincipal("alice", Map.of()));

		service.expireUserSessions(userAt(USER_URI), "saml-current");

		assertEquals(Set.of("form", "saml-other"), expiredSessionIds(sessions));
	}

	@Test
	void expireUserSessions_callerWithoutReadAccessToTheUser_readsTheNameAsAdmin() {
		SessionRegistryImpl sessions = new SessionRegistryImpl();
		PrincipalManager principalManager = principalManagerWithAdmin();
		URI adminUri = principalManager.getAdminUser().getURI();
		AtomicReference<URI> actingUser = new AtomicReference<>(OTHER_USER_URI);
		when(principalManager.getAuthenticatedUserURI()).thenAnswer(_ -> actingUser.get());
		doAnswer(call -> {
			actingUser.set(call.getArgument(0));
			return null;
		}).when(principalManager).setAuthenticatedUserURI(any());
		// Stands in for the core's ReadMetadata check, which only the admin passes here
		when(principalManager.getPrincipalName(USER_URI)).thenAnswer(_ -> {
			if (!adminUri.equals(actingUser.get())) {
				throw new IllegalStateException("name read as " + actingUser.get());
			}
			return "alice";
		});
		AuthService service = sessionExpiryService(principalManager, sessions);
		sessions.registerNewSession("saml", new DefaultSaml2AuthenticatedPrincipal("alice", Map.of()));

		service.expireUserSessions(userAt(USER_URI), null);

		assertEquals(Set.of("saml"), expiredSessionIds(sessions));
		assertEquals(OTHER_USER_URI, actingUser.get());
	}

	@Test
	void expireUserSessions_nameCannotBeRead_stillExpiresTheFormLoginSessions() {
		SessionRegistryImpl sessions = new SessionRegistryImpl();
		PrincipalManager principalManager = principalManagerWithAdmin();
		when(principalManager.getPrincipalName(USER_URI)).thenThrow(new RepositoryException("unreadable"));
		AuthService service = sessionExpiryService(principalManager, sessions);
		sessions.registerNewSession("form", formLogin(USER_URI));
		sessions.registerNewSession("saml", new DefaultSaml2AuthenticatedPrincipal("alice", Map.of()));

		assertThrows(RepositoryException.class, () -> service.expireUserSessions(userAt(USER_URI), null));

		// Matched by resource URI, before the name is read
		assertEquals(Set.of("form"), expiredSessionIds(sessions));
	}

	@Test
	void expireUserSessions_userWithoutName_expiresTheFormLoginSessionsOnly() {
		SessionRegistryImpl sessions = new SessionRegistryImpl();
		AuthService service = sessionExpiryService(principalManagerNaming(USER_URI, null), sessions);
		sessions.registerNewSession("form", formLogin(USER_URI));
		sessions.registerNewSession("other-cas", springUser("bob"));
		sessions.registerNewSession("other-saml", new DefaultSaml2AuthenticatedPrincipal("bob", Map.of()));

		service.expireUserSessions(userAt(USER_URI), null);

		assertEquals(Set.of("form"), expiredSessionIds(sessions));
	}

	@Test
	void expireUserSessions_nameDifferingOnlyByADotlessI_isAnotherUsersLogin() {
		SessionRegistryImpl sessions = new SessionRegistryImpl();
		// The login resolves the lower-cased name exactly, and lower-casing keeps the dotless i apart from i
		AuthService service = sessionExpiryService(principalManagerNaming(USER_URI, "al\u0131ce"), sessions);
		sessions.registerNewSession("other-cas", springUser("alice"));
		sessions.registerNewSession("other-saml", new DefaultSaml2AuthenticatedPrincipal("alice", Map.of()));

		service.expireUserSessions(userAt(USER_URI), null);

		assertEquals(Set.of(), expiredSessionIds(sessions));
	}

	@Test
	void expireFormLoginSessions_expiresTheFormLoginSessionsOfThatUserOnly() {
		SessionRegistryImpl sessions = new SessionRegistryImpl();
		AuthService service = authServiceWithSessionRegistry(sessions);
		sessions.registerNewSession("form-1", formLogin(USER_URI));
		sessions.registerNewSession("form-2", formLogin(USER_URI));
		sessions.registerNewSession("other-form", formLogin(OTHER_USER_URI));
		// Matched by resource URI alone, as a deleted user's name is gone
		sessions.registerNewSession("saml", new DefaultSaml2AuthenticatedPrincipal("alice", Map.of()));

		service.expireFormLoginSessions(USER_URI);

		assertEquals(Set.of("form-1", "form-2"), expiredSessionIds(sessions));
	}

	@Test
	void confirmPasswordLegacy_validToken_expiresEveryLoginOfTheUser() {
		SessionRegistryImpl sessions = new SessionRegistryImpl();
		SignupTokenCache tokens = new SignupTokenCache(Ticker.systemTicker());
		PrincipalManager principalManager = principalManagerNaming(USER_URI, ADA);
		AuthService service = confirmedResetService(principalManager, sessions, mock(EmailSender.class), tokens);
		Entry account = mock(Entry.class);
		User user = userAt(USER_URI);
		when(account.getResource()).thenReturn(user);
		when(user.setSaltedHashedSecret("salted-hash")).thenReturn(true);
		when(principalManager.getPrincipalEntry(ADA)).thenReturn(account);
		SignupInfo reset = new SignupInfo();
		reset.setEmail(ADA);
		reset.setSaltedHashedPassword("salted-hash");
		reset.setExpirationDate(new Date(System.currentTimeMillis() + Duration.ofDays(1).toMillis()));
		tokens.putToken("reset-token", reset);
		sessions.registerNewSession("form", formLogin(USER_URI));
		sessions.registerNewSession("saml", new DefaultSaml2AuthenticatedPrincipal(ADA, Map.of()));
		sessions.registerNewSession("other-form", formLogin(OTHER_USER_URI));

		String message = service.confirmPasswordLegacy("reset-token", "Password reset");

		assertEquals("Password reset was successful.", message);
		assertEquals(Set.of("form", "saml"), expiredSessionIds(sessions));
	}

	@Test
	@SuppressWarnings("unchecked")
	void constructor_signupWhitelist_isLowerCasedSoMixedCaseConfigStillMatches() {
		// The domain comparison at the end of signup() is an exact Set lookup against a lower-cased
		// email domain, so a whitelist entry configured as "Example.COM" would reject every
		// alice@example.com sign-up if this normalisation were dropped. Asserted on the field because
		// the comparison itself sits deep inside signup(), behind collaborators this test has no use for.
		AuthService service = new AuthService(null, null, null, null, null, null, null, emailValidator,
				null, null, null, null, null, meterRegistry,
				new MockEnvironment()
						.withProperty("entrystore.auth.signup.whitelist.1", "Example.COM")
						.withProperty("entrystore.auth.signup.whitelist.2", "OTHER.example.org"), executor, executor);

		var whitelist = (Set<String>) ReflectionTestUtils.getField(service, "domainWhitelist");

		assertEquals(Set.of("example.com", "other.example.org"), whitelist);
	}

	@Test
	void signup_newAddress_storesTheTokenBeforeTheConfirmationLinkIsSent() throws Exception {
		SignupTokenCache tokens = new SignupTokenCache(Ticker.systemTicker());
		EmailSender emailSender = mock(EmailSender.class);
		AtomicReference<SignupInfo> storedWhenSent = new AtomicReference<>();
		when(emailSender.sendSignupConfirmation(eq("Ada Lovelace"), eq(ADA), anyString())).thenAnswer(invocation -> {
			storedWhenSent.set(tokens.getTokenValue(tokenOf(invocation.getArgument(2))));
			return true;
		});
		AuthService service = signupService(principalManagerWithAdmin(), emailSender, tokens, Runnable::run);

		String message = service.signup(new MockHttpServletRequest(), signupBody(ADA), "Sign-up");

		assertEquals("A confirmation message was sent to " + ADA, message);
		assertNotNull(storedWhenSent.get(), "the link must work by the time the mail arrives");
		assertTrue(Password.check(PASSWORD, storedWhenSent.get().getSaltedHashedPassword()));
	}

	@Test
	void signup_newAddress_answersBeforeHashingStoringOrSendingAnything() throws Exception {
		PrincipalManager principalManager = principalManagerWithAdmin();
		SignupTokenCache tokens = new SignupTokenCache(Ticker.systemTicker());
		EmailSender emailSender = mock(EmailSender.class);
		when(emailSender.sendSignupConfirmation(eq("Ada Lovelace"), eq(ADA), anyString())).thenReturn(true);
		AuthService service = signupService(principalManager, emailSender, tokens, executor);

		String message;
		try (MockedStatic<Password> password = mockStatic(Password.class, CALLS_REAL_METHODS)) {
			message = service.signup(new MockHttpServletRequest(), signupBody(ADA), "Sign-up");
			password.verify(() -> Password.getSaltedHash(anyString()), never());
		}

		assertEquals("A confirmation message was sent to " + ADA, message);
		verifyNoInteractions(emailSender);
		verify(principalManager, never()).getPrincipalEntry(any());
		assertEquals(0, tokens.caffeineCaches().get("signup-tokens").estimatedSize());
		ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
		verify(executor).execute(task.capture());

		task.getValue().run();

		ArgumentCaptor<String> link = ArgumentCaptor.forClass(String.class);
		verify(emailSender).sendSignupConfirmation(eq("Ada Lovelace"), eq(ADA), link.capture());
		assertTrue(Password.check(PASSWORD, tokens.getTokenValue(tokenOf(link.getValue())).getSaltedHashedPassword()));
	}

	@Test
	void signup_existingAccount_answersBeforeLookingUpTheAccountOrSendingTheNotice() throws Exception {
		PrincipalManager principalManager = principalManagerWithAdmin();
		Entry account = userEntryNamed("Ada", "Lovelace");
		User user = (User) account.getResource();
		when(principalManager.getPrincipalEntry(ADA)).thenReturn(account);
		EmailSender emailSender = mock(EmailSender.class);
		when(emailSender.sendExistingAccountNotice("Ada Lovelace", ADA)).thenReturn(NoticeOutcome.SENT);
		AuthService service = signupService(principalManager, emailSender,
				new SignupTokenCache(Ticker.systemTicker()), executor);

		String message = service.signup(new MockHttpServletRequest(), signupBody(ADA), "Sign-up");

		assertEquals("A confirmation message was sent to " + ADA, message);
		verifyNoInteractions(emailSender);
		verify(principalManager, never()).getPrincipalEntry(any());
		verify(user, never()).isDisabled();
		ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
		verify(executor).execute(task.capture());

		task.getValue().run();

		verify(emailSender).sendExistingAccountNotice("Ada Lovelace", ADA);
	}

	@Test
	void signup_confirmationNotSent_removesTheTokenAndStillAnswersAsForASentOne() throws Exception {
		SignupTokenCache tokens = new SignupTokenCache(Ticker.systemTicker());
		EmailSender emailSender = mock(EmailSender.class);
		AtomicReference<String> link = new AtomicReference<>();
		when(emailSender.sendSignupConfirmation(eq("Ada Lovelace"), eq(ADA), anyString())).thenAnswer(invocation -> {
			link.set(invocation.getArgument(2));
			return false;
		});
		AuthService service = signupService(principalManagerWithAdmin(), emailSender, tokens, Runnable::run);

		String message = service.signup(new MockHttpServletRequest(), signupBody(ADA), "Sign-up");

		assertEquals("A confirmation message was sent to " + ADA, message);
		assertNull(tokens.getTokenValue(tokenOf(link.get())));
	}

	@Test
	void signup_existingAccountByName_sendsTheNoticeWithTheStoredNameAndStoresNoToken() throws Exception {
		PrincipalManager principalManager = principalManagerWithAdmin();
		Entry account = userEntryNamed("Ada", "Lovelace");
		when(principalManager.getPrincipalEntry(ADA)).thenReturn(account);
		SignupTokenCache tokens = new SignupTokenCache(Ticker.systemTicker());
		EmailSender emailSender = mock(EmailSender.class);
		when(emailSender.sendExistingAccountNotice("Ada Lovelace", ADA)).thenReturn(NoticeOutcome.SENT);
		AuthService service = signupService(principalManager, emailSender, tokens, Runnable::run);

		String message = service.signup(new MockHttpServletRequest(), signupBody(ADA, "Mallory", "Someone"), "Sign-up");

		assertEquals("A confirmation message was sent to " + ADA, message);
		verify(emailSender).sendExistingAccountNotice("Ada Lovelace", ADA);
		verify(emailSender, never()).sendSignupConfirmation(any(), any(), any());
		assertEquals(0, tokens.caffeineCaches().get("signup-tokens").estimatedSize());
	}

	@Test
	void signup_existingAccountWithoutName_greetsWithTheAddress() throws Exception {
		PrincipalManager principalManager = principalManagerWithAdmin();
		Entry account = mock(Entry.class);
		User user = mock(User.class);
		when(account.getGraphType()).thenReturn(GraphType.User);
		when(account.getResource()).thenReturn(user);
		when(user.getEntry()).thenReturn(account);
		when(principalManager.getPrincipalEntry(ADA)).thenReturn(account);
		EmailSender emailSender = mock(EmailSender.class);
		when(emailSender.sendExistingAccountNotice(ADA, ADA)).thenReturn(NoticeOutcome.SENT);
		AuthService service = signupService(principalManager, emailSender,
				new SignupTokenCache(Ticker.systemTicker()), Runnable::run);

		service.signup(new MockHttpServletRequest(), signupBody(ADA), "Sign-up");

		verify(emailSender).sendExistingAccountNotice(ADA, ADA);
		verify(emailSender, never()).sendSignupConfirmation(any(), any(), any());
	}

	@Test
	void signup_principalNameOfAGroup_isNotAnAccountAndGetsTheConfirmationLink() throws Exception {
		PrincipalManager principalManager = principalManagerWithAdmin();
		Entry group = mock(Entry.class);
		when(group.getGraphType()).thenReturn(GraphType.Group);
		when(principalManager.getPrincipalEntry(ADA)).thenReturn(group);
		EmailSender emailSender = mock(EmailSender.class);
		AuthService service = signupService(principalManager, emailSender,
				new SignupTokenCache(Ticker.systemTicker()), Runnable::run);

		service.signup(new MockHttpServletRequest(), signupBody(ADA), "Sign-up");

		verify(emailSender).sendSignupConfirmation(eq("Ada Lovelace"), eq(ADA), anyString());
		verify(emailSender, never()).sendExistingAccountNotice(any(), any());
	}

	@Test
	void signup_confirmationNotSent_isCountedAsFailed() throws Exception {
		EmailSender emailSender = mock(EmailSender.class);
		when(emailSender.sendSignupConfirmation(eq("Ada Lovelace"), eq(ADA), anyString())).thenReturn(false);
		AuthService service = signupService(principalManagerWithAdmin(), emailSender,
				new SignupTokenCache(Ticker.systemTicker()), Runnable::run);

		service.signup(new MockHttpServletRequest(), signupBody(ADA), "Sign-up");

		assertEquals(1.0, meterRegistry.counter("auth.signup.failed").count());
	}

	@Test
	void signup_existingAccountNoticeNotSent_isCountedAsFailed() throws Exception {
		PrincipalManager principalManager = principalManagerWithAdmin();
		Entry account = userEntryNamed("Ada", "Lovelace");
		when(principalManager.getPrincipalEntry(ADA)).thenReturn(account);
		EmailSender emailSender = mock(EmailSender.class);
		when(emailSender.sendExistingAccountNotice("Ada Lovelace", ADA)).thenReturn(NoticeOutcome.FAILED);
		AuthService service = signupService(principalManager, emailSender,
				new SignupTokenCache(Ticker.systemTicker()), Runnable::run);

		service.signup(new MockHttpServletRequest(), signupBody(ADA), "Sign-up");

		assertEquals(1.0, meterRegistry.counter("auth.signup.failed").count());
	}

	@Test
	void signup_templateWithoutNoticeSection_isNoFailureAndIsNotWarnedPerSignup() throws Exception {
		PrincipalManager principalManager = principalManagerWithAdmin();
		Entry account = userEntryNamed("Ada", "Lovelace");
		when(principalManager.getPrincipalEntry(ADA)).thenReturn(account);
		EmailSender emailSender = mock(EmailSender.class);
		when(emailSender.sendExistingAccountNotice("Ada Lovelace", ADA)).thenReturn(NoticeOutcome.NO_NOTICE_SECTION);
		AuthService service = signupService(principalManager, emailSender,
				new SignupTokenCache(Ticker.systemTicker()), Runnable::run);

		try (CapturingAppender appender = CapturingAppender.attachTo(AuthService.class)) {
			service.signup(new MockHttpServletRequest(), signupBody(ADA), "Sign-up");

			assertEquals(0, appender.countAt(Level.WARN), "the renderer warns once, when it loads the template");
		}
		assertEquals(0.0, meterRegistry.counter("auth.signup.failed").count());
	}

	@Test
	void signup_existingAccountNoticeThrows_isCountedAsFailedAndAnsweredAsForASentMail() throws Exception {
		PrincipalManager principalManager = principalManagerWithAdmin();
		Entry account = userEntryNamed("Ada", "Lovelace");
		when(principalManager.getPrincipalEntry(ADA)).thenReturn(account);
		EmailSender emailSender = mock(EmailSender.class);
		when(emailSender.sendExistingAccountNotice("Ada Lovelace", ADA)).thenThrow(new IllegalStateException("boom"));
		AuthService service = signupService(principalManager, emailSender,
				new SignupTokenCache(Ticker.systemTicker()), Runnable::run);

		String message = service.signup(new MockHttpServletRequest(), signupBody(ADA), "Sign-up");

		assertEquals("A confirmation message was sent to " + ADA, message);
		assertEquals(1.0, meterRegistry.counter("auth.signup.failed").count());
	}

	@Test
	void signup_existingDisabledAccount_getsNoNoticeAndTheSameAnswer() throws Exception {
		PrincipalManager principalManager = principalManagerWithAdmin();
		Entry account = mock(Entry.class);
		User user = mock(User.class);
		when(account.getGraphType()).thenReturn(GraphType.User);
		when(account.getResource()).thenReturn(user);
		when(user.isDisabled()).thenReturn(true);
		when(principalManager.getPrincipalEntry(ADA)).thenReturn(account);
		SignupTokenCache tokens = new SignupTokenCache(Ticker.systemTicker());
		EmailSender emailSender = mock(EmailSender.class);
		AuthService service = signupService(principalManager, emailSender, tokens, Runnable::run);

		String message = service.signup(new MockHttpServletRequest(), signupBody(ADA), "Sign-up");

		assertEquals("A confirmation message was sent to " + ADA, message);
		verify(emailSender, never()).sendExistingAccountNotice(any(), any());
		verify(emailSender, never()).sendSignupConfirmation(any(), any(), any());
		assertEquals(0, tokens.caffeineCaches().get("signup-tokens").estimatedSize());
	}

	@Test
	void signup_domainOutsideTheWhitelist_isRejectedBeforeAnyMailIsDispatched() throws Exception {
		AuthService service = signupService(mock(PrincipalManager.class), mock(EmailSender.class),
				new SignupTokenCache(Ticker.systemTicker()), executor,
				new MockEnvironment().withProperty("entrystore.auth.signup.whitelist.1", "example.com"),
				new RecaptchaSettings(false, null, null), new SignupRateLimiter(0, Duration.ofHours(1), Ticker.systemTicker()));

		assertThrows(ExpectationFailedHtmlException.class,
				() -> service.signup(new MockHttpServletRequest(), signupBody(ADA), "Sign-up"));
		verify(executor, never()).execute(any());
	}

	@Test
	void signup_missingRecaptcha_isRejectedBeforeAnyMailIsDispatched() throws Exception {
		AuthService service = signupService(mock(PrincipalManager.class), mock(EmailSender.class),
				new SignupTokenCache(Ticker.systemTicker()), executor, new MockEnvironment(),
				new RecaptchaSettings(true, "site-key", "secret"),
				new SignupRateLimiter(0, Duration.ofHours(1), Ticker.systemTicker()));

		assertThrows(BadRequestHtmlException.class,
				() -> service.signup(new MockHttpServletRequest(), signupBody(ADA), "Sign-up"));
		verify(executor, never()).execute(any());
	}

	@Test
	void signup_overTheRateLimit_isRejectedBeforeItsMailIsDispatched() throws Exception {
		AuthService service = signupService(mock(PrincipalManager.class), mock(EmailSender.class),
				new SignupTokenCache(Ticker.systemTicker()), executor, new MockEnvironment(),
				new RecaptchaSettings(false, null, null), new SignupRateLimiter(1, Duration.ofHours(1), Ticker.systemTicker()));
		service.signup(new MockHttpServletRequest(), signupBody(ADA), "Sign-up");

		CustomResponseException thrown = assertThrows(CustomResponseException.class,
				() -> service.signup(new MockHttpServletRequest(), signupBody(ADA), "Sign-up"));

		assertEquals(HttpStatus.TOO_MANY_REQUESTS, thrown.getStatus());
		verify(executor, times(1)).execute(any());
	}

	@Test
	void signup_rejectedExecution_incrementsTheCounterAndStillAnswersAsForASentMail() throws Exception {
		doThrow(new RejectedExecutionException("queue saturated")).when(executor).execute(any());
		AuthService service = signupService(mock(PrincipalManager.class), mock(EmailSender.class),
				new SignupTokenCache(Ticker.systemTicker()), executor);

		String message = service.signup(new MockHttpServletRequest(), signupBody(ADA), "Sign-up");

		assertEquals("A confirmation message was sent to " + ADA, message);
		assertEquals(1.0, meterRegistry.counter("auth.signup.rejected").count());
	}

	@Test
	void signup_dispatch_carriesTheRequestedAddressForTheShutdownLog() throws Exception {
		AuthService service = signupService(mock(PrincipalManager.class), mock(EmailSender.class),
				new SignupTokenCache(Ticker.systemTicker()), executor);

		service.signup(new MockHttpServletRequest(), signupBody(ADA), "Sign-up");

		ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
		verify(executor).execute(task.capture());
		assertEquals(ADA, assertInstanceOf(QueuedDispatch.class, task.getValue()).recipient());
	}

	@Test
	void submitPasswordResetDispatch_dispatch_carriesTheRequestedAddressForTheShutdownLog() {
		SignupInfo ci = new SignupInfo();
		ci.setEmail(ADA);

		authService.submitPasswordResetDispatch(ci, PASSWORD);

		ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
		verify(executor).execute(task.capture());
		assertEquals(ADA, assertInstanceOf(QueuedDispatch.class, task.getValue()).recipient());
	}

	@Test
	void signup_accountLookupFails_isCountedAsFailedAndAnsweredAsForASentMail() throws Exception {
		PrincipalManager principalManager = principalManagerWithAdmin();
		when(principalManager.getPrincipalEntry(ADA)).thenThrow(new RepositoryException("not a principal"));
		EmailSender emailSender = mock(EmailSender.class);
		AuthService service = signupService(principalManager, emailSender,
				new SignupTokenCache(Ticker.systemTicker()), Runnable::run);

		String message = service.signup(new MockHttpServletRequest(), signupBody(ADA), "Sign-up");

		assertEquals("A confirmation message was sent to " + ADA, message);
		assertEquals(1.0, meterRegistry.counter("auth.signup.failed").count());
		verifyNoInteractions(emailSender);
	}

	private static UserDetails springUser(String username) {
		return org.springframework.security.core.userdetails.User.withUsername(username).password("N/A").build();
	}

	private AuthService authServiceWithSessionRegistry(SessionRegistry sessionRegistry) {
		// Only the collaborators reached before pwReset's email check and by submitPasswordResetDispatch are real.
		return new AuthService(null, null, null, null, null, null, null, emailValidator,
				null, sessionRegistry, null, null, null, meterRegistry,
				new MockEnvironment(), executor, executor);
	}

	/** Only the collaborators signup() and its dispatches reach are set; reCAPTCHA and the rate limit are off. */
	private AuthService signupService(PrincipalManager principalManager, EmailSender emailSender,
									  SignupTokenCache tokens, AsyncTaskExecutor signupExecutor) throws Exception {
		return signupService(principalManager, emailSender, tokens, signupExecutor, new MockEnvironment(),
				new RecaptchaSettings(false, null, null), new SignupRateLimiter(0, Duration.ofHours(1), Ticker.systemTicker()));
	}

	private AuthService signupService(PrincipalManager principalManager, EmailSender emailSender,
									  SignupTokenCache tokens, AsyncTaskExecutor signupExecutor, MockEnvironment environment,
									  RecaptchaSettings recaptcha, SignupRateLimiter rateLimiter) throws Exception {
		return new AuthService(null, principalManager, null, null, recaptcha, tokens, null, emailValidator,
				emailSender, null, rateLimiter, null, confirmationLinks(), meterRegistry, environment, executor,
				signupExecutor);
	}

	/**
	 * Only the collaborators the password-reset dispatch and confirmPassword's password check reach are set; the
	 * dispatch runs on the calling thread and the rate limit is off.
	 */
	private AuthService passwordResetService(EmailSender emailSender, SignupTokenCache tokens) throws Exception {
		return new AuthService(null, null, null, null, null, tokens, null, emailValidator, emailSender, null, null,
				new PasswordResetRateLimiter(0, Duration.ofHours(1), Ticker.systemTicker()),
				confirmationLinks(), meterRegistry, new MockEnvironment(), Runnable::run, executor);
	}

	private static ConfirmationLinkBuilder confirmationLinks() throws Exception {
		RepositoryManagerImpl repositoryManager = mock(RepositoryManagerImpl.class);
		when(repositoryManager.getRepositoryURL()).thenReturn(URI.create("https://example.org/store/").toURL());
		return new ConfirmationLinkBuilder(new ConfirmationUrlProperties(null, null), repositoryManager);
	}

	private static PrincipalManager principalManagerWithAdmin() {
		PrincipalManager principalManager = mock(PrincipalManager.class);
		User admin = mock(User.class);
		when(admin.getURI()).thenReturn(URI.create("https://example.org/store/_principals/resource/_admin"));
		when(principalManager.getAdminUser()).thenReturn(admin);
		return principalManager;
	}

	private static Entry userEntryNamed(String givenName, String familyName) {
		URI resourceUri = URI.create("https://example.org/store/_principals/resource/7");
		var vf = SimpleValueFactory.getInstance();
		Model metadata = new LinkedHashModel();
		metadata.add(vf.createIRI(resourceUri.toString()), vf.createIRI(NS.foaf, "givenName"), vf.createLiteral(givenName));
		metadata.add(vf.createIRI(resourceUri.toString()), vf.createIRI(NS.foaf, "familyName"), vf.createLiteral(familyName));
		Entry entry = mock(Entry.class);
		User user = mock(User.class);
		when(entry.getGraphType()).thenReturn(GraphType.User);
		when(entry.getResource()).thenReturn(user);
		when(entry.getResourceURI()).thenReturn(resourceUri);
		when(entry.getMetadataGraph()).thenReturn(metadata);
		when(user.getEntry()).thenReturn(entry);
		return entry;
	}

	private static SignupRequestBody signupBody(String email) {
		return signupBody(email, "Ada", "Lovelace");
	}

	private static SignupRequestBody signupBody(String email, String firstName, String lastName) {
		return new SignupRequestBody(email, PASSWORD, firstName, lastName, null, null, null, Map.of());
	}

	private static String tokenOf(String confirmationLink) {
		return confirmationLink.substring(confirmationLink.indexOf("confirm=") + "confirm=".length());
	}

	/** Only the collaborators the session expiry reaches are set. */
	private AuthService sessionExpiryService(PrincipalManager principalManager, SessionRegistry sessions) {
		return confirmedResetService(principalManager, sessions, null, null);
	}

	/** Only the collaborators the confirmed password reset and its session expiry reach are set. */
	private AuthService confirmedResetService(PrincipalManager principalManager, SessionRegistry sessions,
											  EmailSender emailSender, SignupTokenCache tokens) {
		return new AuthService(null, principalManager, null, null, null, tokens, null, emailValidator, emailSender,
				sessions, null, null, null, meterRegistry, new MockEnvironment(), executor, executor);
	}

	/** A principal manager with an admin, in which the user at {@code userUri} has the given name. */
	private static PrincipalManager principalManagerNaming(URI userUri, String name) {
		PrincipalManager principalManager = principalManagerWithAdmin();
		when(principalManager.getPrincipalName(userUri)).thenReturn(name);
		return principalManager;
	}

	private static User userAt(URI userUri) {
		User user = mock(User.class);
		when(user.getURI()).thenReturn(userUri);
		return user;
	}

	private static ESUserSessionDetails formLogin(URI userUri) {
		return new ESUserSessionDetails(springUser(userUri.toString()), mock(User.class), null);
	}

	/** The principal UsernameClaimOidcUserService produces at login, named after the username claim. */
	private static DefaultOidcUser oidcLogin(String username) {
		var idToken = new OidcIdToken("id-token", Instant.now(), Instant.now().plusSeconds(60),
				Map.of("sub", "subject-" + username, "email", username));
		return new DefaultOidcUser(List.of(new SimpleGrantedAuthority("ROLE_USER")), idToken, "email");
	}

	private static Set<String> expiredSessionIds(SessionRegistry sessions) {
		return sessions.getAllPrincipals().stream()
				.flatMap(principal -> sessions.getAllSessions(principal, true).stream())
				.filter(SessionInformation::isExpired)
				.map(SessionInformation::getSessionId)
				.collect(Collectors.toSet());
	}
}
