/*
 * Copyright (c) 2007-2025 MetaSolutions AB
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

package org.entrystore.repository.security;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class PasswordTest {

	@Test
	public void check_exception() {
		assertThrows(IllegalArgumentException.class, () -> Password.check(null, ""));
		assertThrows(IllegalArgumentException.class, () -> Password.check("", ""));
	}

	@Test
	public void check_ok() {
		assertTrue(Password.check("somePassword", "some$UOe58Q8uFZPwqhsvuVYmqnelHhtzbvA/"));
		assertFalse(Password.check("somePassword", "Password$UOe58Q8uFZPwqhsvuVYmqnelHhtzbvA/"));
	}

	@Test
	public void sha256_ok() {
		assertEquals("uq6QvQZIZ6so8DTW7UDvFGhAEuTAVnGB6u40lKI1hpU=", Password.sha256("somePassword"));
		assertEquals("47DEQpj8HBSa+/TImW+5JCeuQeRkm5NMpJWZG3hSuFU=", Password.sha256(""));
	}

	@Disabled
	@Test
	public void getSaltedHash_ok() {
		// not idempotent
	}

	@Test
	public void conformsToRules_exception() {
		Password.setRules(Password.getDefaultRules());
		assertFalse(Password.conformsToRules(null));
		assertFalse(Password.conformsToRules(""));
	}

	@Test
	public void conformsToRules_default_ok() {
		Password.setRules(Password.getDefaultRules());
		assertTrue(Password.conformsToRules("!ABCdefgh123456*"));
		assertFalse(Password.conformsToRules("!1234567890"));
		assertFalse(Password.conformsToRules("!abcdefgh123456"));
		assertFalse(Password.conformsToRules("!ABCDEFGH123456"));
	}

	@Test
	public void conformsToRules_ok() {
		Password.Rules rules = new Password.Rules();
		rules.lowercase = false;
		rules.uppercase = false;
		rules.number = false;
		rules.symbol =true;
		rules.minLength = 13;
		Password.setRules(rules);
		assertTrue(Password.conformsToRules("!ABCdefgh123456*"));
		assertFalse(Password.conformsToRules("!1234567890"));
		assertTrue(Password.conformsToRules("!abcdefgh1234567890"));
		assertTrue(Password.conformsToRules("!ABCDEFGH123456"));
		assertFalse(Password.conformsToRules("ABCDEFGH123456"));
	}
}
