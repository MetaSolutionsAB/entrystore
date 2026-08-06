package org.entrystore.rest.springboot.service.auth;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.entrystore.Entry;
import org.entrystore.PrincipalManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.net.URI;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LoginAttemptServiceTest {

	@Mock
	private PrincipalManager pm;

	@Mock
	private Entry userEntry;

	private static final String USERNAME = "testuser";
	private static final URI USER_URI = URI.create("http://localhost/store/_principals/1");

	private LoginAttemptService service;

	private void initService(int maxAttempts, Duration duration, boolean includeAdmin) {
		service = new LoginAttemptService(pm, new SimpleMeterRegistry(), maxAttempts, duration, includeAdmin);
		service.init();
	}

	private void setupUserExistsAsAdmin() {
		when(pm.getPrincipalEntry(USERNAME)).thenReturn(userEntry);
		when(userEntry.getResourceURI()).thenReturn(USER_URI);
		when(pm.isUserAdminOrAdminGroup(USER_URI)).thenReturn(true);
	}

	@BeforeEach
	void setUp() {
		// With includeAdmin=true (the default) the service never consults PrincipalManager, so most
		// tests do not need to stub pm. Only adminExemptWhenIncludeAdminFalse exercises that path.
		initService(3, Duration.ofMinutes(5), true);
	}

	@Test
	void belowMaxAttempts_notLockedOut() {
		service.recordFailure(USERNAME);
		service.recordFailure(USERNAME);

		assertFalse(service.isLockedOut(USERNAME));
	}

	@Test
	void atMaxAttempts_lockedOut() {
		service.recordFailure(USERNAME);
		service.recordFailure(USERNAME);
		service.recordFailure(USERNAME);

		assertTrue(service.isLockedOut(USERNAME));
		assertNotNull(service.getLockedUntil(USERNAME));
	}

	@Test
	void successAfterFailures_clearsCounter() {
		service.recordFailure(USERNAME);
		service.recordFailure(USERNAME);

		service.recordSuccess(USERNAME);

		assertFalse(service.isLockedOut(USERNAME));
		assertNull(service.getLockedUntil(USERNAME));
	}

	@Test
	void lockoutExpiresAfterDuration() throws InterruptedException {
		initService(2, Duration.ofMillis(100), true);

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
		service.recordFailure(USERNAME);
		service.recordFailure(USERNAME);
		service.recordFailure(USERNAME);

		assertTrue(service.isLockedOut(USERNAME));
	}

	@Test
	void nonExistentUser_lockoutAppliesUniformly() {
		// Nonexistent usernames are tracked the same way as known users so the absence of a 429
		// response cannot be used to enumerate accounts.
		service.recordFailure("ghost");
		service.recordFailure("ghost");
		service.recordFailure("ghost");

		assertTrue(service.isLockedOut("ghost"));
	}

	@Test
	void overlyLongUsername_ignored() {
		String huge = "x".repeat(257);

		service.recordFailure(huge);
		service.recordFailure(huge);
		service.recordFailure(huge);

		assertFalse(service.isLockedOut(huge));
	}

	@Test
	void nullUsername_doesNotThrow() {
		service.recordFailure(null);
		service.recordSuccess(null);

		assertFalse(service.isLockedOut(null));
	}

	@Test
	void counterResetsAfterLockoutExpires() throws InterruptedException {
		initService(2, Duration.ofMillis(100), true);

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

	@Test
	void lockoutSurvivesCounterCacheFlood() {
		// Pins the central invariant of the counter / lockout split: a flood of failures against
		// unique junk usernames evicts entries from the counter cache (safe — eviction only resets
		// the failure count to zero), but must NOT evict an already-locked entry. A regression that
		// re-merges the two stores would silently reopen the cache-eviction "unlock" attack.
		initService(3, Duration.ofMinutes(5), true);

		service.recordFailure(USERNAME);
		service.recordFailure(USERNAME);
		service.recordFailure(USERNAME);
		assertTrue(service.isLockedOut(USERNAME));

		// 100_000 is MAX_TRACKED_USERNAMES — flooding past the cap forces Caffeine to evict
		// counter entries, but the locked entry lives in a separate cache and must persist.
		for (long i = 0; i < 100_001L; i++) {
			service.recordFailure("flood-" + i);
		}

		assertTrue(service.isLockedOut(USERNAME),
				"Lockout must persist even after the counter cache evicts the victim's key");
	}

	@Test
	void postExpiryGetsFullFreshBudget() throws InterruptedException {
		// After a lockout expires, the user must get a full maxAttempts-sized fresh budget of
		// failures — not just one before re-lockout. A regression that fails to invalidate the
		// counter when migrating to the lockout cache would leave a stale counter that re-locks on
		// the very next failure.
		initService(3, Duration.ofMillis(100), true);

		service.recordFailure(USERNAME);
		service.recordFailure(USERNAME);
		service.recordFailure(USERNAME);
		assertTrue(service.isLockedOut(USERNAME));

		Thread.sleep(150);
		assertFalse(service.isLockedOut(USERNAME));

		// First two failures after expiry must NOT re-lock.
		service.recordFailure(USERNAME);
		assertFalse(service.isLockedOut(USERNAME));
		service.recordFailure(USERNAME);
		assertFalse(service.isLockedOut(USERNAME));

		// Third failure re-locks.
		service.recordFailure(USERNAME);
		assertTrue(service.isLockedOut(USERNAME));
	}

	@Test
	void recordSuccessClearsActiveLockout() {
		// recordSuccess must invalidate BOTH the counter and the active lockout entry, so an
		// out-of-band manual unlock (or a successful login at the threshold-tipping moment) does
		// not leave the user stuck behind a stale lockout.
		initService(3, Duration.ofMinutes(5), true);

		service.recordFailure(USERNAME);
		service.recordFailure(USERNAME);
		service.recordFailure(USERNAME);
		assertTrue(service.isLockedOut(USERNAME));

		service.recordSuccess(USERNAME);

		assertFalse(service.isLockedOut(USERNAME));
		assertNull(service.getLockedUntil(USERNAME));
	}

}
