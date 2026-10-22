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
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.http.HttpStatus;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.session.SessionInformation;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.saml2.provider.service.authentication.DefaultSaml2AuthenticatedPrincipal;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.URI;
import java.time.Duration;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
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

		// request is null: validateAndSetEmail runs before clientIp(request)
		BadRequestHtmlException thrown = assertThrows(BadRequestHtmlException.class,
				() -> authService.pwReset(null, body, "Password reset"));

		assertEquals("Invalid email address: " + email + ".", thrown.getMessage());
		assertEquals("Password reset", thrown.getTitle());
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
		AuthService service = new AuthService(null, null, null, null, null, null, null, emailValidator,
				null, null, null, null, null, meterRegistry,
				new MockEnvironment()
						.withProperty("entrystore.auth.signup.whitelist.1", "Example.COM")
						.withProperty("entrystore.auth.signup.whitelist.2", "OTHER.example.org"), executor, executor);

		var whitelist = (Set<String>) ReflectionTestUtils.getField(service, "domainWhitelist");

		assertEquals(Set.of("example.com", "other.example.org"), whitelist);
	}

	@Test
	void expireAllSessions_expiresTheSessionsOfEveryLoginOfTheUserOnly() {
		SessionRegistry sessionRegistry = mock(SessionRegistry.class);
		PrincipalManager principalManager = mock(PrincipalManager.class);
		AuthService service = new AuthService(null, principalManager, null, null, null, null, null, emailValidator,
				null, sessionRegistry, null, null, null, meterRegistry, new MockEnvironment(), executor, executor);
		URI userUri = URI.create("https://example.org/store/_principals/resource/7");
		User user = mock(User.class);
		when(user.getURI()).thenReturn(userUri);
		when(principalManager.getPrincipalName(userUri)).thenReturn("alice");
		var formLogin = new ESUserSessionDetails(springUser(userUri.toString()), user, null);
		var casLogin = springUser("ALICE");
		var ssoLogin = new DefaultSaml2AuthenticatedPrincipal("alice", Map.of());
		var otherFormLogin = new ESUserSessionDetails(
				springUser("https://example.org/store/_principals/resource/8"), mock(User.class), null);
		var otherSsoLogin = new DefaultSaml2AuthenticatedPrincipal("bob", Map.of());
		var formSession = new SessionInformation(formLogin, "form", new Date());
		var casSession = new SessionInformation(casLogin, "cas", new Date());
		var ssoSession = new SessionInformation(ssoLogin, "sso", new Date());
		when(sessionRegistry.getAllPrincipals())
				.thenReturn(List.of(formLogin, otherFormLogin, casLogin, otherSsoLogin, ssoLogin));
		when(sessionRegistry.getAllSessions(formLogin, false)).thenReturn(List.of(formSession));
		when(sessionRegistry.getAllSessions(casLogin, false)).thenReturn(List.of(casSession));
		when(sessionRegistry.getAllSessions(ssoLogin, false)).thenReturn(List.of(ssoSession));

		service.expireAllSessions(user);

		assertTrue(formSession.isExpired());
		assertTrue(casSession.isExpired());
		assertTrue(ssoSession.isExpired());
		verify(sessionRegistry, never()).getAllSessions(otherFormLogin, false);
		verify(sessionRegistry, never()).getAllSessions(otherSsoLogin, false);
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
		verify(principalManager, never()).getUserByExternalID(any());
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
		verify(principalManager, never()).getUserByExternalID(any());
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
	void signup_existingAccountByName_stillRunsTheExternalIdLookup() throws Exception {
		PrincipalManager principalManager = principalManagerWithAdmin();
		Entry account = userEntryNamed("Ada", "Lovelace");
		when(principalManager.getPrincipalEntry(ADA)).thenReturn(account);
		EmailSender emailSender = mock(EmailSender.class);
		when(emailSender.sendExistingAccountNotice("Ada Lovelace", ADA)).thenReturn(NoticeOutcome.SENT);
		AuthService service = signupService(principalManager, emailSender,
				new SignupTokenCache(Ticker.systemTicker()), Runnable::run);

		service.signup(new MockHttpServletRequest(), signupBody(ADA), "Sign-up");

		// A new address runs both lookups, so an existing one does too: the lookup's store work does not depend on it.
		verify(principalManager).getUserByExternalID(ADA);
	}

	@Test
	void signup_existingAccountByExternalIdWithoutName_greetsWithTheAddress() throws Exception {
		PrincipalManager principalManager = principalManagerWithAdmin();
		User account = mock(User.class);
		when(account.getEntry()).thenReturn(mock(Entry.class));
		when(principalManager.getUserByExternalID(ADA)).thenReturn(account);
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

	/** Only the collaborators the password-reset dispatch reaches are set; it runs on the calling thread. */
	private AuthService passwordResetService(EmailSender emailSender, SignupTokenCache tokens) throws Exception {
		return new AuthService(null, null, null, null, null, tokens, null, emailValidator, emailSender, null, null, null,
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
