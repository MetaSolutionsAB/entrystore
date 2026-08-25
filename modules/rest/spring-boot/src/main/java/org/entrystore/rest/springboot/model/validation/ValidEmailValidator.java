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

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import lombok.RequiredArgsConstructor;
import org.entrystore.rest.springboot.service.auth.EmailValidator;
import org.hibernate.validator.constraintvalidation.HibernateConstraintValidatorContext;

@RequiredArgsConstructor
public class ValidEmailValidator implements ConstraintValidator<ValidEmail, String> {

	private final EmailValidator emailValidator;

	@Override
	public boolean isValid(String value, ConstraintValidatorContext context) {
		// Only null and "" pass, not all-whitespace: a whitespace-only address is present as far as
		// @NotEmpty is concerned, so this constraint is the one that has to reject it. EmailValidator
		// itself rejects null and "", so this cannot delegate straight through.
		if (value == null || value.isEmpty()) {
			return true;
		}
		if (emailValidator.isValid(value)) {
			return true;
		}

		// The rejected address is echoed back to the caller, so it is bound as a message *parameter*
		// instead of being concatenated into the template. Parameters are substituted verbatim, whereas
		// a value spliced into a template is itself subject to interpolation. Building a custom
		// violation pins that down further: custom violations default to
		// ExpressionLanguageFeatureLevel.NONE, so no expression in the template can be evaluated.
		HibernateConstraintValidatorContext hibernateContext =
				context.unwrap(HibernateConstraintValidatorContext.class);
		hibernateContext.disableDefaultConstraintViolation();
		hibernateContext.addMessageParameter("email", value);
		hibernateContext
				.buildConstraintViolationWithTemplate(context.getDefaultConstraintMessageTemplate())
				.addConstraintViolation();
		return false;
	}
}
