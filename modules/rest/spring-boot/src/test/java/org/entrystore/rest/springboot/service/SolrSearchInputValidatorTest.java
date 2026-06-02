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

package org.entrystore.rest.springboot.service;

import org.entrystore.rest.springboot.model.api.FacetSettingsRequestParams;
import org.entrystore.rest.springboot.model.exception.BadRequestException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SolrSearchInputValidatorTest {

	private static final int MAX_LEN = 1024;
	private static final int MAX_FQ_COUNT = 16;
	private static final int MAX_FACET_COUNT = 16;

	private SolrSearchInputValidator validator;

	@BeforeEach
	void setUp() {
		validator = new SolrSearchInputValidator();
		ReflectionTestUtils.setField(validator, "maxQueryLength", MAX_LEN);
		ReflectionTestUtils.setField(validator, "maxSortLength", MAX_LEN);
		ReflectionTestUtils.setField(validator, "maxFilterQueryLength", MAX_LEN);
		ReflectionTestUtils.setField(validator, "maxFilterQueryCount", MAX_FQ_COUNT);
		ReflectionTestUtils.setField(validator, "maxFacetFieldsLength", MAX_LEN);
		ReflectionTestUtils.setField(validator, "maxFacetFieldCount", MAX_FACET_COUNT);
	}

	@Test
	void validateQueryAcceptsAtMaxLength() {
		assertDoesNotThrow(() -> validator.validateQuery("a".repeat(MAX_LEN)));
	}

	@Test
	void validateQueryRejectsOneOverMaxLength() {
		BadRequestException ex = assertThrows(BadRequestException.class,
				() -> validator.validateQuery("a".repeat(MAX_LEN + 1)));
		// Message must name the parameter and the limit so operators can diagnose without server logs.
		assertTrue(ex.getMessage().contains("'query'"), ex.getMessage());
		assertTrue(ex.getMessage().contains(String.valueOf(MAX_LEN)), ex.getMessage());
	}

	@Test
	void validateSortAcceptsNullAndEmpty() {
		assertDoesNotThrow(() -> validator.validateSort(null));
		assertDoesNotThrow(() -> validator.validateSort(""));
	}

	@ParameterizedTest
	@ValueSource(strings = {
			"modified desc",
			"score desc",
			"created asc",
			"title.en desc",
			"title.pl asc",
			"score desc,modified desc",
			"rdfType asc"
	})
	void validateSortAcceptsAllowedFields(String sort) {
		assertDoesNotThrow(() -> validator.validateSort(sort));
	}

	@ParameterizedTest
	@ValueSource(strings = {
			"evilField asc",
			"password desc",
			"sun.java.command asc",
			"title.<script> desc"
	})
	void validateSortRejectsDisallowedFields(String sort) {
		assertThrows(BadRequestException.class, () -> validator.validateSort(sort));
	}

	@Test
	void validateSortAcceptsAtMaxLength() {
		// Build a single-clause sort that is exactly MAX_LEN chars long. Padding via the allow-listed
		// "modified" field name plus repeated spaces (the order token is parsed lazily by
		// SearchService.ORDER.valueOf and falls back to asc on unknown values).
		String padded = "modified " + "a".repeat(MAX_LEN - "modified ".length());
		// Sanity check: at-cap.
		assertTrue(padded.length() == MAX_LEN, "test setup: padded length != MAX_LEN");
		assertDoesNotThrow(() -> validator.validateSort(padded));
	}

	@Test
	void validateSortRejectsOverlongInput() {
		String overlong = "modified asc," + "a".repeat(MAX_LEN);
		assertThrows(BadRequestException.class, () -> validator.validateSort(overlong));
	}

	@ParameterizedTest
	@ValueSource(strings = {
			",modified desc",
			"modified desc,",
			",,",
			"modified desc, ,score desc"
	})
	void validateSortRejectsEmptyClauses(String sort) {
		assertThrows(BadRequestException.class, () -> validator.validateSort(sort));
	}

	@Test
	void validateFilterQueriesAcceptsNullAndEmpty() {
		assertDoesNotThrow(() -> validator.validateFilterQueries(List.of(), null));
		assertDoesNotThrow(() -> validator.validateFilterQueries(List.of(""), ""));
	}

	@Test
	void validateFilterQueriesAcceptsAtMaxCount() {
		List<String> fqs = new ArrayList<>();
		for (int i = 0; i < MAX_FQ_COUNT; i++) {
			fqs.add("field" + i + ":value" + i);
		}
		assertDoesNotThrow(() -> validator.validateFilterQueries(fqs, String.join(",", fqs)));
	}

	@Test
	void validateFilterQueriesAcceptsAtMaxLength() {
		String raw = "a".repeat(MAX_LEN);
		assertDoesNotThrow(() -> validator.validateFilterQueries(List.of(raw), raw));
	}

	@Test
	void validateFilterQueriesRejectsOneOverMaxCount() {
		List<String> fqs = new ArrayList<>();
		for (int i = 0; i < MAX_FQ_COUNT + 1; i++) {
			fqs.add("f:v");
		}
		assertThrows(BadRequestException.class,
				() -> validator.validateFilterQueries(fqs, String.join(",", fqs)));
	}

	@Test
	void validateFilterQueriesRejectsCombinedOverlongInput() {
		String raw = "a".repeat(MAX_LEN + 1);
		assertThrows(BadRequestException.class,
				() -> validator.validateFilterQueries(List.of(raw), raw));
	}

	@Test
	void validateFilterQueriesRejectsOverlongDecodedEntry() {
		// Raw under the cap (1000 chars of %20 expands to 1000 chars after URLDecoder, but each
		// percent-encoded triple shrinks to one char — simulate the post-decode result here).
		String decoded = "a".repeat(MAX_LEN + 1);
		String raw = "f:v";   // raw fits under the cap
		assertThrows(BadRequestException.class,
				() -> validator.validateFilterQueries(List.of(decoded), raw));
	}

	@Test
	void validateFacetSettingsAcceptsNullParams() {
		FacetSettingsRequestParams empty = new FacetSettingsRequestParams();
		assertDoesNotThrow(() -> validator.validateFacetSettings(empty));
	}

	@ParameterizedTest
	@ValueSource(strings = {
			"rdfType",
			"lang",
			"acl.metadata.r",
			"acl.resource.rw",
			"tag.uri",
			"metadata.predicate.uri.0123abcd",
			"metadata.predicate.literal_s.deadbeef",
			"metadata.predicate.literal_t.cafebabe",
			"metadata.predicate.literal.shorthand_form",
			"related.metadata.predicate.uri.0123abcd",
			"rdfType,lang,status"
	})
	void validateFacetSettingsAcceptsAllowedFields(String facetFields) {
		FacetSettingsRequestParams req = new FacetSettingsRequestParams();
		req.setFacetFields(facetFields);
		assertDoesNotThrow(() -> validator.validateFacetSettings(req));
	}

	@ParameterizedTest
	@ValueSource(strings = {
			"secretField",
			"unknown.predicate.uri.xyz",
			"metadata.predicate.uri.",                  // empty tail
			"metadata.predicate.uri.has spaces"         // disallowed char
	})
	void validateFacetSettingsRejectsDisallowedFields(String facetFields) {
		FacetSettingsRequestParams req = new FacetSettingsRequestParams();
		req.setFacetFields(facetFields);
		assertThrows(BadRequestException.class, () -> validator.validateFacetSettings(req));
	}

	@Test
	void validateFacetSettingsRejectsDynamicPrefixWithOverlongTail() {
		FacetSettingsRequestParams req = new FacetSettingsRequestParams();
		req.setFacetFields("metadata.predicate.uri." + "a".repeat(65));
		assertThrows(BadRequestException.class, () -> validator.validateFacetSettings(req));
	}

	@Test
	void validateFacetSettingsAcceptsAtMaxFacetFieldCount() {
		// Build exactly MAX_FACET_COUNT allow-listed comma-separated fields.
		StringBuilder fields = new StringBuilder("rdfType");
		for (int i = 1; i < MAX_FACET_COUNT; i++) {
			fields.append(",lang");
		}
		FacetSettingsRequestParams req = new FacetSettingsRequestParams();
		req.setFacetFields(fields.toString());
		assertDoesNotThrow(() -> validator.validateFacetSettings(req));
	}

	@Test
	void validateFacetSettingsRejectsTooManyFacetFields() {
		StringBuilder fields = new StringBuilder("rdfType");
		for (int i = 0; i < MAX_FACET_COUNT; i++) {
			fields.append(",lang");
		}
		FacetSettingsRequestParams req = new FacetSettingsRequestParams();
		req.setFacetFields(fields.toString());
		assertThrows(BadRequestException.class, () -> validator.validateFacetSettings(req));
	}

	@Test
	void validateFacetSettingsRejectsOverlongFacetFields() {
		// Single comma-less value longer than the length cap — defeats the count-only check.
		FacetSettingsRequestParams req = new FacetSettingsRequestParams();
		req.setFacetFields("metadata.predicate.uri." + "a".repeat(MAX_LEN));
		assertThrows(BadRequestException.class, () -> validator.validateFacetSettings(req));
	}

	@ParameterizedTest
	@ValueSource(strings = {
			"abc",
			"abc-123",
			"abc_123",
			"DEADBEEF",
			"a",
			"abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_-"     // exactly 64 chars
	})
	void validateFacetSettingsAcceptsSafeFacetMatches(String matches) {
		FacetSettingsRequestParams req = new FacetSettingsRequestParams();
		req.setFacetFields("rdfType");
		req.setFacetMatches(matches);
		assertDoesNotThrow(() -> validator.validateFacetSettings(req));
	}

	@ParameterizedTest
	@ValueSource(strings = {
			".*",
			"(a|b)+",
			"foo.*bar",
			"a{1,100}",
			"foo bar"
	})
	void validateFacetSettingsRejectsRegexFacetMatches(String matches) {
		FacetSettingsRequestParams req = new FacetSettingsRequestParams();
		req.setFacetFields("rdfType");
		req.setFacetMatches(matches);
		assertThrows(BadRequestException.class, () -> validator.validateFacetSettings(req));
	}

	@Test
	void validateFacetSettingsRejectsFacetMatchesOverSixtyFourChars() {
		FacetSettingsRequestParams req = new FacetSettingsRequestParams();
		req.setFacetFields("rdfType");
		req.setFacetMatches("a".repeat(65));
		assertThrows(BadRequestException.class, () -> validator.validateFacetSettings(req));
	}

	@Test
	void validateFacetSettingsAcceptsEmptyFacetMatchesEvenWithoutFacetFields() {
		// An empty `facetMatches=` query parameter must behave like the parameter was omitted —
		// otherwise any client that unconditionally renders the parameter in its URL template
		// breaks with 400 instead of getting normal search results.
		FacetSettingsRequestParams req = new FacetSettingsRequestParams();
		req.setFacetMatches("");
		assertDoesNotThrow(() -> validator.validateFacetSettings(req));
	}

	@Test
	void validateFacetSettingsRejectsNonEmptyFacetMatchesWithoutFacetFields() {
		// Solr's facet.matches regex filter only makes sense when applied to a facet field; without
		// facetFields the parameter is semantically meaningless.
		FacetSettingsRequestParams req = new FacetSettingsRequestParams();
		req.setFacetMatches("abc");
		assertThrows(BadRequestException.class, () -> validator.validateFacetSettings(req));
	}
}
