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

import org.apache.commons.lang3.exception.ExceptionUtils;
import org.entrystore.rest.springboot.model.api.FacetSettingsRequestParams;
import org.entrystore.rest.springboot.model.exception.BadRequestException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.support.PropertySourcesPlaceholderConfigurer;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
	void defaultConfigAcceptsAQueryOringTwentyResourceUris() {
		// entrystore.js and EntryScape OR up to 20 resource URIs into one query; 5.x had no length cap.
		String query = IntStream.rangeClosed(1, 20)
				.mapToObj(i -> "resource:\"https://catalog.example.org/store/1234/resource/" + i + "\"")
				.collect(Collectors.joining(" OR ", "(", ")"));
		assertTrue(query.length() > MAX_LEN, "test setup: query must exceed the former 1024 default");

		contextRunner().run(context ->
				assertDoesNotThrow(() -> context.getBean(SolrSearchInputValidator.class).validateQuery(query)));
	}

	@Test
	void defaultConfigAcceptsAFilterQueryOverTheFormerDefaultCap() {
		String raw = "resource:" + "a".repeat(2 * MAX_LEN);

		contextRunner().run(context ->
				assertEquals(List.of(raw), context.getBean(SolrSearchInputValidator.class).parseFilterQueries(raw)));
	}

	@Test
	void configuredQueryMaxLengthRejectsLongerQueries() {
		contextRunner()
				.withPropertyValues("entrystore.solr.search.query.max-length=10")
				.run(context -> {
					SolrSearchInputValidator configured = context.getBean(SolrSearchInputValidator.class);
					assertDoesNotThrow(() -> configured.validateQuery("a".repeat(10)));
					BadRequestException ex = assertThrows(BadRequestException.class,
							() -> configured.validateQuery("a".repeat(11)));
					assertTrue(ex.getMessage().contains("'query'"), ex.getMessage());
				});
	}

	@Test
	void configuredFilterQueryMaxLengthRejectsLongerFilterQueries() {
		contextRunner()
				.withPropertyValues("entrystore.solr.search.filter-query.max-length=10")
				.run(context -> assertThrows(BadRequestException.class,
						() -> context.getBean(SolrSearchInputValidator.class).parseFilterQueries("a".repeat(11))));
	}

	@Test
	void negativeLimitFailsStartup() {
		contextRunner()
				.withPropertyValues("entrystore.solr.search.query.max-length=-1")
				.run(context -> {
					Throwable failure = context.getStartupFailure();
					assertNotNull(failure);
					assertTrue(ExceptionUtils.getRootCause(failure).getMessage()
							.contains("entrystore.solr.search.query.max-length"), failure.toString());
				});
	}

	@Test
	void defaultConfigAcceptsAnyNumberOfFilterQueries() {
		String raw = String.join(",", Collections.nCopies(MAX_FQ_COUNT + 1, "f:v"));

		contextRunner().run(context -> assertEquals(MAX_FQ_COUNT + 1,
				context.getBean(SolrSearchInputValidator.class).parseFilterQueries(raw).size()));
	}

	private static ApplicationContextRunner contextRunner() {
		return new ApplicationContextRunner()
				.withBean(PropertySourcesPlaceholderConfigurer.class)
				.withBean(SolrSearchInputValidator.class);
	}

	@Test
	void validateSortAcceptsNullAndEmpty() {
		assertDoesNotThrow(() -> validator.validateSort(null));
		assertDoesNotThrow(() -> validator.validateSort(""));
	}

	@ParameterizedTest(name = "{0}")
	@ValueSource(strings = {
			"modified desc",
			"score desc",
			"created asc",
			"title.en desc",
			"title.pl asc",
			"title.sv-SE asc",
			"uri asc",
			"score desc,modified desc",
			"rdfType asc",
			"public desc",
			"graphType asc",
			"description asc",
			"tag.literal asc",
			"metadata.predicate.integer.0123abcd desc",
			"unknownField asc"    // Solr answers an unknown field with 400
	})
	void validateSortAcceptsAnyPlainFieldButTheLanguageCompanion(String sort) {
		assertDoesNotThrow(() -> validator.validateSort(sort));
	}

	@ParameterizedTest(name = "{0}")
	@ValueSource(strings = {
			"{!func}modified desc",
			"modified desc,{!key=x}created asc",
			"query({!lucene v=x}) desc",
			"metadata.predicate.literal_l.0123abcd asc"
	})
	void validateSortRejectsLocalParamsAndTheLanguageCompanion(String sort) {
		BadRequestException ex = assertThrows(BadRequestException.class, () -> validator.validateSort(sort));
		assertTrue(ex.getMessage().contains("'sort'"), ex.getMessage());
	}

	@Test
	void validateSortAcceptsAtMaxLength() {
		// Single clause of exactly MAX_LEN chars; SearchService parses the order token leniently (unknown means asc).
		String padded = "modified " + "a".repeat(MAX_LEN - "modified ".length());
		// Sanity check: at-cap.
		assertEquals(MAX_LEN, padded.length(), "test setup: padded length != MAX_LEN");
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
	void validateSortAcceptsEmptyClauses(String sort) {
		// SearchService skips clauses that are not "field order"
		assertDoesNotThrow(() -> validator.validateSort(sort));
	}

	@Test
	void defaultConfigAcceptsSortOverTheFormerDefaultCap() {
		String sort = "modified desc," + "a".repeat(2 * MAX_LEN);

		contextRunner().run(context ->
				assertDoesNotThrow(() -> context.getBean(SolrSearchInputValidator.class).validateSort(sort)));
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
		// The per-entry cap is defense-in-depth: unreachable from the controller flow today
		// (raw filterQuery is length-capped first, and URLDecoder only shrinks length), but this
		// test pins the behaviour for a future caller that passes pre-decoded entries directly.
		String decoded = "a".repeat(MAX_LEN + 1);
		String raw = "f:v";   // raw fits under the cap
		assertThrows(BadRequestException.class,
				() -> validator.validateFilterQueries(List.of(decoded), raw));
	}

	@Test
	void parseFilterQueriesNullReturnsEmptyList() {
		assertTrue(validator.parseFilterQueries(null).isEmpty());
	}

	@Test
	void parseFilterQueriesBlankValueYieldsOneEmptyQuery() {
		// filterQuery= from URL-template clients keeps its current shape: one empty entry, no rejection.
		assertEquals(List.of(""), validator.parseFilterQueries(""));
	}

	@Test
	void parseFilterQueriesDecodesAfterSplit() {
		// An unencoded comma separates filter queries; an encoded one (%2C) stays inside one.
		assertEquals(List.of("a:x,y", "b:z"), validator.parseFilterQueries("a:x%2Cy,b:z"));
	}

	@Test
	void parseFilterQueriesRejectsOneOverMaxCount() {
		String raw = String.join(",", Collections.nCopies(MAX_FQ_COUNT + 1, "f:v"));
		assertThrows(BadRequestException.class, () -> validator.parseFilterQueries(raw));
	}

	@Test
	void validateFacetSettingsAcceptsNullParams() {
		FacetSettingsRequestParams empty = new FacetSettingsRequestParams();
		assertDoesNotThrow(() -> validator.validateFacetSettings(empty));
	}

	@ParameterizedTest(name = "{0}")
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
			"related.metadata.predicate.literal_s.deadbeef",
			"rdfType,lang,status",
			" rdfType , lang ",
			"tag.literal",
			"public",
			"graphType",
			"description",
			"metadata.predicate.integer.0123abcd",
			"related.metadata.predicate.integer.0123abcd",
			"unknownField"    // Solr answers an unknown field with 400
	})
	void validateFacetSettingsAcceptsAnyPlainFieldButTheLanguageCompanion(String facetFields) {
		FacetSettingsRequestParams req = new FacetSettingsRequestParams();
		req.setFacetFields(facetFields);
		assertDoesNotThrow(() -> validator.validateFacetSettings(req));
	}

	@ParameterizedTest(name = "{0}")
	@ValueSource(strings = {
			"metadata.predicate.literal_l.0123abcd",
			"related.metadata.predicate.literal_l.0123abcd",
			" metadata.predicate.literal_l.0123abcd",
			"rdfType,metadata.predicate.literal_l.0123abcd",
			"{!key=x}metadata.predicate.literal_l.0123abcd",
			"{!facet.matches='(a+)+$'}tag.literal",
			"{!facet.limit=-1}metadata.predicate.literal_s.0123abcd",
			"rdfType,{!ex=t}lang",
			"tag literal"
	})
	void validateFacetSettingsRejectsLocalParamsAndTheLanguageCompanion(String facetFields) {
		FacetSettingsRequestParams req = new FacetSettingsRequestParams();
		req.setFacetFields(facetFields);
		assertThrows(BadRequestException.class, () -> validator.validateFacetSettings(req));
	}

	@Test
	void validateFacetSettingsAcceptsAtMaxFacetFieldCount() {
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

	@Test
	void defaultConfigAcceptsAnyNumberAndLengthOfFacetFields() {
		FacetSettingsRequestParams req = new FacetSettingsRequestParams();
		req.setFacetFields(String.join(",", Collections.nCopies(MAX_FACET_COUNT + 1, "metadata.predicate.uri.0123abcd"))
				+ ",metadata.predicate.uri." + "a".repeat(MAX_LEN));

		contextRunner().run(context ->
				assertDoesNotThrow(() -> context.getBean(SolrSearchInputValidator.class).validateFacetSettings(req)));
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
	void validateFacetSettingsIgnoresFacetMatchesWithoutFacetFields() {
		// Without facetFields there is no faceting, so facetMatches never reaches Solr, as in 5.x.
		FacetSettingsRequestParams req = new FacetSettingsRequestParams();
		req.setFacetMatches("(a+)+$");
		assertDoesNotThrow(() -> validator.validateFacetSettings(req));
	}

	@ParameterizedTest
	@ValueSource(strings = {"sv", "en-GB", "zh-Hant-TW", "x-private-1"})
	void validateFacetSettingsAcceptsWellFormedFacetLang(String facetLang) {
		FacetSettingsRequestParams req = new FacetSettingsRequestParams();
		req.setFacetFields("metadata.predicate.literal_s.deadbeef");
		req.setFacetLang(facetLang);
		assertDoesNotThrow(() -> validator.validateFacetSettings(req));
	}

	@ParameterizedTest
	@ValueSource(strings = {"sv_SE", ".*", "sv,nb", "en GB", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"})
	void validateFacetSettingsRejectsMalformedFacetLang(String facetLang) {
		FacetSettingsRequestParams req = new FacetSettingsRequestParams();
		req.setFacetFields("metadata.predicate.literal_s.deadbeef");
		req.setFacetLang(facetLang);
		BadRequestException ex = assertThrows(BadRequestException.class, () -> validator.validateFacetSettings(req));
		assertTrue(ex.getMessage().contains("'facetLang'"), ex.getMessage());
	}

	@Test
	void validateFacetSettingsRejectsFacetLangWithoutFacetFields() {
		FacetSettingsRequestParams req = new FacetSettingsRequestParams();
		req.setFacetLang("sv");
		BadRequestException ex = assertThrows(BadRequestException.class, () -> validator.validateFacetSettings(req));
		assertTrue(ex.getMessage().contains("'facetLang'"), ex.getMessage());
	}

	@Test
	void validateFacetSettingsAcceptsEmptyFacetLangEvenWithoutFacetFields() {
		FacetSettingsRequestParams req = new FacetSettingsRequestParams();
		req.setFacetLang("");
		assertDoesNotThrow(() -> validator.validateFacetSettings(req));
	}
}
