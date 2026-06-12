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

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EmailValidatorTest {

	private final EmailValidator emailValidator = new EmailValidator();

	@ParameterizedTest(name = "{0}")
	@ValueSource(strings = {
			"user@example.com",
			"user@example.co", // two-character TLD, shortest accepted
			"first.last@sub.example.notarealtld", // TLD absent from Commons Validator's whitelist — this validator consults no TLD list
			"user@[192.168.1.1]" // IP address domain in brackets
	})
	void acceptsValidEmails(String email) {
		assertTrue(emailValidator.isValid(email), email + " should be valid");
	}

	@ParameterizedTest(name = "\"{0}\"") // quoted so the empty-string case has a non-blank display name
	@NullAndEmptySource // pins null/blank rejection ahead of the ENTRYSTORE-909 library swap
	@ValueSource(strings = {
			"user@[999.999.999.999]", // bracketed domain that is not a valid IP address
			"user@", // missing domain
			"user@localhost", // no dot in domain
			"user@.com", // domain starts with a dot
			"user@example.c" // one-character TLD
	})
	void rejectsInvalidEmails(String email) {
		assertFalse(emailValidator.isValid(email), email + " should be invalid");
	}

}
