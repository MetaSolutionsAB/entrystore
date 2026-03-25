package org.entrystore.rest.springboot.service.auth;

import org.entrystore.Entry;
import org.entrystore.PrincipalManager;
import org.entrystore.config.Config;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.net.URI;
import java.time.Duration;

import static org.entrystore.repository.config.Settings.AUTH_TEMP_LOCKOUT_ADMIN;
import static org.entrystore.repository.config.Settings.AUTH_TEMP_LOCKOUT_DURATION;
import static org.entrystore.repository.config.Settings.AUTH_TEMP_LOCKOUT_MAX_ATTEMPTS;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LoginAttemptServiceTest {

	@Mock
	private Config config;

	@Mock
	private PrincipalManager pm;

	@Mock
	private Entry userEntry;

	private static final String USERNAME = "testuser";
	private static final URI USER_URI = URI.create("http://localhost/store/_principals/1");

	private LoginAttemptService service;

	private void initService(int maxAttempts, Duration duration, boolean includeAdmin) {
		when(config.getInt(AUTH_TEMP_LOCKOUT_MAX_ATTEMPTS, 5)).thenReturn(maxAttempts);
		when(config.getDuration(AUTH_TEMP_LOCKOUT_DURATION, Duration.ofMinutes(5))).thenReturn(duration);
		when(config.getBoolean(AUTH_TEMP_LOCKOUT_ADMIN, true)).thenReturn(includeAdmin);

		service = new LoginAttemptService(config, pm);
		service.init();
	}

	private void setupUserExists() {
		when(pm.getPrincipalEntry(USERNAME)).thenReturn(userEntry);
	}

	private void setupUserExistsAsAdmin() {
		setupUserExists();
		when(userEntry.getResourceURI()).thenReturn(USER_URI);
		when(pm.isUserAdminOrAdminGroup(USER_URI)).thenReturn(true);
	}

	@BeforeEach
	void setUp() {
		initService(3, Duration.ofMinutes(5), true);
	}

	@Test
	void belowMaxAttempts_notLockedOut() {
		setupUserExists();

		service.recordFailure(USERNAME);
		service.recordFailure(USERNAME);

		assertFalse(service.isLockedOut(USERNAME));
	}

	@Test
	void atMaxAttempts_lockedOut() {
		setupUserExists();

		service.recordFailure(USERNAME);
		service.recordFailure(USERNAME);
		service.recordFailure(USERNAME);

		assertTrue(service.isLockedOut(USERNAME));
		assertNotNull(service.getLockedUntil(USERNAME));
	}

	@Test
	void successAfterFailures_clearsCounter() {
		setupUserExists();

		service.recordFailure(USERNAME);
		service.recordFailure(USERNAME);

		service.recordSuccess(USERNAME);

		assertFalse(service.isLockedOut(USERNAME));
		assertNull(service.getLockedUntil(USERNAME));
	}

	@Test
	void lockoutExpiresAfterDuration() throws InterruptedException {
		initService(2, Duration.ofMillis(100), true);
		setupUserExists();

		service.recordFailure(USERNAME);
		service.recordFailure(USERNAME);

		assertTrue(service.isLockedOut(USERNAME));

		Thread.sleep(150);

		assertFalse(service.isLockedOut(USERNAME));
	}

	@Test
	void negativeMaxAttempts_disablesLockout() {
		initService(-1, Duration.ofMinutes(5), true);

		for (int i = 0; i < 10; i++) {
			service.recordFailure(USERNAME);
		}

		assertFalse(service.isLockedOut(USERNAME));
	}

	@Test
	void zeroMaxAttempts_disablesLockout() {
		initService(0, Duration.ofMinutes(5), true);

		for (int i = 0; i < 10; i++) {
			service.recordFailure(USERNAME);
		}

		assertFalse(service.isLockedOut(USERNAME));
	}

	@Test
	void zeroDuration_disablesLockout() {
		initService(3, Duration.ZERO, true);

		for (int i = 0; i < 10; i++) {
			service.recordFailure(USERNAME);
		}

		assertFalse(service.isLockedOut(USERNAME));
	}

	@Test
	void adminExemptWhenIncludeAdminFalse() {
		initService(3, Duration.ofMinutes(5), false);
		setupUserExistsAsAdmin();

		for (int i = 0; i < 5; i++) {
			service.recordFailure(USERNAME);
		}

		assertFalse(service.isLockedOut(USERNAME));
	}

	@Test
	void adminLockedWhenIncludeAdminTrue() {
		setupUserExists();

		service.recordFailure(USERNAME);
		service.recordFailure(USERNAME);
		service.recordFailure(USERNAME);

		assertTrue(service.isLockedOut(USERNAME));
	}

	@Test
	void nonExistentUser_noLockoutEntryCreated() {
		when(pm.getPrincipalEntry("ghost")).thenReturn(null);

		service.recordFailure("ghost");

		assertFalse(service.isLockedOut("ghost"));
	}

	@Test
	void counterResetsAfterLockoutExpires() throws InterruptedException {
		initService(2, Duration.ofMillis(100), true);
		setupUserExists();

		service.recordFailure(USERNAME);
		service.recordFailure(USERNAME);
		assertTrue(service.isLockedOut(USERNAME));

		Thread.sleep(150);
		assertFalse(service.isLockedOut(USERNAME));

		// After expiry, one failure should not lock out again
		service.recordFailure(USERNAME);
		assertFalse(service.isLockedOut(USERNAME));

		// Second failure should lock out again
		service.recordFailure(USERNAME);
		assertTrue(service.isLockedOut(USERNAME));
	}
}
