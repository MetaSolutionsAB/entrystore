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

package org.entrystore.rest.springboot.util;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorFactory;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.entrystore.rest.springboot.model.api.SignupRequestBody;
import org.entrystore.rest.springboot.model.exception.BadRequestHtmlException;
import org.entrystore.rest.springboot.model.validation.AuthValidationMessages;
import org.entrystore.rest.springboot.model.validation.ValidEmailValidator;
import org.entrystore.rest.springboot.service.auth.EmailValidator;
import org.hibernate.validator.HibernateValidator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@code RequestBodyValidator} is the only thing enforcing every field constraint on both
 * {@code /auth/signup} endpoints, so if it passed silently the whole sign-up contract would be
 * unenforced with no test failing. Runs against a real Hibernate Validator over a real
 * {@link SignupRequestBody} rather than a mock, since the behaviour under test is which message a body
 * with several faults produces.
 */
class RequestBodyValidatorTest {

	private static final String TITLE = "Sign-up";

	private final ValidatorFactory factory = Validation.byProvider(HibernateValidator.class)
			.configure()
			.constraintValidatorFactory(new EmailValidatorFactory())
			.buildValidatorFactory();

	private final Validator validator = factory.getValidator();
	private final RequestBodyValidator requestBodyValidator = new RequestBodyValidator(validator);

	@AfterEach
	void tearDown() {
		factory.close();
	}

	private static SignupRequestBody body(String email, String password, String first, String last) {
		return new SignupRequestBody(email, password, first, last, null, null, null, Map.of());
	}

	@Test
	void validBody_passes() {
		assertDoesNotThrow(() -> requestBodyValidator.assertValid(
				body("user@example.com", "secret12", "Ada", "Lovelace"), TITLE));
	}

	@Test
	void missingField_isReportedWithoutNamingWhichOne() {
		BadRequestHtmlException thrown = assertThrows(BadRequestHtmlException.class,
				() -> requestBodyValidator.assertValid(
						body("user@example.com", "", "Ada", "Lovelace"), TITLE));

		assertEquals(AuthValidationMessages.PARAMETERS_MISSING, thrown.getMessage());
		assertEquals(TITLE, thrown.getTitle());
	}

	@Test
	void malformedAddress_isReportedWithTheRejectedAddress() {
		BadRequestHtmlException thrown = assertThrows(BadRequestHtmlException.class,
				() -> requestBodyValidator.assertValid(
						body("user@localhost", "secret12", "Ada", "Lovelace"), TITLE));

		assertEquals("Invalid email address: user@localhost.", thrown.getMessage());
	}

	/**
	 * The reason {@code ValidationErrorMessages} exists. Two faults with <em>different</em> messages, and
	 * email is declared first, so its message must win — repeatably, not according to whatever order the
	 * validator's {@code Set} happened to iterate in. No integration test covers this: they each break
	 * exactly one field, and the four missing-field cases all share one message.
	 */
	@Test
	void addressAndPresenceBothFailing_reportsTheAddressBecauseEmailIsDeclaredFirst() {
		for (int attempt = 0; attempt < 20; attempt++) {
			BadRequestHtmlException thrown = assertThrows(BadRequestHtmlException.class,
					() -> requestBodyValidator.assertValid(
							body("user@localhost", "secret12", "Ada", ""), TITLE));

			assertEquals("Invalid email address: user@localhost.", thrown.getMessage(),
					"the earliest-declared failing field must win on every run");
		}
	}

	@Test
	void severalMissingFields_reportTheSharedMessage() {
		BadRequestHtmlException thrown = assertThrows(BadRequestHtmlException.class,
				() -> requestBodyValidator.assertValid(body(null, null, null, null), TITLE));

		assertEquals(AuthValidationMessages.PARAMETERS_MISSING, thrown.getMessage());
	}

	/** Whitespace is supplied as far as {@code @NotEmpty} goes, so the address rule is what rejects it. */
	@Test
	void whitespaceAddress_isReportedAsMalformedNotMissing() {
		BadRequestHtmlException thrown = assertThrows(BadRequestHtmlException.class,
				() -> requestBodyValidator.assertValid(
						body(" ", "secret12", "Ada", "Lovelace"), TITLE));

		assertEquals("Invalid email address:  .", thrown.getMessage());
	}

	/** Stands in for Spring's SpringConstraintValidatorFactory, which resolves validators as beans. */
	private static final class EmailValidatorFactory implements ConstraintValidatorFactory {

		@Override
		@SuppressWarnings("unchecked")
		public <T extends ConstraintValidator<?, ?>> T getInstance(Class<T> key) {
			if (key == ValidEmailValidator.class) {
				return (T) new ValidEmailValidator(new EmailValidator());
			}
			try {
				return key.getDeclaredConstructor().newInstance();
			} catch (ReflectiveOperationException e) {
				throw new IllegalStateException("Cannot instantiate " + key, e);
			}
		}

		@Override
		public void releaseInstance(ConstraintValidator<?, ?> instance) {
			// nothing held
		}
	}
}
