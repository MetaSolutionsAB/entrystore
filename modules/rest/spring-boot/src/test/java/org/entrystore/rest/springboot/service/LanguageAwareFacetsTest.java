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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Without {@code facetLang} this class must add nothing to the Solr request and pass Solr's buckets straight
 * through, because {@code /search} is reachable by guests and every facet parameter it sets is one the configured
 * cap would otherwise bound. With {@code facetLang} the selection happens in Solr under a bounded overrequest, and
 * the counts come from a second exact request.
 */
class LanguageAwareFacetsTest {

	private static final String FIELD = "metadata.predicate.literal_s.abc12345";
	private static final String COMPANION = "metadata.predicate.literal_l.abc12345";
	private static final int MAX_FACET_LIMIT = 1000;

	private static SolrSearchIndex.FacetSettings settings(String fields, String matches, String lang, int limit) {
		var settings = new SolrSearchIndex.FacetSettings();
		settings.fields = fields;
		settings.matches = matches;
		settings.lang = lang;
		settings.limit = limit;
		settings.minCount = 1;
		return settings;
	}

	/** The unit separator the companion terms carry between label and language tag. */
	private static final char SEPARATOR = (char) 0x1F;

	private static String term(String label, String lang) {
		return label + SEPARATOR + (lang == null ? "" : lang);
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

	/** What Solr returns for the companion once facet.matches has already selected the language. */
	private static FacetField companionFacet(String... terms) {
		FacetField companion = new FacetField(COMPANION);
		for (String term : terms) {
			companion.add(term, 1);
		}
		return companion;
	}

	private static SolrSearchIndex indexReturning(Map<String, Long> counts) {
		SolrSearchIndex index = mock(SolrSearchIndex.class);
		when(index.facetCountsForLabels(any(), anyString(), any())).thenReturn(counts);
		return index;
	}

	private static List<String> names(List<FacetValueDto> values) {
		return values.stream().map(FacetValueDto::name).toList();
	}

	private static List<FacetValueDto> onlyFacet(List<FacetValuesDto> result) {
		assertEquals(1, result.size(), "the companion must not appear as a facet field of its own");
		assertEquals(FIELD, result.getFirst().name());
		return result.getFirst().values();
	}

	@Test
	void configure_withoutFacetLang_addsOnlyTheRequestedFieldAndTheCappedLimit() {
		SolrQuery query = new SolrQuery("*:*");

		LanguageAwareFacets.configure(query, settings(FIELD, null, null, 10), MAX_FACET_LIMIT);

		assertEquals(List.of(FIELD), List.of(query.getFacetFields()),
				"no companion may be requested without facetLang: the request must match the pre-language one");
		assertEquals("10", query.get("facet.limit"));
		assertNull(query.get("f." + COMPANION + ".facet.limit"));
		assertNull(query.get("f." + FIELD + ".facet.limit"), "the client field must stay under the global limit");
	}

	@Test
	void configure_withoutFacetLang_addsNothingPerFieldEvenWithFacetMatches() {
		SolrQuery query = new SolrQuery("*:*");

		LanguageAwareFacets.configure(query, settings(FIELD, "Sver", null, 10), MAX_FACET_LIMIT);

		assertEquals("Sver", query.get("facet.matches"));
		assertTrue(query.getParameterNames().stream().noneMatch(name -> name.startsWith("f.")),
				"a per-field override would be a request this endpoint did not send before");
	}

	@Test
	void configure_rewritesTheLiteralShorthandToTheStringField() {
		SolrQuery query = new SolrQuery("*:*");

		LanguageAwareFacets.configure(query, settings("metadata.predicate.literal.abc12345", null, null, 10), MAX_FACET_LIMIT);

		assertEquals(List.of(FIELD), List.of(query.getFacetFields()));
	}

	@Test
	void configure_withFacetLang_addsABoundedCompanionFacet() {
		SolrQuery query = new SolrQuery("*:*");

		LanguageAwareFacets.configure(query, settings(FIELD, null, "sv", 10), MAX_FACET_LIMIT);

		assertEquals(List.of(FIELD, COMPANION), List.of(query.getFacetFields()));
		assertEquals("40", query.get("f." + COMPANION + ".facet.limit"), "overrequest is bounded, never unlimited");
		assertEquals("1", query.get("f." + COMPANION + ".facet.mincount"));
		assertEquals("false", query.get("f." + COMPANION + ".facet.missing"));
		assertNull(query.get("f." + FIELD + ".facet.limit"), "the client field must stay under the global limit");
	}

	@Test
	void configure_withFacetLang_boundsTheOverrequestByTheConfiguredCap() {
		SolrQuery query = new SolrQuery("*:*");

		LanguageAwareFacets.configure(query, settings(FIELD, null, "sv", 5000), 100);

		assertEquals("400", query.get("f." + COMPANION + ".facet.limit"),
				"a client asking for more buckets than the cap must not widen the overrequest past it");
	}

	@Test
	void configure_withFacetLang_addsNoCompanionForANonLiteralFacet() {
		SolrQuery query = new SolrQuery("*:*");

		LanguageAwareFacets.configure(query, settings("rdfType", null, "sv", 10), MAX_FACET_LIMIT);

		assertEquals(List.of("rdfType"), List.of(query.getFacetFields()));
		assertTrue(query.getParameterNames().stream().noneMatch(name -> name.startsWith("f.")));
	}

	@Test
	void configure_withFacetLang_selectsTheLanguageAndUntaggedTermsInSolr() {
		SolrQuery query = new SolrQuery("*:*");

		LanguageAwareFacets.configure(query, settings(FIELD, null, "sv", 10), MAX_FACET_LIMIT);
		Pattern pattern = Pattern.compile(query.get("f." + COMPANION + ".facet.matches"));

		assertTrue(pattern.matcher(term("Sverige", "sv")).matches());
		assertTrue(pattern.matcher(term("Sverige", "sv-FI")).matches(), "a subtag of the range must match");
		assertTrue(pattern.matcher(term("Stockholm", null)).matches(), "untagged labels are always included");
		assertTrue(pattern.matcher(term("Sverige", "SV")).matches(), "matching is case-insensitive");
		assertFalse(pattern.matcher(term("Sweden", "en")).matches());
		assertFalse(pattern.matcher(term("Sweden", "svenska")).matches(), "a longer tag is not a subtag");
	}

	@Test
	void configure_withFacetLang_anchorsFacetMatchesToTheLabelPart() {
		SolrQuery query = new SolrQuery("*:*");

		LanguageAwareFacets.configure(query, settings(FIELD, "Sver-1", "sv", 10), MAX_FACET_LIMIT);
		Pattern pattern = Pattern.compile(query.get("f." + COMPANION + ".facet.matches"));

		assertTrue(pattern.matcher(term("Sver-1", "sv")).matches());
		assertTrue(pattern.matcher(term("Sver-1", null)).matches());
		assertFalse(pattern.matcher(term("Sver-10", "sv")).matches(), "the label part must be anchored");
		assertFalse(pattern.matcher(term("Sver-1", "en")).matches(), "the language part still applies");
	}

	@Test
	void merge_withoutFacetLang_returnsSolrBucketsUnchangedAndAsksSolrNothingMore() {
		SolrSearchIndex index = mock(SolrSearchIndex.class);

		List<FacetValueDto> values = onlyFacet(LanguageAwareFacets.merge(List.of(clientFacet()),
				settings(FIELD, null, null, 10), new SolrQuery("*:*"), index));

		assertEquals(List.of("Sweden", "Sverige", "Britain", "Stockholm"), names(values));
		assertEquals(List.of(3L, 2L, 1L, 1L), values.stream().map(FacetValueDto::count).toList());
		verify(index, never()).facetCountsForLabels(any(), anyString(), any());
	}

	@Test
	void merge_withFacetLang_returnsTheExactCountsOfTheSelectedLabels() {
		Map<String, Long> counts = new LinkedHashMap<>();
		counts.put("Sverige", 2L);
		counts.put("Stockholm", 1L);
		SolrSearchIndex index = indexReturning(counts);

		List<FacetValueDto> values = onlyFacet(LanguageAwareFacets.merge(
				List.of(clientFacet(), companionFacet(term("Sverige", "sv"), term("Stockholm", null))),
				settings(FIELD, null, "sv", 10), new SolrQuery("*:*"), index));

		assertEquals(List.of("Sverige", "Stockholm"), names(values));
		assertEquals(List.of(2L, 1L), values.stream().map(FacetValueDto::count).toList());
	}

	@Test
	void merge_withFacetLang_dedupesALabelThatOccursInSeveralTerms() {
		SolrSearchIndex index = indexReturning(Map.of("Sverige", 2L));

		onlyFacet(LanguageAwareFacets.merge(
				List.of(clientFacet(), companionFacet(term("Sverige", "sv"), term("Sverige", "sv-FI"), term("Sverige", null))),
				settings(FIELD, null, "sv", 10), new SolrQuery("*:*"), index));

		verify(index).facetCountsForLabels(any(), eq(FIELD), eq(java.util.Set.of("Sverige")));
	}

	@Test
	void merge_withFacetLang_sortsByCountThenNameAndAppliesTheClientLimit() {
		Map<String, Long> counts = new LinkedHashMap<>();
		counts.put("Alfa", 1L);
		counts.put("Sverige", 2L);
		counts.put("Beta", 1L);
		SolrSearchIndex index = indexReturning(counts);

		List<FacetValueDto> values = onlyFacet(LanguageAwareFacets.merge(
				List.of(clientFacet(), companionFacet(term("Alfa", "sv"), term("Sverige", "sv"), term("Beta", "sv"))),
				settings(FIELD, null, "sv", 2), new SolrQuery("*:*"), index));

		assertEquals(List.of("Sverige", "Alfa"), names(values), "count first, then name, then cut to the limit");
	}

	@Test
	void merge_withFacetLang_andNoCandidatesFallsBackToTheUnfilteredFacet() {
		SolrSearchIndex index = indexReturning(Map.of());

		List<FacetValueDto> values = onlyFacet(LanguageAwareFacets.merge(
				List.of(clientFacet(), companionFacet()),
				settings(FIELD, null, "sv", 10), new SolrQuery("*:*"), index));

		assertEquals(List.of("Sweden", "Sverige", "Britain", "Stockholm"), names(values),
				"an index predating the companion field must still answer, unfiltered rather than empty");
		verify(index, never()).facetCountsForLabels(any(), anyString(), any());
	}

	@Test
	void merge_keepsTheMissingBucketLast() {
		FacetField field = clientFacet();
		field.add(null, 4);
		SolrSearchIndex index = indexReturning(Map.of("Sverige", 2L));

		List<FacetValueDto> values = onlyFacet(LanguageAwareFacets.merge(
				List.of(field, companionFacet(term("Sverige", "sv"))),
				settings(FIELD, null, "sv", 1), new SolrQuery("*:*"), index));

		assertEquals(Arrays.asList("Sverige", null), names(values));
		assertEquals(4L, values.getLast().count());
	}

	@Test
	void merge_leavesANonLiteralFacetUntouched() {
		FacetField rdfType = new FacetField("rdfType");
		rdfType.add("http://example.com/A", 5);
		rdfType.add("http://example.com/B", 4);
		SolrSearchIndex index = mock(SolrSearchIndex.class);

		List<FacetValuesDto> result = LanguageAwareFacets.merge(List.of(rdfType),
				settings("rdfType", null, "sv", 1), new SolrQuery("*:*"), index);

		assertEquals(List.of("http://example.com/A", "http://example.com/B"), names(result.getFirst().values()),
				"facetLang concerns literal facets only");
	}

	@Test
	void merge_reportsADuplicatedResponseFieldOnce() {
		SolrSearchIndex index = mock(SolrSearchIndex.class);

		List<FacetValuesDto> result = LanguageAwareFacets.merge(List.of(clientFacet(), clientFacet()),
				settings(FIELD, null, null, 10), new SolrQuery("*:*"), index);

		assertEquals(1, result.size());
	}
}
