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

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import org.entrystore.rest.springboot.service.auth.EmailValidator;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs against a real Hibernate Validator rather than calling {@code isValid} directly, because the
 * behaviour under test is the interpolated message the caller receives, which only the engine produces.
 */
class ValidEmailValidatorTest {

	private record Body(@ValidEmail String email) {
	}

	// LocalValidatorFactoryBean resolves ValidEmailValidator as a bean so its EmailValidator is injected;
	// the default factory cannot construct it.
	private static AnnotationConfigApplicationContext context;
	private static Validator validator;

	@BeforeAll
	static void startContext() {
		context = new AnnotationConfigApplicationContext(LocalValidatorFactoryBean.class, EmailValidator.class);
		validator = context.getBean(Validator.class);
	}

	@AfterAll
	static void stopContext() {
		context.close();
	}

	@ParameterizedTest(name = "\"{0}\"")
	@ValueSource(strings = {
			"user@example.com", "user@example.notarealtld", "user@[192.168.1.1]", "user@[IPv6:2001:db8::1]"
	})
	void validAddress_producesNoViolation(String email) {
		assertTrue(validator.validate(new Body(email)).isEmpty());
	}

	/**
	 * Null and empty are {@code @NotEmpty}'s business. Reporting them here as well would show the caller
	 * two messages for one omission.
	 */
	@Test
	void nullAddress_isLeftToNotEmpty() {
		assertTrue(validator.validate(new Body(null)).isEmpty());
	}

	@Test
	void emptyAddress_isLeftToNotEmpty() {
		assertTrue(validator.validate(new Body("")).isEmpty());
	}

	/** Present but malformed, so this constraint owns it — unlike null and "". */
	@Test
	void whitespaceOnlyAddress_isRejected() {
		assertEquals(1, validator.validate(new Body(" ")).size());
	}

	@ParameterizedTest(name = "{0}")
	@ValueSource(strings = {
			"user@localhost", "user@example.c", "user@example..com", "user@[999.999.999.999]"
	})
	void invalidAddress_messageNamesTheRejectedAddress(String email) {
		Set<ConstraintViolation<Body>> violations = validator.validate(new Body(email));

		assertEquals(1, violations.size());
		assertEquals("Invalid email address: " + email + ".",
				violations.iterator().next().getMessage());
	}

	/**
	 * The rejected address is echoed into the message and, on the auth endpoints, into an HTML page. If
	 * it were spliced into the message template instead of bound as a parameter, Hibernate Validator
	 * would evaluate it: {@code ${1+1}} would come back as {@code 2}, and an attacker-chosen expression
	 * could read from the validation context.
	 */
	@Test
	void invalidAddressContainingAnExpression_isEchoedLiterallyAndNotEvaluated() {
		Set<ConstraintViolation<Body>> violations = validator.validate(new Body("${1+1}@localhost"));

		assertEquals("Invalid email address: ${1+1}@localhost.",
				violations.iterator().next().getMessage());
	}

	@Test
	void invalidAddressContainingAMessageParameter_isEchoedLiterally() {
		Set<ConstraintViolation<Body>> violations = validator.validate(new Body("{email}@localhost"));

		assertEquals("Invalid email address: {email}@localhost.",
				violations.iterator().next().getMessage());
	}
}
