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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.params.provider.Arguments.arguments;

class HttpQueryRedactorTest {

	@Test
	void nullInput_returnsNull() {
		assertNull(HttpQueryRedactor.redact(null));
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("redactionCases")
	void redact(String name, String input, String expected) {
		assertEquals(expected, HttpQueryRedactor.redact(input));
	}

	static Stream<Arguments> redactionCases() {
		return Stream.of(
				arguments("emptyInput_returnsEmpty", "", ""),
				arguments("singleSensitiveParam_isRedacted", "confirm=abc123", "confirm=***"),
				arguments("singleNonSensitiveParam_isPreserved", "q=hello", "q=hello"),
				arguments("mixedParams_onlySensitiveValuesRedacted",
						"q=hello&confirm=abc&page=2", "q=hello&confirm=***&page=2"),
				// Name preserved verbatim, value replaced.
				arguments("sensitiveParamNameIsMatchedCaseInsensitively", "Confirm=abc", "Confirm=***"),
				arguments("multipleSensitiveParams_areAllRedacted",
						"token=x&secret=y", "token=***&secret=***"),
				// Value hidden even when empty, so presence/absence shape of the token does not itself leak.
				arguments("sensitiveParamWithEmptyValue_isStillRedacted", "confirm=", "confirm=***"),
				// Base64-shaped value with internal '=' — we split on the FIRST '=' only so the full value
				// (including the trailing '=def') is replaced.
				arguments("sensitiveValueContainingEqualsSign_isFullyRedacted", "confirm=ab=def", "confirm=***"),
				// The redactor never decodes; operator sees *** in place of the percent-encoded characters.
				arguments("urlEncodedSensitiveValue_isRedactedWithoutDecoding", "confirm=ab%20cd", "confirm=***"),
				// "confirm" by itself is not a name=value pair, so it stays verbatim.
				arguments("malformedPairWithoutEquals_isPassedThrough", "confirm&page=2", "confirm&page=2"),
				// "mytoken" must NOT be treated as the sensitive "token" — exact match only.
				arguments("substringMatchOfSensitiveName_isNotRedacted",
						"mytoken=abc&token=def", "mytoken=abc&token=***"),
				arguments("casTicket_isRedacted", "ticket=ST-12345-abcde", "ticket=***"),
				arguments("samlRelayState_isRedacted", "RelayState=opaque-id", "RelayState=***"),
				// Servlet container decodes %63onfirm to "confirm" for @RequestParam binding, so the
				// controller consumes the real token; the redactor must also recognise the decoded form
				// or the token leaks to logs while the password reset succeeds. Name is kept on the wire
				// so operators see exactly what the client sent.
				arguments("percentEncodedSensitiveName_isRedacted",
						"%63onfirm=secret-token", "%63onfirm=***"),
				// %43 → 'C'; once decoded the name is "Confirm", which matches case-insensitively.
				arguments("percentEncodedSensitiveName_caseInsensitiveOnDecodedForm",
						"%43onfirm=secret-token", "%43onfirm=***"),
				// "%ZZ" is a malformed escape; URLDecoder throws IllegalArgumentException. The decodeName
				// fallback returns the raw name, which is not sensitive — pair passes through.
				arguments("malformedPercentEscape_doesNotThrowAndPassesThroughForNonSensitiveName",
						"q%ZZ=hello", "q%ZZ=hello"),
				// Legacy HTML 4 / RFC 1866 separator. Output normalises to '&' — accepted trade-off
				// for log/feed-link sinks. Without this fix, "q=hello;confirm=secret" would be a single
				// pair (name=q, value=hello;confirm=secret) and the token would leak.
				arguments("semicolonSeparator_isRecognisedAsPairBoundary",
						"q=hello;confirm=secret", "q=hello&confirm=***"),
				arguments("mixedAmpAndSemicolonSeparators_areBothRecognised",
						"q=hello;confirm=abc&token=def", "q=hello&confirm=***&token=***"),
				// Position-shape regression sentinels: defend the loop boundaries against a future
				// refactor that drops the leading or trailing pair.
				arguments("sensitiveParamAtFirstPosition_isRedacted",
						"confirm=abc&q=hello", "confirm=***&q=hello"),
				arguments("sensitiveParamAtLastPosition_isRedacted",
						"q=hello&token=abc", "q=hello&token=***"),
				// "=foo" has no name at all; the decoded "" is not in the sensitive set.
				arguments("emptyNamePair_isPassedThrough", "=foo&q=bar", "=foo&q=bar"),
				// "a&&b" splits to ["a", "", "b"] with limit=-1; output round-trips the shape.
				arguments("consecutiveAmpersands_preserveEmptySegments", "confirm=x&&q=y", "confirm=***&&q=y"),
				arguments("leadingAndTrailingAmpersands_arePreserved", "&confirm=x&", "&confirm=***&")
		);
	}
}
