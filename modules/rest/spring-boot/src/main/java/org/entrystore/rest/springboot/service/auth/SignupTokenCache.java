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
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import com.github.benmanes.caffeine.cache.Ticker;
import lombok.extern.slf4j.Slf4j;
import org.entrystore.rest.springboot.configuration.CaffeineCacheSource;
import org.entrystore.rest.springboot.model.auth.ConfirmAttemptResult;
import org.entrystore.rest.springboot.model.auth.SignupInfo;
import org.entrystore.rest.springboot.util.CapacityEvictionWarning;
import org.springframework.stereotype.Service;
import org.springframework.util.unit.DataSize;

import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * Holds the pending sign-up and password-reset records, keyed by the one-time token mailed to the
 * user, until the user confirms or the record's own {@link SignupInfo#getExpirationDate() deadline}
 * passes (set by {@code AuthService}, currently 24 hours). Every mutation runs under one lock so
 * {@link #confirmAttempt} can verify, count and remove atomically; a read takes the lock only to
 * drop a record it finds past its deadline (see {@code pastDeadline}).
 *
 * <p>Tokens are minted by anonymous requests whose caller strings (names, redirect URLs, at most 50
 * {@code custom_*} properties) are bounded only by the 32 KB request cap, so {@code maximumWeight} caps the
 * estimated bytes held (see {@link #weigh}) rather than the entry count, and {@link CapacityEvictionWarning}
 * reports when the cap bites. Caffeine evicts by W-TinyLFU, not by age: flood and legitimate tokens are both
 * write-once, so once the cache is full a newly minted legitimate token is evicted before its deadline while
 * flood entries persist to theirs. A flood entry weighs at most ~40 KB (~70 KB with UTF-16 strings), so at the
 * default sign-up rate limit (10 per IP per hour, 24 h TTL) about 8 source IPs fill the cache; the WARN is the
 * signal to look for one.
 */
@Slf4j
@Service
public class SignupTokenCache implements CaffeineCacheSource {

	// Estimated bytes: ~100k typical tokens (~1.3 KB each), but only ~2k-3k flood entries (see class Javadoc).
	static final DataSize MAX_WEIGHT = DataSize.ofMegabytes(128);

	// Object headers, the Date, the map and the cache node: rough, but keeps tiny entries from weighing ~0.
	static final int ENTRY_OVERHEAD = 1024;

	// HashMap node, two String and two byte[] headers, table slot: else many tiny custom_* members hold heap for free.
	static final int PROPERTY_OVERHEAD = 120;

	private final Object lock = new Object();
	private final Cache<String, SignupInfo> tokens;

	public SignupTokenCache(Ticker ticker) {
		var capacityWarning = new CapacityEvictionWarning(log, "Sign-up/password-reset token",
				MAX_WEIGHT.toMegabytes() + " MB");
		this.tokens = Caffeine.newBuilder()
				.ticker(ticker)
				.maximumWeight(MAX_WEIGHT.toBytes())
				.weigher(SignupTokenCache::weigh)
				// Remaining time is measured on the wall clock, elapsed time on the ticker; a record already
				// past its deadline gets a non-positive duration and expires on insert.
				.expireAfter(Expiry.writing((String token, SignupInfo info) ->
						Duration.between(Instant.now(), info.getExpirationDate().toInstant())))
				.evictionListener(capacityWarning.listener())
				.recordStats()
				.build();
	}

	@Override
	public Map<String, Cache<?, ?>> caffeineCaches() {
		return Map.of("signup-tokens", tokens);
	}

	public void putToken(String token, SignupInfo info) {
		// Without a deadline the record would live until capacity eviction; refuse it at the door.
		Objects.requireNonNull(info.getExpirationDate(), "a pending token must carry an expiration date");
		synchronized (lock) {
			tokens.put(token, info);
		}
	}

	/**
	 * Returns the pending record for {@code token}, or {@code null} when the token is unknown, already
	 * consumed, or past its deadline. The deadline is re-checked on the wall clock because Caffeine's
	 * expiry runs on the ticker (see {@code pastDeadline}); a record found past it is removed here, so
	 * the confirmation-form checks in {@code AuthService}, which read through this method, never honour
	 * a stale token. Does not consume the token; a confirmation uses {@link #consumeToken} or
	 * {@link #confirmAttempt}.
	 */
	public SignupInfo getTokenValue(String token) {
		if (token == null) {
			return null;
		}
		SignupInfo info = tokens.getIfPresent(token);
		if (info != null && pastDeadline(info)) {
			removeToken(token);
			return null;
		}
		return info;
	}

	/**
	 * Removes and returns the pending record for {@code token}, or returns {@code null} when the token is
	 * unknown, already consumed, or past its deadline. The atomic remove guarantees that of two concurrent
	 * legacy confirmations of the same link only one gets the record and applies it.
	 */
	public SignupInfo consumeToken(String token) {
		if (token == null) {
			return null;
		}
		SignupInfo info;
		synchronized (lock) {
			info = tokens.asMap().remove(token);
		}
		return (info == null || pastDeadline(info)) ? null : info;
	}

	public void removeToken(String token) {
		synchronized (lock) {
			tokens.invalidate(token);
		}
	}

	public void removeAllTokens(String userEmail) {
		synchronized (lock) {
			tokens.asMap().entrySet().removeIf(entry -> userEmail.equals(entry.getValue().getEmail()));
		}
	}

	/**
	 * Atomically verifies a credential-confirmation attempt against the pending record for {@code token}.
	 * Verification, the failed-attempt count, and token removal all happen under the cache lock so that
	 * parallel attempts cannot race past the limit (the lock-out invariant this feature relies on). The
	 * {@code credentialsValid} predicate runs inside the lock — for sign-up it is a PBKDF2 password check,
	 * for password reset a plain email comparison. Holding the lock across it serializes confirmations;
	 * that work is bounded because the predicate runs only for a found, non-expired token and is capped at
	 * {@code maxAttempts} strikes per token. This method does not itself throttle by IP: callers must
	 * rate-limit the confirm endpoint so a single token cannot be hammered with attempts — the
	 * {@code AuthService.confirmSignup} / {@code confirmPassword} callers acquire a per-IP permit before
	 * invoking it.
	 *
	 * <ul>
	 *   <li>match → the token is removed and {@link ConfirmAttemptResult.Status#VALID} is returned with the record</li>
	 *   <li>mismatch below the limit → the attempt counter is incremented and {@link ConfirmAttemptResult.Status#INVALID_CREDENTIALS} is returned</li>
	 *   <li>mismatch reaching the limit → the token is removed and {@link ConfirmAttemptResult.Status#TOKEN_INVALIDATED} is returned</li>
	 *   <li>no record (unknown or expired) → {@link ConfirmAttemptResult.Status#TOKEN_NOT_FOUND}</li>
	 * </ul>
	 */
	public ConfirmAttemptResult confirmAttempt(String token, Predicate<SignupInfo> credentialsValid, int maxAttempts) {
		synchronized (lock) {
			// A missing token field on the confirm request arrives here as null; treat it as not-found
			// rather than letting the cache's null-key check throw and surface as a 500.
			SignupInfo info = (token == null) ? null : tokens.getIfPresent(token);
			if (info == null) {
				return ConfirmAttemptResult.tokenNotFound();
			}
			// Wall-clock re-check over the ticker-based Caffeine expiry, see pastDeadline.
			if (pastDeadline(info)) {
				tokens.invalidate(token);
				return ConfirmAttemptResult.tokenNotFound();
			}
			if (credentialsValid.test(info)) {
				tokens.invalidate(token);
				return ConfirmAttemptResult.valid(info);
			}
			int attempts = info.recordFailedConfirmation();
			if (attempts >= maxAttempts) {
				tokens.invalidate(token);
				return ConfirmAttemptResult.tokenInvalidated();
			}
			return ConfirmAttemptResult.invalidCredentials(maxAttempts - attempts);
		}
	}

	/**
	 * Whether the record's wall-clock deadline has passed. Caffeine fixes each entry's expiry at insert
	 * and measures elapsed time on the ticker ({@code System.nanoTime}, which is monotonic and does not
	 * advance while the host or VM is suspended), so after a suspend an entry can outlive its deadline
	 * in the cache. The deadline the user was promised is the wall-clock one, so every read path
	 * re-checks it and drops the record once it has passed.
	 */
	private static boolean pastDeadline(SignupInfo info) {
		return info.getExpirationDate().before(new Date());
	}

	/**
	 * Estimated bytes held by one entry: {@link #ENTRY_OVERHEAD}, {@link #PROPERTY_OVERHEAD} per custom property,
	 * plus the caller strings it carries.
	 */
	static int weigh(String token, SignupInfo info) {
		int properties = info.getCustomProperties() == null ? 0 : info.getCustomProperties().size();
		return ENTRY_OVERHEAD + token.length() + PROPERTY_OVERHEAD * properties + info.estimatedSize();
	}
}
