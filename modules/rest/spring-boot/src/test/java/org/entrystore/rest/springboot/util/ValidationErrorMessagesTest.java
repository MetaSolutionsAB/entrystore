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

import org.junit.jupiter.api.Test;
import org.springframework.validation.FieldError;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ValidationErrorMessagesTest {

	private record Body(String email, String password, String firstName) {
	}

	private static FieldError error(String field, String message) {
		return new FieldError("body", field, null, false, null, null, message);
	}

	/**
	 * The point of the class: the reported message must not depend on the order the validator happened
	 * to produce violations in. Both orderings of the same two failures resolve to the earlier-declared
	 * field.
	 */
	@Test
	void severalFields_reportTheEarliestDeclaredOneRegardlessOfInputOrder() {
		List<FieldError> passwordFirst = List.of(error("password", "pw"), error("email", "em"));
		List<FieldError> emailFirst = List.of(error("email", "em"), error("password", "pw"));

		assertEquals(Optional.of("em"),
				ValidationErrorMessages.firstFromFieldErrors(Body.class, passwordFirst));
		assertEquals(Optional.of("em"),
				ValidationErrorMessages.firstFromFieldErrors(Body.class, emailFirst));
	}

	@Test
	void laterFieldsLoseToEarlierOnes() {
		List<FieldError> errors = List.of(error("firstName", "fn"), error("password", "pw"));

		assertEquals(Optional.of("pw"),
				ValidationErrorMessages.firstFromFieldErrors(Body.class, errors));
	}

	@Test
	void singleField_reportsItsMessage() {
		assertEquals(Optional.of("fn"),
				ValidationErrorMessages.firstFromFieldErrors(Body.class, List.of(error("firstName", "fn"))));
	}

	@Test
	void noErrors_yieldsEmptySoCallersCanFallBack() {
		assertTrue(ValidationErrorMessages.firstFromFieldErrors(Body.class, List.of()).isEmpty());
	}

	/**
	 * A field name that is not a component of the record sorts after every component rather than being
	 * dropped, so it is still reported when nothing else failed.
	 *
	 * <p>Note this is <em>not</em> how Spring reports a class-level constraint: those arrive as global
	 * {@code ObjectError}s with an empty property path and never appear in {@code getFieldErrors()} at
	 * all, which is why {@code AppExceptionHandler} falls back to {@code getGlobalErrors()} separately.
	 * This case only covers a field whose name does not match a component.
	 */
	@Test
	void fieldThatIsNotAComponent_sortsLastButIsStillReported() {
		List<FieldError> both = List.of(error("notAComponent", "other"), error("password", "pw"));

		assertEquals(Optional.of("pw"),
				ValidationErrorMessages.firstFromFieldErrors(Body.class, both));
		assertEquals(Optional.of("other"),
				ValidationErrorMessages.firstFromFieldErrors(Body.class, List.of(error("notAComponent", "other"))));
	}

	/**
	 * Ties must not fall back to encounter order, or the reported message would vary between runs for a
	 * body that is not a record — the shape {@code ConstraintViolationException} presents for a validated
	 * method's parameters, where there are no components to order by.
	 */
	@Test
	void nonRecordBody_breaksTiesOnThePathSoTheWinnerIsStable() {
		List<FieldError> oneOrder = List.of(error("zebra", "z"), error("alpha", "a"));
		List<FieldError> otherOrder = List.of(error("alpha", "a"), error("zebra", "z"));

		assertEquals(Optional.of("a"),
				ValidationErrorMessages.firstFromFieldErrors(String.class, oneOrder));
		assertEquals(Optional.of("a"),
				ValidationErrorMessages.firstFromFieldErrors(String.class, otherOrder));
	}

	/**
	 * Ordering by record components cannot apply to a non-record body, so every field ties. The call must
	 * still return a message rather than failing, since the caller uses it to reject the request.
	 */
	@Test
	void nonRecordBody_stillReportsAMessage() {
		assertEquals(Optional.of("pw"),
				ValidationErrorMessages.firstFromFieldErrors(String.class, List.of(error("password", "pw"))));
	}

	/**
	 * A violation on a nested member is ordered by the top-level field that contains it. Asserted the
	 * way round that can actually fail: {@code firstName} is declared last, so if the leading segment
	 * were not extracted the path would match no component, sort after {@code password}, and the
	 * password message would win instead.
	 */
	@Test
	void nestedPropertyPath_isOrderedByItsLeadingSegment() {
		List<FieldError> errors = List.of(error("firstName.length", "nested"), error("password", "pw"));

		assertEquals(Optional.of("pw"),
				ValidationErrorMessages.firstFromFieldErrors(Body.class, errors));

		List<FieldError> nestedOnEarlierField = List.of(error("email.local", "nested"), error("password", "pw"));
		assertEquals(Optional.of("nested"),
				ValidationErrorMessages.firstFromFieldErrors(Body.class, nestedOnEarlierField));
	}
}
