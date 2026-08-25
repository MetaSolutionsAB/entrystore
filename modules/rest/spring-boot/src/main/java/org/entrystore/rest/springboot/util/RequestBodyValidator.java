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

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import lombok.RequiredArgsConstructor;
import org.entrystore.rest.springboot.model.exception.BadRequestHtmlException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * Applies a body's Bean Validation constraints outside argument resolution.
 *
 * <p>Used by both sign-up endpoints, for two separate reasons — neither of which is "the JSON one could
 * have used {@code @Valid}":
 *
 * <ul>
 * <li>The form-encoded endpoint <em>cannot</em> use {@code @Valid} at all: a field named
 * {@code g-recaptcha-response} has no legal record-component equivalent, so that endpoint assembles the
 * body itself and Spring never validates it.</li>
 * <li>The JSON endpoint deliberately <em>must not</em>. {@code @Valid} runs during argument resolution,
 * ahead of the method body, so it would answer 400 before {@code HttpUtil.checkRequestSize} can answer
 * 413 — and it would answer in JSON, where these endpoints render HTML.</li>
 * </ul>
 *
 * <p>Running the same constraints here keeps one definition of what a valid body is, instead of a second
 * hand-written check that can drift from the annotations.
 */
@Component
@RequiredArgsConstructor
public class RequestBodyValidator {

	private final Validator validator;

	/**
	 * Throws {@link BadRequestHtmlException} carrying the winning violation's message, so the rendered
	 * page is the one {@code AppExceptionHandler} produces for every other failure on these endpoints.
	 *
	 * @param htmlTitle heading for the error page
	 */
	public <T> void assertValid(T body, String htmlTitle) {
		Set<ConstraintViolation<T>> violations = validator.validate(body);
		if (violations.isEmpty()) {
			return;
		}
		// Rejects unconditionally once anything failed. Deriving the throw from the presence of a
		// message would let a violation with no message resolve to "valid", turning a validation gap
		// into an accepted request.
		String message = ValidationErrorMessages.firstFromViolations(body.getClass(), violations)
				.orElse(HttpStatus.BAD_REQUEST.getReasonPhrase());
		throw new BadRequestHtmlException(message, htmlTitle);
	}
}
