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
 * Result of a credential-confirmation request, returned by the auth service to the controller.
 * A successful confirmation carries the user-facing success message; a failed-but-retryable
 * confirmation carries the number of attempts left so the form can be re-rendered with feedback.
 * Terminal failures (unknown/expired/invalidated token) are signalled via exceptions instead.
 *
 * @param success           whether the action (account creation / password change) was performed
 * @param message           the success message when {@code success} is {@code true}, otherwise {@code null}
 * @param remainingAttempts attempts left before the token is invalidated when {@code success} is {@code false}
 */
public record ConfirmationResult(boolean success, String message, int remainingAttempts) {

	public ConfirmationResult {
		if (success == (message == null)) {
			throw new IllegalArgumentException("message must be present exactly when success is true");
		}
		if (!success && remainingAttempts < 1) {
			throw new IllegalArgumentException("remainingAttempts must be >= 1 for a retryable result");
		}
	}

	public static ConfirmationResult success(String message) {
		return new ConfirmationResult(true, message, 0);
	}

	public static ConfirmationResult retry(int remainingAttempts) {
		return new ConfirmationResult(false, null, remainingAttempts);
	}
}
