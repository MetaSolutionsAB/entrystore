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

import org.springframework.security.authentication.DisabledException;

/**
 * Thrown by {@link SecurityConfig#passwordAuthenticationProvider} for a disabled account only after its password
 * matched. {@link FormLoginAuthenticationFailureHandler} answers only this subclass 403, so a plain
 * {@link DisabledException}, which Spring's default provider throws before the password check, still gets the
 * normalized 401.
 */
class DisabledAfterPasswordCheckException extends DisabledException {

	DisabledAfterPasswordCheckException() {
		super("User is disabled");
	}
}
