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

import java.security.SecureRandom;
import java.util.Base64;

/**
 * The handle that names a login session to clients. The session id is carried only by the session cookie, so a
 * handle is random and not derived from it. Every password login gets a new one, which the session keeps until it
 * ends or logs in again; SAML, CAS and OIDC sessions have none.
 */
public final class SessionHandle {

	private static final int BYTES = 16;
	private static final SecureRandom RANDOM = new SecureRandom();
	private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

	private SessionHandle() {
	}

	/** @return a new handle of 128 random bits as 22 base64url characters */
	public static String generate() {
		byte[] random = new byte[BYTES];
		RANDOM.nextBytes(random);
		return ENCODER.encodeToString(random);
	}
}
