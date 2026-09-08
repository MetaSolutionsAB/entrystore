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
 * {@link #confirmAttempt} can verify, count and remove atomically; reads are lock-free.
 *
 * <p>Tokens are minted by anonymous requests, so {@code maximumSize} bounds the heap and
 * {@link CapacityEvictionWarning} reports when the bound bites. The per-IP rate limiters bound
 * legitimate creation; the cap only matters under a many-source flood.
 */
@Slf4j
@Service
public class SignupTokenCache implements CaffeineCacheSource {

	// Cardinality bound: a day of entries at a few hundred bytes each stays well within heap, while a
	// cap this generous never evicts a legitimate user's pending confirmation early.
	static final long MAX_ENTRIES = 100_000;

	private final Object lock = new Object();
	private final Cache<String, SignupInfo> tokens;

	public SignupTokenCache(Ticker ticker) {
		var capacityWarning = new CapacityEvictionWarning(log, "Sign-up/password-reset token", MAX_ENTRIES);
		this.tokens = Caffeine.newBuilder()
				.ticker(ticker)
				.maximumSize(MAX_ENTRIES)
				// Each record carries its own deadline: the remaining time is measured on the wall clock
				// while Caffeine measures elapsed time on the ticker (see the Expiry Javadoc). A record
				// already past its deadline gets a non-positive duration and is expired on insert.
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

	public SignupInfo getTokenValue(String token) {
		return (token == null) ? null : tokens.getIfPresent(token);
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
			// Belt-and-braces over Caffeine's per-entry expiry, which is fixed at insert and measured on the
			// ticker: covers a deadline moved after insert and wall-clock drift against the ticker.
			if (info.getExpirationDate().before(new Date())) {
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
}
