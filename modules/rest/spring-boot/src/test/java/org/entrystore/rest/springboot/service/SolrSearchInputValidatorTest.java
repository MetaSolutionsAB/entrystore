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

	@ParameterizedTest(name = "{0}")
	@ValueSource(strings = {
			"{!join fromIndex=other from=resource to=context}*:*",
			// Solr unescapes a nested query before reading its local params
			"_query_:\"{!join from\\u0049ndex=other from=resource to=context}*:*\"",
			"{!query v='{!join fromInd\\ex=other from=resource to=context}*:*'}",
			"_query_:\"_query_:\\\"{!join from\\\\u0049ndex=other from=resource to=context}*:*\\\"\"",
			// Solr reads a unicode escape with Integer.parseInt, which also takes a sign and non-ASCII digits
			"{!query v='{!join fromI\\u+06edex=other from=resource to=context}*:*'}",
			"{!query v='{!join fromI\\u\uFF10\uFF10\uFF16\uFF45dex=other from=resource to=context}*:*'}",
			"{!query v='{!join fromI\\u\u0660\u0660\u0666edex=other from=resource to=context}*:*'}"
	})
	void validateQueryRejectsFromIndexHoweverItIsEscaped(String query) {
		BadRequestException ex = assertThrows(BadRequestException.class, () -> validator.validateQuery(query));
		assertEquals("Local parameter 'fromIndex' is not permitted in 'query'", ex.getMessage());
	}

	@ParameterizedTest(name = "{0}")
	@ValueSource(strings = {
			"resource:http\\://example.org/data/fromindex.html",
			"title:FromIndex*",
			"fulltext:FROMINDEX",
			// Solr reads local-param keys case-sensitively, so this is no cross-core join
			"{!join FROMINDEX=other from=resource to=context}*:*"
	})
	void validateQueryAcceptsOtherCasingsOfFromIndex(String query) {
		assertDoesNotThrow(() -> validator.validateQuery(query));
	}

	@ParameterizedTest(name = "{0}")
	@ValueSource(strings = {
			"{!query v=$facet.matches}",
			"{!query v=$ facet.matches}",
			"{!query v=$'facet.matches'}",
			"{!func}query($qq)",
			// Solr skips any Character.isWhitespace after the $, not only ASCII whitespace
			"{!lucene v=$\u2003facet.matches}",
			"{!func}query($\u001Fqq)",
			"_query_:\"{!query v=\\$facet.matches}\"",
			// the lucene parser reads the field _val_ as a function query
			"_val_:\"query($qq)\"",
			"\\_val\\_:\"query($qq)\"",
			// the local params only appear once Solr unescapes the nested query
			"_query_:\"\\u007b!query v=$facet.matches}\""
	})
	void validateQueryRejectsParameterDereferencing(String query) {
		BadRequestException ex = assertThrows(BadRequestException.class, () -> validator.validateQuery(query));
		assertEquals("Parameter dereferencing ($name) is not permitted in 'query'", ex.getMessage());
	}

	@ParameterizedTest(name = "{0}")
	@ValueSource(strings = {
			"price:$100 OR title:US$",
			"title:US$ AND title:rates",
			"title:\"US$\"",
			"title:$Money",
			"title:\\$Money"
	})
	void validateQueryAcceptsADollarSignOutsideLocalParamsAndFunctionQueries(String query) {
		assertDoesNotThrow(() -> validator.validateQuery(query));
	}

	@Test
	void validateQueryRejectsEscapesNestedSeventeenLevelsDeep() {
		// each pass turns the leading unicode-escaped backslash into a new escape, shedding one level only
		String query = "\\u005c" + "u005c".repeat(16);

		BadRequestException ex = assertThrows(BadRequestException.class, () -> validator.validateQuery(query));
		assertEquals("Query parameter 'query' nests escapes more than 16 levels deep", ex.getMessage());
	}

	@Test
	void validateQueryAcceptsEscapesNestedSixteenLevelsDeep() {
		String query = "\\u005c" + "u005c".repeat(15);

		assertDoesNotThrow(() -> validator.validateQuery(query));
	}

	@Test
	void validateQueryAcceptsTheContextTypeJoin() {
		// the shape entrystore.js sends to filter by context type (ENTRYSTORE-1277), with a ' escaped in a type URI
		assertDoesNotThrow(() -> validator.validateQuery("filter({!join from=resource to=context score=none "
				+ "v='graphType:Context AND rdfType:(\"http://example.org/ns/o\\'type\" OR "
				+ "\"http://entryscape.com/terms/CatalogContext\")'})"));
	}

	@Test
	void validateQueryAcceptsEscapedCharacters() {
		assertDoesNotThrow(() -> validator.validateQuery("resource:http\\://example.com/a\\ b"));
	}

	@Test
	void parseFilterQueriesRejectsFromIndexInAnyEntry() {
		BadRequestException ex = assertThrows(BadRequestException.class,
				() -> validator.parseFilterQueries("public:true,{!join fromIndex=other from=resource to=context}*:*"));
		assertEquals("Local parameter 'fromIndex' is not permitted in 'filterQuery'", ex.getMessage());
	}

	@Test
	void parseFilterQueriesRejectsAPercentEncodedFromIndex() {
		String encoded = "%7B!join%20from%49ndex%3Dother%20from%3Dresource%20to%3Dcontext%7D*:*";

		BadRequestException ex = assertThrows(BadRequestException.class, () -> validator.parseFilterQueries(encoded));
		assertEquals("Local parameter 'fromIndex' is not permitted in 'filterQuery'", ex.getMessage());
	}

	@Test
	void parseFilterQueriesRejectsParameterDereferencing() {
		BadRequestException ex = assertThrows(BadRequestException.class,
				() -> validator.parseFilterQueries("{!query v=$facet.matches}"));
		assertEquals("Parameter dereferencing ($name) is not permitted in 'filterQuery'", ex.getMessage());
	}

	@Test
	void validateFacetSettingsRejectsFromIndexInFacetMatches() {
		// literal text for the regex, yet query text once a query dereferences it with {!query v=$facet.matches}
		FacetSettingsRequestParams req = new FacetSettingsRequestParams();
		req.setFacetFields("tag.literal");
		req.setFacetMatches("_query_:\"\\{!join fromIndex=other from=resource to=context\\}title:a\\*\"");

		BadRequestException ex = assertThrows(BadRequestException.class, () -> validator.validateFacetSettings(req));
		assertEquals("Local parameter 'fromIndex' is not permitted in 'facetMatches'", ex.getMessage());
	}

	@Test
	void parseFilterQueriesAcceptsTheContextTypeJoin() {
		String fq = "filter({!join from=resource to=context score=none "
				+ "v='graphType:Context AND rdfType:(\"http://entryscape.com/terms/CatalogContext\")'})";

		assertEquals(List.of(fq), validator.parseFilterQueries(fq));
	}

	@Test
	void parseFilterQueriesAcceptsTheContextTypeJoinWithEncodedPlus() {
		String fq = "filter({!join from=resource to=context score=none "
				+ "v='+graphType:Context +rdfType:(\"http://entryscape.com/terms/CatalogContext\")'})";

		// the request decoding has turned a + the client sent as %252B into %2B
		assertEquals(List.of(fq), validator.parseFilterQueries(fq.replace("+", "%2B")));
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

	@ParameterizedTest(name = "{0}")
	@ValueSource(strings = {
			"all",
			"fulltext",
			"metadata.object.literal",
			" all ",
			"rdfType,metadata.object.literal"
	})
	void validateFacetSettingsRejectsCatchAllTextFields(String facetFields) {
		FacetSettingsRequestParams req = new FacetSettingsRequestParams();
		req.setFacetFields(facetFields);
		BadRequestException ex = assertThrows(BadRequestException.class, () -> validator.validateFacetSettings(req));
		assertTrue(ex.getMessage().contains("'facetFields'"), ex.getMessage());
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

	@ParameterizedTest(name = "{0}")
	@ValueSource(strings = {
			"abc",
			"abc-123",
			"abc_123",
			"a",
			"Österreich",
			"Łódź",
			"Café crème",
			"日本語",
			"O'Brien",
			"New York",
			"a, \"b\"",
			"Dr\\. Smith",
			"Price \\$\\{amount\\}",
			"\\{!lucene\\}\\*:\\*",
			"C:\\\\temp"
	})
	void validateFacetSettingsAcceptsLiteralFacetMatches(String matches) {
		FacetSettingsRequestParams req = new FacetSettingsRequestParams();
		req.setFacetFields("rdfType");
		req.setFacetMatches(matches);
		assertDoesNotThrow(() -> validator.validateFacetSettings(req));
	}

	@ParameterizedTest(name = "{0}")
	@ValueSource(strings = {
			".*",
			"(a|b)+",
			"foo.*bar",
			"a{1,100}",
			"[ab]",
			"^a",
			"a$",
			"\\d+",
			"\\Qa\\E",
			"trailing\\",
			"label\u001Fsv"
	})
	void validateFacetSettingsRejectsRegexFacetMatches(String matches) {
		FacetSettingsRequestParams req = new FacetSettingsRequestParams();
		req.setFacetFields("rdfType");
		req.setFacetMatches(matches);
		BadRequestException ex = assertThrows(BadRequestException.class, () -> validator.validateFacetSettings(req));
		assertTrue(ex.getMessage().contains("'facetMatches'"), ex.getMessage());
	}

	@Test
	void validateFacetSettingsAcceptsFacetMatchesOfTwoHundredFiftySixChars() {
		FacetSettingsRequestParams req = new FacetSettingsRequestParams();
		req.setFacetFields("rdfType");
		req.setFacetMatches("ä".repeat(256));
		assertDoesNotThrow(() -> validator.validateFacetSettings(req));
	}

	@Test
	void validateFacetSettingsRejectsFacetMatchesOverTwoHundredFiftySixChars() {
		FacetSettingsRequestParams req = new FacetSettingsRequestParams();
		req.setFacetFields("rdfType");
		req.setFacetMatches("ä".repeat(257));
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
