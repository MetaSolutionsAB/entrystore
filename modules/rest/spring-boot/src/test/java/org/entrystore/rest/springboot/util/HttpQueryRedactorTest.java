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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class HttpQueryRedactorTest {

	@Test
	void nullInput_returnsNull() {
		assertNull(HttpQueryRedactor.redact(null));
	}

	@Test
	void emptyInput_returnsEmpty() {
		assertEquals("", HttpQueryRedactor.redact(""));
	}

	@Test
	void singleSensitiveParam_isRedacted() {
		assertEquals("confirm=***", HttpQueryRedactor.redact("confirm=abc123"));
	}

	@Test
	void singleNonSensitiveParam_isPreserved() {
		assertEquals("q=hello", HttpQueryRedactor.redact("q=hello"));
	}

	@Test
	void mixedParams_onlySensitiveValuesRedacted() {
		assertEquals("q=hello&confirm=***&page=2",
				HttpQueryRedactor.redact("q=hello&confirm=abc&page=2"));
	}

	@Test
	void sensitiveParamNameIsMatchedCaseInsensitively() {
		// Name preserved verbatim, value replaced.
		assertEquals("Confirm=***", HttpQueryRedactor.redact("Confirm=abc"));
	}

	@Test
	void multipleSensitiveParams_areAllRedacted() {
		assertEquals("token=***&secret=***",
				HttpQueryRedactor.redact("token=x&secret=y"));
	}

	@Test
	void sensitiveParamWithEmptyValue_isStillRedacted() {
		// We hide the value even when it's empty, so the presence/absence
		// shape of the token does not itself leak.
		assertEquals("confirm=***", HttpQueryRedactor.redact("confirm="));
	}

	@Test
	void sensitiveValueContainingEqualsSign_isFullyRedacted() {
		// Base64-shaped value with internal '=' — we split on the FIRST '='
		// only so the full value (including the trailing '=def') is replaced.
		assertEquals("confirm=***", HttpQueryRedactor.redact("confirm=ab=def"));
	}

	@Test
	void urlEncodedSensitiveValue_isRedactedWithoutDecoding() {
		// The redactor never decodes; operator sees *** in place of the
		// percent-encoded characters.
		assertEquals("confirm=***", HttpQueryRedactor.redact("confirm=ab%20cd"));
	}

	@Test
	void malformedPairWithoutEquals_isPassedThrough() {
		// "confirm" by itself is not a name=value pair, so it stays verbatim.
		assertEquals("confirm&page=2", HttpQueryRedactor.redact("confirm&page=2"));
	}

	@Test
	void substringMatchOfSensitiveName_isNotRedacted() {
		// "mytoken" must NOT be treated as the sensitive "token" — exact match only.
		assertEquals("mytoken=abc&token=***",
				HttpQueryRedactor.redact("mytoken=abc&token=def"));
	}

	@Test
	void casTicket_isRedacted() {
		assertEquals("ticket=***", HttpQueryRedactor.redact("ticket=ST-12345-abcde"));
	}

	@Test
	void samlRelayState_isRedacted() {
		assertEquals("RelayState=***", HttpQueryRedactor.redact("RelayState=opaque-id"));
	}

	@Test
	void percentEncodedSensitiveName_isRedacted() {
		// Servlet container decodes %63onfirm to "confirm" for @RequestParam binding,
		// so the controller consumes the real token; the redactor must also recognise
		// the decoded form or the token leaks to logs while the password reset succeeds.
		// Name is kept on the wire so operators see exactly what the client sent.
		assertEquals("%63onfirm=***", HttpQueryRedactor.redact("%63onfirm=secret-token"));
	}

	@Test
	void percentEncodedSensitiveName_caseInsensitiveOnDecodedForm() {
		// %43 → 'C'; once decoded the name is "Confirm", which matches case-insensitively.
		assertEquals("%43onfirm=***", HttpQueryRedactor.redact("%43onfirm=secret-token"));
	}

	@Test
	void malformedPercentEscape_doesNotThrowAndPassesThroughForNonSensitiveName() {
		// "%ZZ" is a malformed escape; URLDecoder throws IllegalArgumentException.
		// The decodeName fallback returns the raw name, which is not sensitive — pair passes through.
		assertEquals("q%ZZ=hello", HttpQueryRedactor.redact("q%ZZ=hello"));
	}

	@Test
	void semicolonSeparator_isRecognisedAsPairBoundary() {
		// Legacy HTML 4 / RFC 1866 separator. Output normalises to '&' — accepted trade-off
		// for log/feed-link sinks. Without this fix, "q=hello;confirm=secret" would be a
		// single pair (name=q, value=hello;confirm=secret) and the token would leak.
		assertEquals("q=hello&confirm=***",
				HttpQueryRedactor.redact("q=hello;confirm=secret"));
	}

	@Test
	void mixedAmpAndSemicolonSeparators_areBothRecognised() {
		assertEquals("q=hello&confirm=***&token=***",
				HttpQueryRedactor.redact("q=hello;confirm=abc&token=def"));
	}

	@Test
	void sensitiveParamAtFirstPosition_isRedacted() {
		// Position-shape regression sentinel: defends the loop boundaries against a
		// future refactor that drops the leading or trailing pair.
		assertEquals("confirm=***&q=hello",
				HttpQueryRedactor.redact("confirm=abc&q=hello"));
	}

	@Test
	void sensitiveParamAtLastPosition_isRedacted() {
		assertEquals("q=hello&token=***",
				HttpQueryRedactor.redact("q=hello&token=abc"));
	}

	@Test
	void emptyNamePair_isPassedThrough() {
		// "=foo" has no name at all; the decoded "" is not in the sensitive set.
		assertEquals("=foo&q=bar", HttpQueryRedactor.redact("=foo&q=bar"));
	}

	@Test
	void consecutiveAmpersands_preserveEmptySegments() {
		// "a&&b" splits to ["a", "", "b"] with limit=-1; output round-trips the shape.
		assertEquals("confirm=***&&q=y", HttpQueryRedactor.redact("confirm=x&&q=y"));
	}

	@Test
	void leadingAndTrailingAmpersands_arePreserved() {
		assertEquals("&confirm=***&", HttpQueryRedactor.redact("&confirm=x&"));
	}
}
