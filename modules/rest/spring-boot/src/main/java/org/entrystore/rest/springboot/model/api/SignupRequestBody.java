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

package org.entrystore.rest.springboot.model.api;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonSetter;
import jakarta.validation.constraints.NotEmpty;
import org.entrystore.rest.springboot.model.validation.AuthValidationMessages;
import org.entrystore.rest.springboot.model.validation.ValidEmail;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Body of {@code POST /auth/signup}, in both its JSON and form-encoded forms.
 *
 * <p>{@code @NotEmpty} rather than {@code @NotBlank} throughout: the checks this replaced tested
 * {@code StringUtils.isNotEmpty}, so an all-whitespace value counts as supplied and is rejected
 * further along by the rule that actually applies to it — a malformed address for {@code email}, the
 * configured password rules for {@code password}, the name rules for the two name fields. Switching to
 * {@code @NotBlank} would report all four as missing instead.
 *
 * <p>Password length and name format are checked in {@code AuthService} rather than here, because both
 * need more than the field's own value: the length applies to the trimmed password, and the name rules
 * span two fields.
 *
 * <p>Message precedence, when a body breaks several rules at once: every constraint on this record
 * first — presence and address format, resolved in component-declaration order — and only then the
 * service-side password length and name format. Component order is therefore a client-visible contract;
 * reordering the components below changes which message a caller receives.
 *
 * <p>This is <em>not</em> the precedence the sequential {@code if} chain had. That chain interleaved
 * per field, checking the password's length before the two names' presence, so a body with both a short
 * password and a missing surname answered "The password must conform to the configured rules." and now
 * answers "One or more parameters are missing.". Every single-fault body is unaffected, which is why no
 * integration test moves.
 */
public record SignupRequestBody(
		@NotEmpty(message = AuthValidationMessages.PARAMETERS_MISSING)
		@ValidEmail
		String email,

		@NotEmpty(message = AuthValidationMessages.PARAMETERS_MISSING)
		String password,

		@JsonSetter("firstname")
		@NotEmpty(message = AuthValidationMessages.PARAMETERS_MISSING)
		String firstName,

		@JsonSetter("lastname")
		@NotEmpty(message = AuthValidationMessages.PARAMETERS_MISSING)
		String lastName,

		@JsonSetter("urlsuccess") String urlSuccess,
		@JsonSetter("urlfailure") String urlFailure,
		@JsonSetter("grecaptcharesponse") String rcResponseV2,

		/**
		 * Body members that match none of the components above. Sign-up carries caller-defined
		 * {@code custom_*} properties onto the new user, so the unrecognised remainder is data, not
		 * something to reject or discard.
		 */
		@JsonAnySetter Map<String, String> extraProperties) {

	public SignupRequestBody {
		// Lower-cased here rather than only in SignupInfo.setEmail so that the address echoed by a
		// @ValidEmail violation is the same one the rest of the flow uses. Locale.ROOT, matching the
		// sign-up domain whitelist comparison: under a locale whose lower-casing differs from the root
		// one (Turkish dotless i) a default-locale fold would produce a domain the whitelist never
		// matches.
		email = email == null ? null : email.toLowerCase(Locale.ROOT);
		// Never null, so callers can iterate without a guard. Jackson omits the any-setter map entirely
		// when the body carries no unrecognised members.
		//
		// Null values are dropped rather than handed to Map.copyOf, which rejects them. A client that
		// serialises its empty optional fields sends {"custom_dept": null}; the any-setter stores that
		// null, and copyOf would throw inside the record constructor, which Jackson rewraps until it
		// reaches the caller as a bare JSON "Bad Request" — refusing a sign-up that the previous
		// untyped Map binding completed, and in JSON where this endpoint answers HTML.
		extraProperties = extraProperties == null ? Map.of() : extraProperties.entrySet().stream()
				.filter(property -> property.getValue() != null)
				.collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue));
	}

	/**
	 * Convenience for the form-encoded endpoint, which cannot bind {@code g-recaptcha-response} to a
	 * record component and so assembles the body itself.
	 */
	public static SignupRequestBody fromFormParameters(Map<String, String> parameters) {
		Map<String, String> extras = new LinkedHashMap<>(parameters);
		extras.keySet().removeAll(Set.of("email", "password", "firstname", "lastname",
				"urlsuccess", "urlfailure", "g-recaptcha-response"));
		return new SignupRequestBody(
				parameters.get("email"),
				parameters.get("password"),
				parameters.get("firstname"),
				parameters.get("lastname"),
				parameters.get("urlsuccess"),
				parameters.get("urlfailure"),
				parameters.get("g-recaptcha-response"),
				extras);
	}
}
