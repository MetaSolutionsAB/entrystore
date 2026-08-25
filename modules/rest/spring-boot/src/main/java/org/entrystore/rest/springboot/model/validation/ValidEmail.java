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

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.ANNOTATION_TYPE;
import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.ElementType.RECORD_COMPONENT;

/**
 * Rejects malformed email addresses using
 * {@link org.entrystore.rest.springboot.service.auth.EmailValidator}, so a declarative constraint and
 * a service-layer check cannot drift apart on what counts as a valid address.
 *
 * <p>Deliberately <em>not</em> Bean Validation's own {@code @Email}: that constraint accepts anything
 * with an {@code @} and a non-empty domain, so {@code user@localhost} and {@code user@example.c} pass
 * it and fail here. Swapping it in would silently widen what the sign-up endpoint accepts.
 *
 * <p>Null and empty values pass. Presence is a separate concern, so pair this with {@code @NotEmpty}
 * on a required field; without that split a missing address would report both "missing parameter" and
 * "invalid address" for one omission. An all-whitespace value is <em>not</em> exempt — it is present
 * but malformed, and reporting it as missing would contradict the pre-existing behaviour.
 */
@Documented
@Target({FIELD, METHOD, PARAMETER, RECORD_COMPONENT, ANNOTATION_TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = ValidEmailValidator.class)
public @interface ValidEmail {

	/**
	 * {@code {email}} is substituted with the rejected address by {@link ValidEmailValidator}, which
	 * binds it as a message parameter rather than splicing it into this template.
	 */
	String message() default AuthValidationMessages.INVALID_EMAIL;

	Class<?>[] groups() default {};

	Class<? extends Payload>[] payload() default {};
}
