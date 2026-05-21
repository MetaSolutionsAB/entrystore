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

import com.github.benmanes.caffeine.cache.Ticker;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CachingPasswordEncoderTest {

	private static final String RAW = "correct horse battery staple";
	private static final String ENCODED = "$pbkdf2$fake-encoded-hash-with-embedded-salt";

	@Test
	void matches_firstCall_invokesDelegate() {
		var delegate = new CountingDelegate(true);
		var encoder = new CachingPasswordEncoder(delegate, Duration.ofHours(1), 100L, Ticker.systemTicker());

		boolean result = encoder.matches(RAW, ENCODED);

		assertTrue(result);
		assertEquals(1, delegate.matchCalls.get());
	}

	@Test
	void matches_secondCall_sameCredentials_skipsDelegate() {
		var delegate = new CountingDelegate(true);
		var encoder = new CachingPasswordEncoder(delegate, Duration.ofHours(1), 100L, Ticker.systemTicker());

		encoder.matches(RAW, ENCODED);
		boolean second = encoder.matches(RAW, ENCODED);

		assertTrue(second);
		assertEquals(1, delegate.matchCalls.get());
	}

	@Test
	void matches_delegateReturnsFalse_doesNotCache() {
		// Negative caching would create a free DoS oracle (lock a user out of their own account by
		// poisoning the cache once) — the wrapper must call PBKDF2 every time on the failure path.
		var delegate = new CountingDelegate(false);
		var encoder = new CachingPasswordEncoder(delegate, Duration.ofHours(1), 100L, Ticker.systemTicker());

		boolean first = encoder.matches(RAW, ENCODED);
		boolean second = encoder.matches(RAW, ENCODED);

		assertFalse(first);
		assertFalse(second);
		assertEquals(2, delegate.matchCalls.get());
	}

	@Test
	void matches_afterTtlExpiry_invokesDelegateAgain() {
		// Drive Caffeine's clock via an AtomicLong ticker so the TTL boundary is deterministic
		// without Thread.sleep flakiness.
		var nanos = new AtomicLong();
		var delegate = new CountingDelegate(true);
		var encoder = new CachingPasswordEncoder(delegate, Duration.ofMinutes(1), 100L, nanos::get);

		encoder.matches(RAW, ENCODED);
		nanos.addAndGet(Duration.ofMinutes(2).toNanos());
		boolean afterExpiry = encoder.matches(RAW, ENCODED);

		assertTrue(afterExpiry);
		assertEquals(2, delegate.matchCalls.get());
	}

	@Test
	void matches_differentEncoded_doesNotHit() {
		// Password rotation produces a new PBKDF2 salt → new encoded → new cache key. The stale
		// entry under the old (encoded, raw) just ages out.
		var delegate = new CountingDelegate(true);
		var encoder = new CachingPasswordEncoder(delegate, Duration.ofHours(1), 100L, Ticker.systemTicker());

		encoder.matches(RAW, ENCODED);
		encoder.matches(RAW, "$pbkdf2$rotated-encoded-hash");

		assertEquals(2, delegate.matchCalls.get());
	}

	@Test
	void matches_differentRaw_sameEncoded_doesNotHit() {
		// An attacker who knows a victim's username (and thus can present the same Authorization
		// header to populate the cache) must not get a free pass on a guessed password — wrong-raw
		// + right-encoded must hit PBKDF2 every time.
		var delegate = new CountingDelegate(false);
		var encoder = new CachingPasswordEncoder(delegate, Duration.ofHours(1), 100L, Ticker.systemTicker());

		encoder.matches("guess-1", ENCODED);
		encoder.matches("guess-2", ENCODED);

		assertEquals(2, delegate.matchCalls.get());
	}

	@Test
	void matches_nullEncoded_delegates() {
		// Null inputs must bypass the cache and let the delegate decide — otherwise a future
		// delegate that throws on null would diverge in behaviour, and a cache key derived from
		// "null:null" would be a permanent free pass.
		var delegate = new CountingDelegate(false);
		var encoder = new CachingPasswordEncoder(delegate, Duration.ofHours(1), 100L, Ticker.systemTicker());

		boolean result = encoder.matches(RAW, null);

		assertFalse(result);
		assertEquals(1, delegate.matchCalls.get());
	}

	@Test
	void matches_nullRaw_delegates() {
		// Symmetric to matches_nullEncoded_delegates — pins the other half of the cacheKey guard
		// so a refactor that drops the rawPassword null-check cannot accidentally cache a
		// (null, ENCODED) outcome under SHA-256("ENCODED:null").
		var delegate = new CountingDelegate(false);
		var encoder = new CachingPasswordEncoder(delegate, Duration.ofHours(1), 100L, Ticker.systemTicker());

		boolean first = encoder.matches(null, ENCODED);
		boolean second = encoder.matches(null, ENCODED);

		assertFalse(first);
		assertFalse(second);
		assertEquals(2, delegate.matchCalls.get());
	}

	@Test
	void matches_concurrentFirstMissesForSameKey_delegateCalledOnce() throws InterruptedException {
		// Pins the stampede fix: Caffeine's get(key, loader) holds a per-key compute lock so only
		// ONE thread enters the loader; the other (threadCount - 1) threads block on Caffeine's
		// lock, never reaching the delegate. The countdown latch therefore counts down exactly
		// once — we poll until it drops below threadCount (proving the loader is engaged) before
		// releasing it. The real invariant the test pins is matchCalls == 1 after all
		// submissions complete.
		int threadCount = 16;
		var loaderEntered = new CountDownLatch(threadCount);
		var releaseLoader = new CountDownLatch(1);
		var blockingDelegate = new CountingDelegate(true) {
			@Override
			public boolean matches(CharSequence rawPassword, String encodedPassword) {
				try {
					loaderEntered.countDown();
					if (!releaseLoader.await(5, TimeUnit.SECONDS)) {
						throw new IllegalStateException("loader release latch timed out");
					}
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					throw new IllegalStateException(e);
				}
				return super.matches(rawPassword, encodedPassword);
			}
		};
		var encoder = new CachingPasswordEncoder(blockingDelegate, Duration.ofHours(1), 100L, Ticker.systemTicker());

		ExecutorService pool = Executors.newFixedThreadPool(threadCount);
		try {
			for (int i = 0; i < threadCount; i++) {
				pool.submit(() -> encoder.matches(RAW, ENCODED));
			}
			// Poll until the one thread inside the loader counts the latch down once. Caffeine's
			// per-key compute lock guarantees the other threads never enter the loader, so the
			// latch can never reach zero — await() would always time out.
			long deadlineNanos = System.nanoTime() + Duration.ofSeconds(5).toNanos();
			while (loaderEntered.getCount() == threadCount && System.nanoTime() < deadlineNanos) {
				Thread.sleep(10);
			}
			assertTrue(loaderEntered.getCount() < threadCount,
					"at least one thread should reach the loader within 5s");
			releaseLoader.countDown();
		} finally {
			pool.shutdown();
			assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
		}

		assertEquals(1, blockingDelegate.matchCalls.get(),
				"cache.get(key, loader) must deduplicate concurrent first-misses");
	}

	@Test
	void matches_delegateReturnsFalse_doesNotStoreInCache() {
		// Pins the loader's null-vs-FALSE invariant directly at the storage layer. A refactor that
		// simplifies the loader to `_ -> delegate.matches(...)` would box FALSE into the
		// Cache<String,Boolean> and lock a user out after a single bad attempt — a DoS oracle the
		// behavioural matches_delegateReturnsFalse_doesNotCache test alone wouldn't catch.
		var delegate = new CountingDelegate(false);
		var encoder = new CachingPasswordEncoder(delegate, Duration.ofHours(1), 100L, Ticker.systemTicker());

		encoder.matches(RAW, ENCODED);

		assertEquals(0L, encoder.cache().estimatedSize(),
				"failed verification must not be stored — boxing FALSE would create a DoS oracle");
	}

	@Test
	void matches_cacheKeySeparator_isUnambiguous() {
		// Pins the encoded+":"+raw separator. The two pairs below produce distinct keys under the
		// correct construction ("a:bcd" vs "ab:cd") but COLLIDE to "abcd" under a separator-drop
		// refactor (encoded + raw). A separator-drop regression would make the second matches()
		// call a silent cache hit, the delegate would be invoked once instead of twice, and an
		// attacker who could construct (raw, encoded) pairs that concat-collide with a legitimate
		// credential pair would skip PBKDF2 entirely — pre-authentication for free.
		var delegate = new CountingDelegate(true);
		var encoder = new CachingPasswordEncoder(delegate, Duration.ofHours(1), 100L, Ticker.systemTicker());

		encoder.matches("bcd", "a");
		encoder.matches("cd", "ab");

		assertEquals(2, delegate.matchCalls.get(),
				"distinct (raw, encoded) pairs must build distinct cache keys (separator must not be droppable)");
		assertEquals(2L, encoder.cache().estimatedSize(),
				"both successful matches must produce separate cache entries");
	}

	@Test
	void matches_aboveMaxSize_evictsOldestEntries() {
		// Pins the .maximumSize(maxSize) Caffeine builder call. A regression that drops that line
		// — or an off-by-one in HttpBasicAuthConfiguration plumbing — would silently produce
		// unbounded memory growth under credential churn. cleanUp() forces Caffeine's
		// otherwise-asynchronous size-based eviction to run synchronously so the assertion is
		// deterministic. Eviction is best-effort, so we assert the post-cleanup size stays at or
		// below the cap, not strict equality.
		var delegate = new CountingDelegate(true);
		long maxSize = 2L;
		var encoder = new CachingPasswordEncoder(delegate, Duration.ofHours(1), maxSize, Ticker.systemTicker());

		encoder.matches("raw-1", "encoded-1");
		encoder.matches("raw-2", "encoded-2");
		encoder.matches("raw-3", "encoded-3");
		encoder.cache().cleanUp();

		assertTrue(encoder.cache().estimatedSize() <= maxSize,
				"cache must enforce maxSize=" + maxSize + " after cleanUp(), got " + encoder.cache().estimatedSize());
	}

	@Test
	void encode_alwaysDelegates() {
		// encode() must pass straight through — never read or write the cache.
		var delegate = new CountingDelegate(true);
		var encoder = new CachingPasswordEncoder(delegate, Duration.ofHours(1), 100L, Ticker.systemTicker());

		encoder.encode(RAW);
		encoder.encode(RAW);

		assertEquals(2, delegate.encodeCalls.get());
		assertEquals(0, delegate.matchCalls.get());
	}

	@Test
	void constructor_nullDelegate_throwsNpe() {
		assertThrows(NullPointerException.class,
				() -> new CachingPasswordEncoder(null, Duration.ofHours(1), 100L, Ticker.systemTicker()));
	}

	@Test
	void constructor_nullTtl_throwsNpe() {
		assertThrows(NullPointerException.class,
				() -> new CachingPasswordEncoder(new CountingDelegate(true), null, 100L, Ticker.systemTicker()));
	}

	@Test
	void constructor_nullTicker_throwsNpe() {
		assertThrows(NullPointerException.class,
				() -> new CachingPasswordEncoder(new CountingDelegate(true), Duration.ofHours(1), 100L, null));
	}

	@Test
	void constructor_zeroTtl_throwsIae() {
		assertThrows(IllegalArgumentException.class,
				() -> new CachingPasswordEncoder(new CountingDelegate(true), Duration.ZERO, 100L, Ticker.systemTicker()));
	}

	@Test
	void constructor_negativeTtl_throwsIae() {
		assertThrows(IllegalArgumentException.class,
				() -> new CachingPasswordEncoder(new CountingDelegate(true), Duration.ofSeconds(-1), 100L, Ticker.systemTicker()));
	}

	@Test
	void constructor_zeroMaxSize_throwsIae() {
		assertThrows(IllegalArgumentException.class,
				() -> new CachingPasswordEncoder(new CountingDelegate(true), Duration.ofHours(1), 0L, Ticker.systemTicker()));
	}

	@Test
	void constructor_negativeMaxSize_throwsIae() {
		assertThrows(IllegalArgumentException.class,
				() -> new CachingPasswordEncoder(new CountingDelegate(true), Duration.ofHours(1), -1L, Ticker.systemTicker()));
	}

	private static class CountingDelegate implements PasswordEncoder {
		final AtomicInteger matchCalls = new AtomicInteger();
		final AtomicInteger encodeCalls = new AtomicInteger();
		private final boolean matchResult;

		CountingDelegate(boolean matchResult) {
			this.matchResult = matchResult;
		}

		@Override
		public String encode(CharSequence rawPassword) {
			encodeCalls.incrementAndGet();
			return "encoded:" + rawPassword;
		}

		@Override
		public boolean matches(CharSequence rawPassword, String encodedPassword) {
			matchCalls.incrementAndGet();
			return matchResult;
		}
	}
}
