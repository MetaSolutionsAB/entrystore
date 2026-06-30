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

package org.entrystore.rest.springboot.service.auth;

import org.entrystore.rest.springboot.model.auth.ConfirmAttemptResult;
import org.entrystore.rest.springboot.model.auth.SignupInfo;
import org.junit.jupiter.api.Test;

import java.util.Date;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class SignupTokenCacheTest {

	private static final Predicate<SignupInfo> ALWAYS_MATCH = info -> true;
	private static final Predicate<SignupInfo> NEVER_MATCH = info -> false;

	private SignupInfo pendingInfo() {
		SignupInfo info = new SignupInfo();
		info.setEmail("user@example.com");
		info.setExpirationDate(new Date(System.currentTimeMillis() + 3600_000));
		return info;
	}

	@Test
	void confirmAttempt_returnsValidAndConsumesToken_whenCredentialsMatch() {
		var cache = new SignupTokenCache();
		SignupInfo info = pendingInfo();
		cache.putToken("tok", info);

		ConfirmAttemptResult result = cache.confirmAttempt("tok", ALWAYS_MATCH, 3);

		assertEquals(ConfirmAttemptResult.Status.VALID, result.status());
		assertSame(info, result.info());
		assertNull(cache.getTokenValue("tok"), "a consumed token must be removed");
	}

	@Test
	void confirmAttempt_returnsTokenNotFound_forUnknownToken() {
		var cache = new SignupTokenCache();

		ConfirmAttemptResult result = cache.confirmAttempt("missing", ALWAYS_MATCH, 3);

		assertEquals(ConfirmAttemptResult.Status.TOKEN_NOT_FOUND, result.status());
	}

	@Test
	void confirmAttempt_returnsTokenNotFound_forNullToken() {
		var cache = new SignupTokenCache();
		cache.putToken("tok", pendingInfo());

		// A confirm request that omits the token field arrives as null; it must not throw.
		ConfirmAttemptResult result = cache.confirmAttempt(null, ALWAYS_MATCH, 3);

		assertEquals(ConfirmAttemptResult.Status.TOKEN_NOT_FOUND, result.status());
	}

	@Test
	void confirmAttempt_countsFailureAndKeepsToken_belowLimit() {
		var cache = new SignupTokenCache();
		SignupInfo info = pendingInfo();
		cache.putToken("tok", info);

		ConfirmAttemptResult first = cache.confirmAttempt("tok", NEVER_MATCH, 3);

		assertEquals(ConfirmAttemptResult.Status.INVALID_CREDENTIALS, first.status());
		assertEquals(2, first.remainingAttempts());
		assertEquals(1, info.getConfirmationAttempts());
		assertSame(info, cache.getTokenValue("tok"), "token must remain usable below the limit");

		ConfirmAttemptResult second = cache.confirmAttempt("tok", NEVER_MATCH, 3);

		assertEquals(ConfirmAttemptResult.Status.INVALID_CREDENTIALS, second.status());
		assertEquals(1, second.remainingAttempts());
	}

	@Test
	void confirmAttempt_invalidatesToken_whenLimitReached() {
		var cache = new SignupTokenCache();
		cache.putToken("tok", pendingInfo());

		cache.confirmAttempt("tok", NEVER_MATCH, 3);
		cache.confirmAttempt("tok", NEVER_MATCH, 3);
		ConfirmAttemptResult third = cache.confirmAttempt("tok", NEVER_MATCH, 3);

		assertEquals(ConfirmAttemptResult.Status.TOKEN_INVALIDATED, third.status());
		assertNull(cache.getTokenValue("tok"), "token must be removed once the attempt limit is reached");
	}

	@Test
	void confirmAttempt_doesNotMatchAfterTokenInvalidated() {
		var cache = new SignupTokenCache();
		cache.putToken("tok", pendingInfo());

		cache.confirmAttempt("tok", NEVER_MATCH, 3);
		cache.confirmAttempt("tok", NEVER_MATCH, 3);
		cache.confirmAttempt("tok", NEVER_MATCH, 3);

		// Even correct credentials cannot complete the flow once the token is gone.
		ConfirmAttemptResult afterInvalidation = cache.confirmAttempt("tok", ALWAYS_MATCH, 3);

		assertEquals(ConfirmAttemptResult.Status.TOKEN_NOT_FOUND, afterInvalidation.status());
	}

	@Test
	void confirmAttempt_returnsTokenNotFoundAndRemoves_whenTokenExpired() {
		var cache = new SignupTokenCache();
		SignupInfo info = pendingInfo();
		info.setExpirationDate(new Date(System.currentTimeMillis() - 1000)); // already expired
		cache.putToken("tok", info);

		// Even matching credentials must not confirm an expired token.
		ConfirmAttemptResult result = cache.confirmAttempt("tok", ALWAYS_MATCH, 3);

		assertEquals(ConfirmAttemptResult.Status.TOKEN_NOT_FOUND, result.status(),
				"an expired token must confirm as not-found even with matching credentials");
		assertNull(cache.getTokenValue("tok"), "an expired token must be removed");
	}

	@Test
	void confirmAttempt_isAtomic_underConcurrentWrongAttempts() {
		var cache = new SignupTokenCache();
		SignupInfo info = pendingInfo();
		cache.putToken("tok", info);
		int maxAttempts = 3;
		int attempts = 200;

		ConcurrentLinkedQueue<ConfirmAttemptResult.Status> results = fireConcurrently(
				attempts, () -> cache.confirmAttempt("tok", NEVER_MATCH, maxAttempts).status());

		// Regardless of interleaving, the lock-out is exact: maxAttempts-1 retryable failures, then one
		// invalidation, then every later attempt sees the token gone. A non-atomic verify+increment would
		// let extra attempts slip past the limit (more INVALID_CREDENTIALS or more than one TOKEN_INVALIDATED).
		assertEquals(maxAttempts - 1L, count(results, ConfirmAttemptResult.Status.INVALID_CREDENTIALS));
		assertEquals(1L, count(results, ConfirmAttemptResult.Status.TOKEN_INVALIDATED));
		assertEquals(attempts - (long) maxAttempts, count(results, ConfirmAttemptResult.Status.TOKEN_NOT_FOUND));
		assertEquals(maxAttempts, info.getConfirmationAttempts(), "the counter must never exceed the limit");
		assertNull(cache.getTokenValue("tok"));
	}

	@Test
	void confirmAttempt_consumesTokenExactlyOnce_underConcurrentValidAttempts() {
		var cache = new SignupTokenCache();
		cache.putToken("tok", pendingInfo());
		int attempts = 200;

		ConcurrentLinkedQueue<ConfirmAttemptResult.Status> results = fireConcurrently(
				attempts, () -> cache.confirmAttempt("tok", ALWAYS_MATCH, 3).status());

		// A token must be consumable by exactly one concurrent confirmation; all others see it gone.
		assertEquals(1L, count(results, ConfirmAttemptResult.Status.VALID),
				"a token must be consumable by exactly one concurrent confirmation");
		assertEquals(attempts - 1L, count(results, ConfirmAttemptResult.Status.TOKEN_NOT_FOUND));
		assertNull(cache.getTokenValue("tok"));
	}

	/**
	 * Runs {@code task} on {@code count} virtual threads released simultaneously (via a start latch) so the
	 * attempts genuinely race, and returns their results once all have completed.
	 */
	private static ConcurrentLinkedQueue<ConfirmAttemptResult.Status> fireConcurrently(
			int count, java.util.function.Supplier<ConfirmAttemptResult.Status> task) {
		var results = new ConcurrentLinkedQueue<ConfirmAttemptResult.Status>();
		var start = new CountDownLatch(1);
		try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
			for (int i = 0; i < count; i++) {
				pool.submit(() -> {
					try {
						start.await();
					} catch (InterruptedException e) {
						Thread.currentThread().interrupt();
						return;
					}
					results.add(task.get());
				});
			}
			start.countDown(); // release all threads at once to maximise contention
		} // close() blocks until every task has finished
		return results;
	}

	private static long count(ConcurrentLinkedQueue<ConfirmAttemptResult.Status> results, ConfirmAttemptResult.Status status) {
		return results.stream().filter(s -> s == status).count();
	}
}
