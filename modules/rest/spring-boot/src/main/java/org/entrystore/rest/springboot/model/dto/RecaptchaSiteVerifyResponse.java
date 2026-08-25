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

package org.entrystore.rest.springboot.model.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Objects;

/**
 * Response of the reCaptcha {@code siteverify} endpoint.
 *
 * <p>{@code ignoreUnknown} because the shape is Google's to change: the response also carries
 * {@code challenge_ts}, {@code hostname} and, for reCaptcha Enterprise, further members none of which
 * this application reads. Failing on them would turn an upstream addition into a sign-up outage, and
 * relying on the application mapper's lenient default would make that depend on configuration
 * elsewhere.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RecaptchaSiteVerifyResponse(
		/**
		 * Boxed, not {@code boolean}: Jackson 3 enables {@code FAIL_ON_NULL_FOR_PRIMITIVES}, and a
		 * creator parameter is handed null both when the member is absent and when it is an explicit
		 * {@code null}, so a primitive here throws while the body is being read rather than resolving to
		 * "not verified" — turning a malformed upstream reply into a 500 instead of a rejected captcha.
		 * Verified against this build with a {@code {}} body.
		 *
		 * <p>The constructor folds both cases to false, so the accessor never returns null and callers
		 * can unbox it safely.
		 */
		Boolean success,
		/**
		 * Machine-readable reasons for a rejection, e.g. {@code invalid-input-secret} for a
		 * misconfigured private key versus {@code timeout-or-duplicate} for a stale token. Absent from a
		 * successful response.
		 */
		@JsonProperty("error-codes") List<String> errorCodes) {

	public RecaptchaSiteVerifyResponse {
		// Absent or explicitly null means not verified, and folding it here is what lets the accessor
		// return a value that never unboxes to a NullPointerException at the call site.
		success = success != null && success;
		// Nulls filtered rather than passed to List.copyOf, which rejects them: a null element would
		// throw inside the deserializer, and that failure surfaces from RestClient as a plain
		// RestClientException — the same shape as an unreadable body, so it would answer 500 rather than
		// a rejected captcha.
		errorCodes = errorCodes == null ? List.of()
				: errorCodes.stream().filter(Objects::nonNull).toList();
	}
}
