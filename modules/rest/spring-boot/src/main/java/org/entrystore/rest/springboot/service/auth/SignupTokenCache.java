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

import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.entrystore.rest.springboot.model.auth.ConfirmAttemptResult;
import org.entrystore.rest.springboot.model.auth.SignupInfo;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.Map;
import java.util.function.Predicate;

/**
 * @author Hannes Ebner
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SignupTokenCache extends TokenCache<String, SignupInfo> {

	@Transactional
	public void cleanup() {
		synchronized (tokenCache) {
			for (Map.Entry<String, SignupInfo> e : tokenCache.entrySet()) {
				if (e.getValue().getExpirationDate().before(new Date())) {
					tokenCache.remove(e.getKey());
				}
			}
		}
	}

	public void removeAllTokens(String userEmail) {
		synchronized (tokenCache) {
			tokenCache.entrySet().removeIf(userInfo -> userEmail.equals(userInfo.getValue().getEmail()));
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
		synchronized (tokenCache) {
			// A missing token field on the confirm request arrives here as null; treat it as not-found
			// rather than letting ConcurrentHashMap.get(null) throw and surface as a 500.
			SignupInfo info = (token == null) ? null : tokenCache.get(token);
			if (info == null) {
				return ConfirmAttemptResult.tokenNotFound();
			}
			// Check only the fetched token's expiry, avoiding an O(n) full-map cleanup() scan under the
			// lock on the confirm hot path; an expired token confirms as TOKEN_NOT_FOUND. Expired entries
			// for other tokens are still reaped by cleanup() via getTokenValue/removeToken/size.
			if (info.getExpirationDate().before(new Date())) {
				tokenCache.remove(token);
				return ConfirmAttemptResult.tokenNotFound();
			}
			if (credentialsValid.test(info)) {
				tokenCache.remove(token);
				return ConfirmAttemptResult.valid(info);
			}
			int attempts = info.recordFailedConfirmation();
			if (attempts >= maxAttempts) {
				tokenCache.remove(token);
				return ConfirmAttemptResult.tokenInvalidated();
			}
			return ConfirmAttemptResult.invalidCredentials(maxAttempts - attempts);
		}
	}

}
