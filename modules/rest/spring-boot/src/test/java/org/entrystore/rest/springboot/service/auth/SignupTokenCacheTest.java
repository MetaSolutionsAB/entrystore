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

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Ticker;
import org.apache.logging.log4j.Level;
import org.entrystore.rest.springboot.model.auth.ConfirmAttemptResult;
import org.entrystore.rest.springboot.model.auth.SignupInfo;
import org.entrystore.rest.springboot.util.CapturingAppender;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SignupTokenCacheTest {

	private static final Predicate<SignupInfo> ALWAYS_MATCH = info -> true;
	private static final Predicate<SignupInfo> NEVER_MATCH = info -> false;

	private static SignupTokenCache newCache() {
		return new SignupTokenCache(Ticker.systemTicker());
	}

	private SignupInfo pendingInfo() {
		SignupInfo info = new SignupInfo();
		info.setEmail("user@example.com");
		info.setExpirationDate(new Date(System.currentTimeMillis() + 3600_000));
		return info;
	}

	// A legacy-mode sign-up: names, a salted hash and both redirect URLs.
	private SignupInfo typicalSignupInfo() {
		SignupInfo info = pendingInfo();
		info.setFirstName("Firstname");
		info.setLastName("Lastname-Surname");
		info.setSaltedHashedPassword("h".repeat(100));
		info.setUrlSuccess("https://app.example.com/signup/success?lang=en&return=/some/path");
		info.setUrlFailure("https://app.example.com/signup/failure?lang=en&return=/some/path");
		return info;
	}

	@Test
	void confirmAttempt_returnsValidAndConsumesToken_whenCredentialsMatch() {
		var cache = newCache();
		SignupInfo info = pendingInfo();
		cache.putToken("tok", info);

		ConfirmAttemptResult result = cache.confirmAttempt("tok", ALWAYS_MATCH, 3);

		assertEquals(ConfirmAttemptResult.Status.VALID, result.status());
		assertSame(info, result.info());
		assertNull(cache.getTokenValue("tok"), "a consumed token must be removed");
	}

	@Test
	void confirmAttempt_returnsTokenNotFound_forUnknownToken() {
		var cache = newCache();

		ConfirmAttemptResult result = cache.confirmAttempt("missing", ALWAYS_MATCH, 3);

		assertEquals(ConfirmAttemptResult.Status.TOKEN_NOT_FOUND, result.status());
	}

	@Test
	void confirmAttempt_returnsTokenNotFound_forNullToken() {
		var cache = newCache();
		cache.putToken("tok", pendingInfo());

		// A confirm request that omits the token field arrives as null; it must not throw.
		ConfirmAttemptResult result = cache.confirmAttempt(null, ALWAYS_MATCH, 3);

		assertEquals(ConfirmAttemptResult.Status.TOKEN_NOT_FOUND, result.status());
	}

	@Test
	void confirmAttempt_countsFailureAndKeepsToken_belowLimit() {
		var cache = newCache();
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
		var cache = newCache();
		cache.putToken("tok", pendingInfo());

		cache.confirmAttempt("tok", NEVER_MATCH, 3);
		cache.confirmAttempt("tok", NEVER_MATCH, 3);
		ConfirmAttemptResult third = cache.confirmAttempt("tok", NEVER_MATCH, 3);

		assertEquals(ConfirmAttemptResult.Status.TOKEN_INVALIDATED, third.status());
		assertNull(cache.getTokenValue("tok"), "token must be removed once the attempt limit is reached");
	}

	@Test
	void confirmAttempt_doesNotMatchAfterTokenInvalidated() {
		var cache = newCache();
		cache.putToken("tok", pendingInfo());

		cache.confirmAttempt("tok", NEVER_MATCH, 3);
		cache.confirmAttempt("tok", NEVER_MATCH, 3);
		cache.confirmAttempt("tok", NEVER_MATCH, 3);

		// Even correct credentials cannot complete the flow once the token is gone.
		ConfirmAttemptResult afterInvalidation = cache.confirmAttempt("tok", ALWAYS_MATCH, 3);

		assertEquals(ConfirmAttemptResult.Status.TOKEN_NOT_FOUND, afterInvalidation.status());
	}

	// Simulates a host suspend: the wall clock passes the deadline while the monotonic ticker stands
	// still, so Caffeine still holds the record and only the wall-clock re-check can reject it.
	@Test
	void confirmAttempt_returnsTokenNotFoundAndRemoves_whenDeadlinePassesWhileTickerStandsStill() {
		var cache = new SignupTokenCache(new AtomicLong()::get);
		SignupInfo info = pendingInfo();
		cache.putToken("tok", info);
		assertNotNull(cache.getTokenValue("tok"));
		info.setExpirationDate(new Date(System.currentTimeMillis() - 1000));

		// Even matching credentials must not confirm an expired token.
		ConfirmAttemptResult result = cache.confirmAttempt("tok", ALWAYS_MATCH, 3);

		assertEquals(ConfirmAttemptResult.Status.TOKEN_NOT_FOUND, result.status(),
				"an expired token must confirm as not-found even with matching credentials");
		// Restoring the deadline proves the record was removed rather than merely filtered.
		info.setExpirationDate(new Date(System.currentTimeMillis() + 3600_000));
		assertNull(cache.getTokenValue("tok"), "an expired token must be removed");
	}

	@Test
	void getTokenValue_returnsNullAndRemoves_whenDeadlinePassesWhileTickerStandsStill() {
		var cache = new SignupTokenCache(new AtomicLong()::get);
		SignupInfo info = pendingInfo();
		cache.putToken("tok", info);
		assertNotNull(cache.getTokenValue("tok"));
		info.setExpirationDate(new Date(System.currentTimeMillis() - 1000));

		assertNull(cache.getTokenValue("tok"), "a token past its wall-clock deadline must read as absent");

		// Restoring the deadline proves the record was removed rather than merely filtered.
		info.setExpirationDate(new Date(System.currentTimeMillis() + 3600_000));
		assertNull(cache.getTokenValue("tok"));
	}

	@Test
	void confirmAttempt_isAtomic_underConcurrentWrongAttempts() {
		var cache = newCache();
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
		var cache = newCache();
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

	// A token without a deadline would live until capacity eviction, so it must be rejected at the
	// door rather than stored.
	@Test
	void putToken_withoutExpirationDate_throws() {
		var cache = newCache();
		SignupInfo info = new SignupInfo();
		info.setEmail("user@example.com");

		assertThrows(NullPointerException.class, () -> cache.putToken("tok", info));
		assertNull(cache.getTokenValue("tok"));
	}

	// Caffeine measures elapsed time on the injected ticker, so the deadline is crossed by advancing
	// the ticker rather than by sleeping or by back-dating the token.
	@Test
	void token_expiresAtItsExpirationDate() {
		var nanos = new AtomicLong();
		var cache = new SignupTokenCache(nanos::get);
		cache.putToken("tok", pendingInfo()); // one hour ahead
		assertNotNull(cache.getTokenValue("tok"));

		nanos.addAndGet(Duration.ofHours(2).toNanos());

		assertNull(cache.getTokenValue("tok"), "a token must not outlive its expiration date");
		assertEquals(ConfirmAttemptResult.Status.TOKEN_NOT_FOUND, cache.confirmAttempt("tok", ALWAYS_MATCH, 3).status());
	}

	@Test
	void token_alreadyExpiredAtInsert_isNotRetrievable() {
		var cache = newCache();
		SignupInfo info = pendingInfo();
		info.setExpirationDate(new Date(System.currentTimeMillis() - 1000));

		cache.putToken("tok", info);

		assertNull(cache.getTokenValue("tok"));
	}

	@Test
	void removeAllTokens_removesOnlyTokensOfThatEmail() {
		var cache = newCache();
		SignupInfo firstOfAlice = pendingInfo();
		SignupInfo secondOfAlice = pendingInfo();
		SignupInfo ofBob = pendingInfo();
		ofBob.setEmail("bob@example.com");
		cache.putToken("alice-1", firstOfAlice);
		cache.putToken("alice-2", secondOfAlice);
		cache.putToken("bob-1", ofBob);

		cache.removeAllTokens("user@example.com");

		assertNull(cache.getTokenValue("alice-1"));
		assertNull(cache.getTokenValue("alice-2"));
		assertSame(ofBob, cache.getTokenValue("bob-1"), "another user's pending token must survive");
	}

	@Test
	void caffeineCaches_exposesTheTokenCacheAsSignupTokens() {
		var cache = newCache();
		cache.putToken("tok", pendingInfo());

		var caches = cache.caffeineCaches();

		assertEquals(Set.of("signup-tokens"), caches.keySet());
		assertEquals(1L, caches.get("signup-tokens").estimatedSize());
	}

	// The weight bound and its throttled SIZE-eviction warn bound the heap against a sign-up flood that
	// outruns the per-IP rate limiter (many source addresses); this pins both, and that the budget stays
	// generous enough that a day of typical sign-ups (~100k records) is never evicted early.
	@Test
	void capacityEvictionOfTypicalTokensIsBoundedAndWarnsOnce() {
		try (var appender = CapturingAppender.attachTo(SignupTokenCache.class)) {
			var cache = newCache();
			int dayOfTypicalTokens = 100_000;
			long typicalWeight = SignupTokenCache.weigh("0123456789abcdef", typicalSignupInfo());
			long tokensToOverfill = SignupTokenCache.MAX_WEIGHT.toBytes() / typicalWeight + 100;
			for (int i = 0; i < tokensToOverfill; i++) {
				cache.putToken(String.format("%016d", i), typicalSignupInfo());
			}
			var caffeine = cache.caffeineCaches().get("signup-tokens");
			caffeine.cleanUp();

			assertTrue(weightedSize(caffeine) <= SignupTokenCache.MAX_WEIGHT.toBytes());
			assertTrue(caffeine.estimatedSize() >= dayOfTypicalTokens,
					"effective cache cap shrunk below a day of legitimate sign-ups and password resets");
			assertEquals(1, appender.countAt(Level.WARN), appender::toString);
			assertTrue(appender.messagesAt(Level.WARN).allMatch(message -> message.contains("capacity")));
		}
	}

	// Every minting body may carry up to 32 KB of caller strings; an entry-count cap alone would let
	// 100k of them hold ~3 GB.
	@Test
	void capacityEvictionBoundsTheBytesOfLargeTokens() {
		try (var appender = CapturingAppender.attachTo(SignupTokenCache.class)) {
			var cache = newCache();
			String largeValue = "x".repeat(30 * 1024);
			int tokens = 5_000; // ~150 MB of caller strings
			for (int i = 0; i < tokens; i++) {
				SignupInfo info = pendingInfo();
				info.setCustomProperties(Map.of("bio", largeValue));
				cache.putToken("tok-" + i, info);
			}
			var caffeine = cache.caffeineCaches().get("signup-tokens");
			caffeine.cleanUp();

			assertTrue(weightedSize(caffeine) <= SignupTokenCache.MAX_WEIGHT.toBytes());
			assertTrue(caffeine.estimatedSize() < tokens, "large tokens must be evicted by weight");
			assertEquals(1, appender.countAt(Level.WARN), appender::toString);
		}
	}

	@Test
	void weigh_countsOverheadAndEveryCallerString() {
		SignupInfo info = pendingInfo(); // email "user@example.com", 16 chars
		info.setFirstName("Ada");
		info.setLastName("Lovelace");
		info.setSaltedHashedPassword("hash");
		info.setUrlSuccess("https://ok");
		info.setUrlFailure("https://ko");
		info.setCustomProperties(Map.of("dept", "maths"));

		assertEquals(SignupTokenCache.ENTRY_OVERHEAD + 3 + 16 + 3 + 8 + 4 + 10 + 10 + SignupTokenCache.PROPERTY_OVERHEAD + 4 + 5,
				SignupTokenCache.weigh("tok", info));
	}

	// ~2,000 empty custom_* members fit a 32 KB body; their map nodes and String objects must be weighed.
	@Test
	void weigh_countsOverheadPerCustomProperty() {
		SignupInfo info = new SignupInfo();
		var properties = new HashMap<String, String>();
		for (int i = 0; i < 2_000; i++) {
			properties.put(String.valueOf(i), "");
		}
		info.setCustomProperties(properties);

		assertTrue(SignupTokenCache.weigh("tok", info) >= 2_000 * SignupTokenCache.PROPERTY_OVERHEAD);
	}

	// The JVM stores a string as UTF-16 once it holds a char above U+00FF.
	@Test
	void weigh_countsTwoBytesPerChar_forNonLatin1Strings() {
		SignupInfo latin1 = new SignupInfo();
		latin1.setFirstName("Zo" + (char) 0xEB); // Latin-1 e-diaeresis: one byte per char
		SignupInfo nonLatin1 = new SignupInfo();
		nonLatin1.setFirstName("Zo" + (char) 0x0113); // e-macron: the whole string is UTF-16

		assertEquals(SignupTokenCache.ENTRY_OVERHEAD + 3 + 3, SignupTokenCache.weigh("tok", latin1));
		assertEquals(SignupTokenCache.ENTRY_OVERHEAD + 3 + 6, SignupTokenCache.weigh("tok", nonLatin1));
	}

	@Test
	void weigh_countsAbsentFieldsAsZero() {
		SignupInfo info = new SignupInfo();

		assertEquals(SignupTokenCache.ENTRY_OVERHEAD + 3, SignupTokenCache.weigh("tok", info));
	}

	@Test
	void consumeToken_returnsTheRecordOnceAndRemovesIt() {
		var cache = newCache();
		SignupInfo info = pendingInfo();
		cache.putToken("tok", info);

		assertSame(info, cache.consumeToken("tok"));
		assertNull(cache.consumeToken("tok"), "a consumed token must not be consumable again");
		assertNull(cache.getTokenValue("tok"));
	}

	@Test
	void consumeToken_returnsNull_forUnknownToken() {
		assertNull(newCache().consumeToken("missing"));
	}

	@Test
	void consumeToken_returnsNull_forNullToken() {
		var cache = newCache();
		cache.putToken("tok", pendingInfo());

		assertNull(cache.consumeToken(null));
		assertNotNull(cache.getTokenValue("tok"), "a null token must not touch other records");
	}

	@Test
	void consumeToken_returnsNullAndRemoves_whenDeadlinePassesWhileTickerStandsStill() {
		var cache = new SignupTokenCache(new AtomicLong()::get);
		SignupInfo info = pendingInfo();
		cache.putToken("tok", info);
		info.setExpirationDate(new Date(System.currentTimeMillis() - 1000));

		assertNull(cache.consumeToken("tok"), "a token past its wall-clock deadline must not be consumed");

		// Restoring the deadline proves the record was removed rather than merely filtered.
		info.setExpirationDate(new Date(System.currentTimeMillis() + 3600_000));
		assertNull(cache.getTokenValue("tok"));
	}

	@Test
	void consumeToken_returnsTheRecordExactlyOnce_underConcurrentConsumers() {
		var cache = newCache();
		cache.putToken("tok", pendingInfo());
		int consumers = 200;

		ConcurrentLinkedQueue<Boolean> results = fireConcurrently(consumers, () -> cache.consumeToken("tok") != null);

		assertEquals(1L, results.stream().filter(Boolean::booleanValue).count(),
				"two concurrent legacy confirmations must not both apply the same token");
		assertEquals(consumers, results.size());
	}

	private static long weightedSize(Cache<?, ?> caffeine) {
		return caffeine.policy().eviction().orElseThrow().weightedSize().orElseThrow();
	}

	/**
	 * Runs {@code task} on {@code count} virtual threads released simultaneously (via a start latch) so the
	 * attempts genuinely race, and returns their results once all have completed.
	 */
	private static <T> ConcurrentLinkedQueue<T> fireConcurrently(int count, Supplier<T> task) {
		var results = new ConcurrentLinkedQueue<T>();
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
