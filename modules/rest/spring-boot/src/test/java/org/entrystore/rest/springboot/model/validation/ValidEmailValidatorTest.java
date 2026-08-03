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
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.entrystore.rest.springboot.service.auth.EmailValidator;
import org.hibernate.validator.HibernateValidator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

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

	private final ValidatorFactory factory = Validation.byProvider(HibernateValidator.class)
			.configure()
			// Mirrors Spring's SpringConstraintValidatorFactory, which resolves ConstraintValidator
			// implementations as beans; without this the no-arg-constructor default cannot build one.
			.constraintValidatorFactory(new SingleValidatorFactory(new EmailValidator()))
			.buildValidatorFactory();

	private final Validator validator = factory.getValidator();

	@AfterEach
	void tearDown() {
		factory.close();
	}

	@ParameterizedTest(name = "\"{0}\"")
	@ValueSource(strings = {"user@example.com", "user@example.notarealtld", "user@[192.168.1.1]"})
	void validAddress_producesNoViolation(String email) {
		assertTrue(validator.validate(new Body(email)).isEmpty());
	}

	/**
	 * Null and empty are {@code @NotEmpty}'s business. Reporting them here as well would show the caller
	 * two messages for one omission.
	 */
	@ParameterizedTest(name = "\"{0}\"")
	@NullSource
	@ValueSource(strings = {""})
	void absentAddress_isLeftToNotEmpty(String email) {
		assertTrue(validator.validate(new Body(email)).isEmpty());
	}

	/** Present but malformed, so this constraint owns it — unlike null and "". */
	@Test
	void whitespaceOnlyAddress_isRejected() {
		assertEquals(1, validator.validate(new Body(" ")).size());
	}

	@Test
	void invalidAddress_messageNamesTheRejectedAddress() {
		Set<ConstraintViolation<Body>> violations = validator.validate(new Body("user@localhost"));

		assertEquals("Invalid email address: user@localhost.",
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

	/** Hands the one validator instance this suite needs, standing in for Spring's bean lookup. */
	private record SingleValidatorFactory(EmailValidator emailValidator)
			implements jakarta.validation.ConstraintValidatorFactory {

		@Override
		@SuppressWarnings("unchecked")
		public <T extends jakarta.validation.ConstraintValidator<?, ?>> T getInstance(Class<T> key) {
			if (key == ValidEmailValidator.class) {
				return (T) new ValidEmailValidator(emailValidator);
			}
			try {
				return key.getDeclaredConstructor().newInstance();
			} catch (ReflectiveOperationException e) {
				throw new IllegalStateException("Cannot instantiate " + key, e);
			}
		}

		@Override
		public void releaseInstance(jakarta.validation.ConstraintValidator<?, ?> instance) {
			// nothing held
		}
	}
}
