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

package org.entrystore.rest.springboot.model.validation;

/**
 * User-facing validation messages shared between declarative constraints and {@code AuthService}.
 *
 * <p>Only the messages with more than one producer live here. The sign-up path reports a malformed
 * address through {@link ValidEmail} while the password-reset path reports it from the service, and
 * the two must render identically — the integration tests assert the exact string. Keeping separate
 * copies is what would let them drift.
 */
public final class AuthValidationMessages {

	/**
	 * Deliberately identical for every missing field. Naming the absent parameter would tell an
	 * unauthenticated caller which of them the endpoint accepts.
	 */
	public static final String PARAMETERS_MISSING = "One or more parameters are missing.";

	/**
	 * {@code {email}} is a Bean Validation message parameter, substituted by
	 * {@link ValidEmailValidator}. Service-side callers substitute it themselves.
	 */
	public static final String INVALID_EMAIL = "Invalid email address: {email}.";

	private AuthValidationMessages() {
	}
}
