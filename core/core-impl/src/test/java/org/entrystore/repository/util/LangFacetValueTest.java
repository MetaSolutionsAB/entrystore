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

package org.entrystore.repository.util;

import org.eclipse.rdf4j.model.Literal;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LangFacetValueTest {

	private static final ValueFactory VF = SimpleValueFactory.getInstance();

	static Stream<Arguments> literals() {
		return Stream.of(
				Arguments.of("tagged literal", VF.createLiteral("Sweden", "en"), new LangFacetValue("Sweden", "en")),
				Arguments.of("untagged literal", VF.createLiteral("Stockholm"), new LangFacetValue("Stockholm", null)),
				Arguments.of("untagged literal with a tag-like suffix", VF.createLiteral("Route 66-en"),
						new LangFacetValue("Route 66-en", null)),
				Arguments.of("label containing @ and -", VF.createLiteral("user@example.org-x", "en"),
						new LangFacetValue("user@example.org-x", "en")),
				Arguments.of("well-formed tag keeps its canonical case", VF.createLiteral("Colour", "en-GB"),
						new LangFacetValue("Colour", "en-GB")),
				Arguments.of("lower-case region subtag is normalised", VF.createLiteral("Colour", "en-gb"),
						new LangFacetValue("Colour", "en-GB")),
				Arguments.of("upper-case language subtag is normalised", VF.createLiteral("Färg", "SV"),
						new LangFacetValue("Färg", "sv")),
				Arguments.of("typed literal", VF.createLiteral(42), new LangFacetValue("42", null)));
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("literals")
	void encodeThenDecode_roundTripsLabelAndLanguage(String ignoredName, Literal literal, LangFacetValue expected) {
		assertEquals(expected, LangFacetValue.decode(LangFacetValue.encode(literal)));
	}

	@Test
	void encodeThenDecode_labelContainingSeparator_keepsLabelIntact() {
		// The separator is always appended and a language tag can never contain it, so splitting at
		// the last occurrence recovers a label that itself contains the separator.
		Literal literal = VF.createLiteral("a\u001Fb", "en");

		assertEquals(new LangFacetValue("a\u001Fb", "en"), LangFacetValue.decode(LangFacetValue.encode(literal)));
	}

	@Test
	void decode_termWithoutSeparator_isTreatedAsPlainLabel() {
		assertEquals(new LangFacetValue("legacy", null), LangFacetValue.decode("legacy"));
	}

	@ParameterizedTest(name = "{0} -> {1}")
	@CsvSource({
			"metadata.predicate.literal_l.abc12345, true",
			"related.metadata.predicate.literal_l.abc12345, true",
			"metadata.predicate.literal_s.abc12345, false",
			"rdfType, false"
	})
	void isLangFacetField_recognisesOnlyTheLiteralLFamily(String fieldName, boolean expected) {
		assertEquals(expected, LangFacetValue.isLangFacetField(fieldName));
	}

	@ParameterizedTest(name = "{0} -> {1}")
	@CsvSource({
			"metadata.predicate.literal_s.abc12345, metadata.predicate.literal_l.abc12345",
			"related.metadata.predicate.literal_s.abc12345, related.metadata.predicate.literal_l.abc12345",
			"metadata.predicate.literal_s.a.b-c, metadata.predicate.literal_l.a.b-c",
			"related.metadata.predicate.literal_s.x, related.metadata.predicate.literal_l.x"
	})
	void companionField_mapsALiteralFacetToItsInternalCompanion(String facetField, String expected) {
		assertEquals(expected, LangFacetValue.companionField(facetField));
	}

	@ParameterizedTest(name = "{0}")
	@ValueSource(strings = {
			"rdfType",
			"metadata.predicate.literal_t.abc12345",
			"metadata.predicate.literal.abc12345",
			"metadata.predicate.literal_l.abc12345",
			"metadata.predicate.literal_s.",
			"related.rdfType"
	})
	void companionField_isNullForEveryOtherField(String field) {
		assertNull(LangFacetValue.companionField(field));
	}

	@Test
	void companionField_isNullForNull() {
		assertNull(LangFacetValue.companionField(null));
	}

	@ParameterizedTest(name = "{0} -> {1}")
	@CsvSource({
			"en-gb, en-GB",
			"SV, sv",
			"zh-hant-tw, zh-Hant-TW",
			"en-GB, en-GB"
	})
	void normalizeLanguageTag_canonicalisesCase(String tag, String expected) {
		assertEquals(expected, LangFacetValue.normalizeLanguageTag(tag));
	}

	@Test
	void normalizeLanguageTag_keepsAnIllFormedTagVerbatim() {
		// Dropping the literal would hide it from the companion field; keeping the raw tag only costs canonical casing.
		assertEquals("not a tag!", LangFacetValue.normalizeLanguageTag("not a tag!"));
	}

	@ParameterizedTest(name = "tag {0} against facetLang {1} -> {2}")
	@CsvSource({
			"en, en, true",
			"en-GB, en, true",
			"en-US, en, true",
			"EN-gb, en, true",
			"en, EN, true",
			"eng, en, false",
			"en, en-GB, false",
			"sv, en, false",
			"en-GB, en-US, false"
	})
	void matchesLanguage_appliesRfc4647BasicFiltering(String tag, String facetLang, boolean expected) {
		assertEquals(expected, LangFacetValue.matchesLanguage(tag, facetLang));
	}

	@Test
	void matchesLanguage_isFalseWithoutATagOrARange() {
		assertFalse(LangFacetValue.matchesLanguage(null, "en"));
		assertFalse(LangFacetValue.matchesLanguage("en", null));
	}

	@Test
	void exceedsLabelCap_isTrueOnlyAboveTheCap() {
		assertFalse(LangFacetValue.exceedsLabelCap("x".repeat(LangFacetValue.MAX_LABEL_LENGTH)));
		assertTrue(LangFacetValue.exceedsLabelCap("x".repeat(LangFacetValue.MAX_LABEL_LENGTH + 1)));
	}

	@Test
	void labelMatchesRegex_fullMatchesOnlyTheLabelPart() {
		Pattern pattern = Pattern.compile(LangFacetValue.labelMatchesRegex("Sverige"));

		assertTrue(pattern.matcher(LangFacetValue.encode(VF.createLiteral("Sverige", "sv"))).matches());
		assertTrue(pattern.matcher(LangFacetValue.encode(VF.createLiteral("Sverige"))).matches());
		assertFalse(pattern.matcher(LangFacetValue.encode(VF.createLiteral("Sverige-x", "sv"))).matches());
	}
}
