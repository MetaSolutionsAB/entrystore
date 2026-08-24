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
import org.springframework.validation.FieldError;

import java.lang.reflect.RecordComponent;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * Picks the one violation to report when several constraints fail together.
 *
 * <p>Endpoints that answer with a single message need a defined winner. Bean Validation returns
 * violations as a {@code Set} in unspecified order, so reporting "the first" would vary between runs
 * for the same request.
 *
 * <p><b>The rule:</b> earliest-declared record component wins; ties break on the property path, so the
 * outcome is total and reproducible even for a body that is not a record (where every path ties) or a
 * validated method's parameters (which have no declaration order to consult).
 *
 * <p>Component declaration order is therefore a client-visible contract for these bodies: reordering
 * the components of a request-body record changes which message a caller receives. {@code
 * SignupRequestBody} declares email, password, firstName, lastName in the order their messages should
 * win.
 */
public final class ValidationErrorMessages {

	private ValidationErrorMessages() {
	}

	/**
	 * For violations straight from a {@code Validator} — a programmatically validated body, or the set
	 * carried by a {@code ConstraintViolationException} from a validated method's parameters.
	 *
	 * <p>Wildcarded rather than generic in the root bean type: {@code
	 * ConstraintViolationException.getConstraintViolations()} is a {@code Set<ConstraintViolation<?>>},
	 * which no single type variable can satisfy.
	 */
	public static Optional<String> firstFromViolations(Class<?> bodyType,
													   Set<? extends ConstraintViolation<?>> violations) {
		return firstByDeclarationOrder(bodyType, violations,
				violation -> violation.getPropertyPath().toString(), ConstraintViolation::getMessage);
	}

	/**
	 * For Spring's binding result, where violations have already become {@link FieldError}s.
	 *
	 * <p>{@code getField()} is also a path — a constraint on a nested member reports {@code
	 * "address.city"} — so it goes through the same leading-segment reduction as a violation path.
	 */
	public static Optional<String> firstFromFieldErrors(Class<?> bodyType, List<FieldError> fieldErrors) {
		return firstByDeclarationOrder(bodyType, fieldErrors,
				FieldError::getField, FieldError::getDefaultMessage);
	}

	/**
	 * One implementation for both entry points, so the winner-selection rule cannot be changed on one
	 * path and left stale on the other.
	 */
	private static <E> Optional<String> firstByDeclarationOrder(Class<?> bodyType, Collection<E> errors,
															   Function<E, String> path,
															   Function<E, String> message) {
		// Resolved once per call rather than inside the comparator: Class.getRecordComponents() is an
		// uncached native call that allocates a fresh array plus one object per component, and a
		// comparator is invoked O(n) times per selection — on a path an unauthenticated caller can drive
		// by sending invalid bodies.
		Map<String, Integer> declarationOrder = declarationOrder(bodyType);
		Comparator<E> byDeclarationThenPath = Comparator
				.<E, Integer>comparing(error -> declarationIndex(declarationOrder, rootField(path.apply(error))))
				.thenComparing(error -> rootField(path.apply(error)));
		return errors.stream().min(byDeclarationThenPath).map(message);
	}

	/**
	 * Leading segment of a property path, so a violation on a nested member is ordered by the top-level
	 * field that contains it.
	 */
	private static String rootField(String propertyPath) {
		int firstSeparator = propertyPath.indexOf('.');
		return firstSeparator < 0 ? propertyPath : propertyPath.substring(0, firstSeparator);
	}

	/** Empty for a non-record type, which leaves every field tied and the path tie-break deciding. */
	private static Map<String, Integer> declarationOrder(Class<?> bodyType) {
		RecordComponent[] components = bodyType.getRecordComponents();
		if (components == null) {
			return Map.of();
		}
		Map<String, Integer> order = new HashMap<>();
		for (int index = 0; index < components.length; index++) {
			order.put(components[index].getName(), index);
		}
		return order;
	}

	/**
	 * A field that is not a component of this record — a class-level constraint, say — sorts after every
	 * component, so it is reported only when nothing else failed.
	 */
	private static int declarationIndex(Map<String, Integer> declarationOrder, String field) {
		Integer index = declarationOrder.get(field);
		return index != null ? index : declarationOrder.size();
	}
}
