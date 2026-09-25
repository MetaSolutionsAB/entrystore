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

import jakarta.validation.Validator;
import org.entrystore.rest.springboot.model.api.SignupRequestBody;
import org.entrystore.rest.springboot.model.exception.BadRequestHtmlException;
import org.entrystore.rest.springboot.model.validation.AuthValidationMessages;
import org.entrystore.rest.springboot.service.auth.EmailValidator;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

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

	// LocalValidatorFactoryBean resolves ValidEmailValidator as a bean so its EmailValidator is injected;
	// the default factory cannot construct it.
	private static AnnotationConfigApplicationContext context;
	private static Validator validator;

	private final RequestBodyValidator requestBodyValidator = new RequestBodyValidator(validator);

	@BeforeAll
	static void startContext() {
		context = new AnnotationConfigApplicationContext(LocalValidatorFactoryBean.class, EmailValidator.class);
		validator = context.getBean(Validator.class);
	}

	@AfterAll
	static void stopContext() {
		context.close();
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

	@Test
	void emptyAddress_isReportedAsMissing() {
		BadRequestHtmlException thrown = assertThrows(BadRequestHtmlException.class,
				() -> requestBodyValidator.assertValid(
						body("", "secret12", "Ada", "Lovelace"), TITLE));

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
}
