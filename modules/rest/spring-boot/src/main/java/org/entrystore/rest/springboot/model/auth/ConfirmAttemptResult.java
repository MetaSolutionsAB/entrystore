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

package org.entrystore.rest.springboot.model.auth;

/**
 * Outcome of an atomic credential-confirmation attempt against a pending token
 * (see {@code SignupTokenCache#confirmAttempt}).
 *
 * @param status            the classification of the attempt
 * @param info              the pending record on a {@link Status#VALID} attempt, otherwise {@code null}
 * @param remainingAttempts attempts left before invalidation on {@link Status#INVALID_CREDENTIALS},
 *                          otherwise {@code 0}
 */
public record ConfirmAttemptResult(Status status, SignupInfo info, int remainingAttempts) {

	public enum Status {
		/** Credentials matched; the token has been consumed. */
		VALID,
		/** Credentials did not match but the token is still usable. */
		INVALID_CREDENTIALS,
		/** Credentials did not match and the attempt limit was reached; the token has been removed. */
		TOKEN_INVALIDATED,
		/** No pending record exists for the token (unknown, already used, or expired). */
		TOKEN_NOT_FOUND
	}

	public ConfirmAttemptResult {
		if ((status == Status.VALID) != (info != null)) {
			throw new IllegalArgumentException("info must be present exactly when status is VALID");
		}
		if (status != Status.INVALID_CREDENTIALS && remainingAttempts != 0) {
			throw new IllegalArgumentException("remainingAttempts is only meaningful for INVALID_CREDENTIALS");
		}
	}

	public static ConfirmAttemptResult valid(SignupInfo info) {
		return new ConfirmAttemptResult(Status.VALID, info, 0);
	}

	public static ConfirmAttemptResult invalidCredentials(int remainingAttempts) {
		return new ConfirmAttemptResult(Status.INVALID_CREDENTIALS, null, remainingAttempts);
	}

	public static ConfirmAttemptResult tokenInvalidated() {
		return new ConfirmAttemptResult(Status.TOKEN_INVALIDATED, null, 0);
	}

	public static ConfirmAttemptResult tokenNotFound() {
		return new ConfirmAttemptResult(Status.TOKEN_NOT_FOUND, null, 0);
	}
}
