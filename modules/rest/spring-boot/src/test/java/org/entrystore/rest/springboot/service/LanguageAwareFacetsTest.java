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

import org.apache.solr.client.solrj.request.SolrQuery;
import org.apache.solr.client.solrj.response.FacetField;
import org.entrystore.repository.util.SolrSearchIndex;
import org.entrystore.rest.springboot.model.dto.FacetValueDto;
import org.entrystore.rest.springboot.model.dto.FacetValuesDto;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LanguageAwareFacetsTest {

	private static final String FIELD = "metadata.predicate.literal_s.abc12345";
	private static final String COMPANION = "metadata.predicate.literal_l.abc12345";

	private static SolrSearchIndex.FacetSettings settings(String fields, String matches, String lang, int limit) {
		var settings = new SolrSearchIndex.FacetSettings();
		settings.fields = fields;
		settings.matches = matches;
		settings.lang = lang;
		settings.limit = limit;
		settings.minCount = 1;
		return settings;
	}

	private static String term(String label, String lang) {
		return label + '\u001F' + (lang == null ? "" : lang);
	}

	/** Sweden 3 (en), Sverige 2 (sv, nb, untagged), Britain 1 (en-GB), Stockholm 1 (untagged only). */
	private static FacetField clientFacet() {
		FacetField field = new FacetField(FIELD);
		field.add("Sweden", 3);
		field.add("Sverige", 2);
		field.add("Britain", 1);
		field.add("Stockholm", 1);
		return field;
	}

	private static FacetField companionFacet() {
		FacetField companion = new FacetField(COMPANION);
		companion.add(term("Sweden", "en"), 3);
		companion.add(term("Sverige", "sv"), 2);
		companion.add(term("Sverige", "nb"), 1);
		companion.add(term("Sverige", null), 1);
		companion.add(term("Britain", "en-GB"), 1);
		companion.add(term("Stockholm", null), 1);
		return companion;
	}

	private static List<FacetValueDto> merged(String lang, int limit) {
		List<FacetValuesDto> result = LanguageAwareFacets.merge(List.of(clientFacet(), companionFacet()),
				settings(FIELD, null, lang, limit));
		assertEquals(1, result.size(), "the companion must not appear as a facet field of its own");
		assertEquals(FIELD, result.get(0).name());
		return result.get(0).values();
	}

	private static List<String> names(List<FacetValueDto> values) {
		return values.stream().map(FacetValueDto::name).toList();
	}

	@Test
	void configure_addsAnUnlimitedCompanionForALiteralFacet() {
		SolrQuery query = new SolrQuery("*:*");

		LanguageAwareFacets.configure(query, settings(FIELD, null, null, 100));

		assertEquals(List.of(FIELD, COMPANION), Arrays.asList(query.getFacetFields()));
		assertEquals("-1", query.get("f." + COMPANION + ".facet.limit"));
		assertEquals("1", query.get("f." + COMPANION + ".facet.mincount"));
		assertEquals("false", query.get("f." + COMPANION + ".facet.missing"));
		assertNull(query.get("f." + FIELD + ".facet.limit"), "without facetLang the client field keeps the global limit");
		assertNull(query.get("facet.matches"));
	}

	@Test
	void configure_rewritesTheLiteralShorthandAndStillAddsTheCompanion() {
		SolrQuery query = new SolrQuery("*:*");

		LanguageAwareFacets.configure(query, settings("metadata.predicate.literal.abc12345", null, null, 100));

		assertEquals(List.of(FIELD, COMPANION), Arrays.asList(query.getFacetFields()));
	}

	@Test
	void configure_addsNoCompanionForANonLiteralFacet() {
		SolrQuery query = new SolrQuery("*:*");

		LanguageAwareFacets.configure(query, settings("rdfType", null, "sv", 100));

		assertEquals(List.of("rdfType"), Arrays.asList(query.getFacetFields()));
		assertTrue(query.getParameterNames().stream().noneMatch(name -> name.startsWith("f.")),
				"no per-field override without a companion");
	}

	@Test
	void configure_anchorsFacetMatchesToTheLabelOnTheCompanionOnly() {
		SolrQuery query = new SolrQuery("*:*");

		LanguageAwareFacets.configure(query, settings("rdfType," + FIELD, "Sver-1", null, 100));

		assertEquals("Sver-1", query.get("facet.matches"));
		assertNull(query.get("f.rdfType.facet.matches"));
		assertNull(query.get("f." + FIELD + ".facet.matches"));
		Pattern perField = Pattern.compile(query.get("f." + COMPANION + ".facet.matches"));
		assertTrue(perField.matcher(term("Sver-1", "sv")).matches());
		assertTrue(perField.matcher(term("Sver-1", null)).matches());
		assertFalse(perField.matcher(term("Sver-10", "sv")).matches());
	}

	@Test
	void configure_liftsTheClientFieldLimitOnlyWithFacetLang() {
		SolrQuery query = new SolrQuery("*:*");

		LanguageAwareFacets.configure(query, settings(FIELD, null, "sv", 100));

		assertEquals("-1", query.get("f." + FIELD + ".facet.limit"));
		assertEquals("-1", query.get("f." + COMPANION + ".facet.limit"));
	}

	@Test
	void merge_attachesSortedLanguagesAndKeepsSolrOrderWithoutFacetLang() {
		List<FacetValueDto> values = merged(null, 100);

		assertEquals(List.of(
				new FacetValueDto("Sweden", 3, List.of("en")),
				new FacetValueDto("Sverige", 2, List.of("nb", "sv")),
				new FacetValueDto("Britain", 1, List.of("en-GB")),
				new FacetValueDto("Stockholm", 1, List.of())), values);
	}

	@Test
	void merge_facetLang_keepsMatchingAndUntaggedLabelsOnly() {
		// Sweden occurs only in "en": excluded. Sverige (has sv) and Stockholm (untagged) stay.
		assertEquals(List.of("Sverige", "Stockholm"), names(merged("sv", 100)));
	}

	@Test
	void merge_facetLang_matchesSubtagsByPrefixButNotTheOtherWayRound() {
		// "en" covers Britain's en-GB; "en-GB" does not cover Sweden's plain en.
		assertEquals(List.of("Sweden", "Sverige", "Britain", "Stockholm"), names(merged("en", 100)));
		assertEquals(List.of("Sverige", "Britain", "Stockholm"), names(merged("en-GB", 100)));
	}

	@Test
	void merge_facetLang_isCaseInsensitiveAndNormalised() {
		assertEquals(List.of("Sverige", "Britain", "Stockholm"), names(merged("EN-gb", 100)));
	}

	@Test
	void merge_facetLang_appliesTheLimitAfterFilteringAndSortsByCountThenName() {
		// Without the filter the single top bucket would be Sweden (3); within "sv" it is Sverige (2).
		assertEquals(List.of("Sverige"), names(merged("sv", 1)));
		// Ties on count are broken by name, so Britain precedes Stockholm.
		assertEquals(List.of("Sverige", "Britain", "Stockholm"), names(merged("en-GB", 3)));
		assertEquals(List.of("Sverige", "Britain"), names(merged("en-GB", 2)));
	}

	@Test
	void merge_facetLang_keepsTheUnchangedPerLabelCountAndLanguages() {
		FacetValueDto sverige = merged("sv", 100).get(0);

		assertEquals(2, sverige.count());
		assertEquals(List.of("nb", "sv"), sverige.langs(), "the lang list names every language, not only the filter");
	}

	@Test
	void merge_keepsTheMissingBucketLastEvenAfterFilteringAndLimiting() {
		FacetField field = clientFacet();
		field.add(null, 4);

		List<FacetValueDto> values = LanguageAwareFacets.merge(List.of(field, companionFacet()),
				settings(FIELD, null, "sv", 1)).get(0).values();

		assertEquals(List.of(new FacetValueDto("Sverige", 2, List.of("nb", "sv")), new FacetValueDto(null, 4, List.of())),
				values);
	}

	@Test
	void merge_leavesANonLiteralFacetUntouchedEvenWithFacetLang() {
		FacetField rdfType = new FacetField("rdfType");
		rdfType.add("http://example.org/A", 5);
		rdfType.add("http://example.org/B", 1);

		List<FacetValuesDto> result = LanguageAwareFacets.merge(List.of(rdfType), settings("rdfType", null, "sv", 1));

		assertEquals(List.of(new FacetValuesDto("rdfType", List.of(
				new FacetValueDto("http://example.org/A", 5, List.of()),
				new FacetValueDto("http://example.org/B", 1, List.of())))), result);
	}

	@Test
	void merge_labelWithoutACompanionTermHasNoLanguagesAndIsDroppedByFacetLang() {
		// A label above the indexer's length cap has no companion term at all.
		FacetField field = new FacetField(FIELD);
		field.add("A very long description", 7);
		FacetField companion = new FacetField(COMPANION);

		assertEquals(List.of(new FacetValueDto("A very long description", 7, List.of())),
				LanguageAwareFacets.merge(List.of(field, companion), settings(FIELD, null, null, 100)).get(0).values());
		assertEquals(List.of(),
				LanguageAwareFacets.merge(List.of(field, companion), settings(FIELD, null, "sv", 100)).get(0).values());
	}

	@Test
	void merge_reportsADuplicatedResponseFieldOnce() {
		List<FacetValuesDto> result = LanguageAwareFacets.merge(
				List.of(clientFacet(), companionFacet(), clientFacet()), settings(FIELD, null, null, 100));

		assertEquals(1, result.size());
	}
}
