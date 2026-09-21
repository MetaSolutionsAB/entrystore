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

import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EmailValidatorTest {

	private static ValidatorFactory factory;
	private static EmailValidator emailValidator;

	@BeforeAll
	static void buildValidator() {
		factory = Validation.buildDefaultValidatorFactory();
		emailValidator = new EmailValidator(factory.getValidator());
	}

	@AfterAll
	static void closeFactory() {
		factory.close();
	}

	@ParameterizedTest(name = "{0}")
	@ValueSource(strings = {
			"user@example.com",
			"user@example.co", // two-character suffix, shortest accepted
			"first.last@sub.example.notarealtld",
			"first.last+tag@example.com",
			"user@exämple.se",
			"\"user@home\"@example.com",
			"user@[192.168.1.1]",
			"user@[255.255.255.255]",
			"user@[IPv6:2001:db8::1]",
			"user@[ipv6:::1]" // tag matched case-insensitively
	})
	void acceptsValidEmails(String email) {
		assertTrue(emailValidator.isValid(email), email + " should be valid");
	}

	@ParameterizedTest(name = "\"{0}\"") // quoted so the empty-string case has a non-blank display name
	@NullAndEmptySource
	@ValueSource(strings = {
			" ",
			" user@example.com", // surrounding whitespace, tolerated by the removed Commons validator
			"user@example.com ",
			"user@[999.999.999.999]",
			"user@[256.0.0.1]",
			"user@[192.168.001.1]", // leading zero passes @Email, rejected by InetAddresses
			"user@[127.1]", // short-form IPv4
			"user@[example.com]",
			"user@[IPv6:2001:db8:::1]",
			"user@[IPv6:192.168.1.1]", // IPv4 behind the IPv6 tag
			"user@[IPv6:fe80::1%eth0]", // zone ID names a host-local scope
			"user@[2001:db8::1]", // IPv6 without the required tag
			"user@",
			"@example.com",
			"user@localhost",
			"user@.com",
			"user@example.c", // one-character suffix
			"user@example.com.",
			"user@example..com",
			"user@-example.com",
			"user@example-.com",
			"user@example com",
			"first..last@example.com",
			"\"user@home\"@localhost", // quoted local part passes @Email, dot rule rejects the domain
			"user@example.com\n"
	})
	void rejectsInvalidEmails(String email) {
		assertFalse(emailValidator.isValid(email), email + " should be invalid");
	}

	@Test
	void acceptsLocalPartAtTheLengthLimit() {
		assertTrue(emailValidator.isValid("a".repeat(64) + "@example.com"));
	}

	@Test
	void rejectsLocalPartAboveTheLengthLimit() {
		assertFalse(emailValidator.isValid("a".repeat(65) + "@example.com"));
	}

	@Test
	void rejectsDomainLabelAboveTheLengthLimit() {
		assertFalse(emailValidator.isValid("user@" + "a".repeat(64) + ".com"));
	}
}
