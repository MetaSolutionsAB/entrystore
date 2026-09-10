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
import org.entrystore.repository.util.LangFacetValue;
import org.entrystore.repository.util.SolrSearchIndex;
import org.entrystore.rest.springboot.model.dto.FacetValueDto;
import org.entrystore.rest.springboot.model.dto.FacetValuesDto;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Server-side language information for literal facets on {@code GET /search?type=solr}.
 *
 * <p>Clients facet on {@code metadata.predicate.literal_s.<hash>} (or the {@code metadata.predicate.literal.<hash>}
 * shorthand) and receive one bucket per label with the same count as before. For every such field this class also
 * facets on the internal companion {@code metadata.predicate.literal_l.<hash>}, whose terms are
 * {@code label + U+001F + language}, without a limit, and merges the companion buckets into a {@code lang} list per
 * label. With {@code facetLang} set, only labels that occur in that language (RFC 4647 basic filtering, so {@code en}
 * covers {@code en-GB}) or untagged are kept; that selection happens before the client's {@code facetLimit}, so the
 * top N is taken within the requested language, and the count stays the per-label count, so a drill-down on the
 * {@code literal_s} field returns exactly that many hits. Companion fields never reach the client.
 */
final class LanguageAwareFacets {

	private static final String LITERAL_SHORTHAND_PREFIX = "metadata.predicate.literal.";

	private static final String LITERAL_S_PREFIX = "metadata.predicate.literal_s.";

	private static final Comparator<FacetValueDto> BY_COUNT_DESC_THEN_NAME =
			Comparator.comparingLong(FacetValueDto::count).reversed().thenComparing(FacetValueDto::name);

	private LanguageAwareFacets() {
	}

	/** The field a client-supplied facet name addresses: the literal shorthand is rewritten to its string field. */
	static String clientField(String requested) {
		return requested.trim().replace(LITERAL_SHORTHAND_PREFIX, LITERAL_S_PREFIX);
	}

	/** Adds the requested facet fields and, for literal facets, their unlimited companions to {@code query}. */
	static void configure(SolrQuery query, SolrSearchIndex.FacetSettings settings) {
		query.setFacet(true);
		query.setFacetMinCount(settings.minCount);
		query.setFacetLimit(settings.limit);
		query.setFacetMissing(settings.missing);
		if (settings.matches != null) {
			query.setParam("facet.matches", settings.matches);
		}
		for (String requested : settings.fields.split(",")) {
			String field = clientField(requested);
			query.addFacetField(field);
			String companion = LangFacetValue.companionField(field);
			if (companion == null) {
				continue;
			}
			// The companion must carry every (label, lang) pair of the result set, or labels the client sees would
			// lose languages; with facetLang the client field is unlimited too, since the top-N is taken after the
			// language filter.
			query.addFacetField(companion);
			query.setParam("f." + companion + ".facet.limit", "-1");
			query.setParam("f." + companion + ".facet.mincount", "1");
			query.setParam("f." + companion + ".facet.missing", "false");
			if (settings.matches != null) {
				query.setParam("f." + companion + ".facet.matches", LangFacetValue.labelMatchesRegex(settings.matches));
			}
			if (settings.lang != null) {
				query.setParam("f." + field + ".facet.limit", "-1");
			}
		}
	}

	/** Turns the raw Solr facet fields into the client-facing list: companions are merged in and then dropped. */
	static List<FacetValuesDto> merge(List<FacetField> response, SolrSearchIndex.FacetSettings settings) {
		Map<String, FacetField> byName = new LinkedHashMap<>();
		for (FacetField facetField : response) {
			byName.putIfAbsent(facetField.getName(), facetField);
		}
		List<FacetValuesDto> result = new ArrayList<>();
		for (FacetField facetField : byName.values()) {
			if (LangFacetValue.isLangFacetField(facetField.getName())) {
				continue;
			}
			String companionName = LangFacetValue.companionField(facetField.getName());
			FacetField companion = companionName == null ? null : byName.get(companionName);
			List<FacetValueDto> values = companion == null
					? plainValues(facetField)
					: mergedValues(facetField, companion, settings);
			result.add(new FacetValuesDto(facetField.getName(), values));
		}
		return result;
	}

	private static List<FacetValueDto> plainValues(FacetField facetField) {
		List<FacetValueDto> values = new ArrayList<>();
		for (FacetField.Count bucket : facetField.getValues()) {
			values.add(new FacetValueDto(bucket.getName(), bucket.getCount(), List.of()));
		}
		return values;
	}

	private static List<FacetValueDto> mergedValues(FacetField facetField, FacetField companion,
													 SolrSearchIndex.FacetSettings settings) {
		Map<String, TreeSet<String>> langsByLabel = new HashMap<>();
		Set<String> untaggedLabels = new HashSet<>();
		for (FacetField.Count bucket : companion.getValues()) {
			if (bucket.getName() == null) {
				continue;
			}
			LangFacetValue term = LangFacetValue.decode(bucket.getName());
			if (term.lang() == null) {
				untaggedLabels.add(term.label());
			} else {
				langsByLabel.computeIfAbsent(term.label(), label -> new TreeSet<>()).add(term.lang());
			}
		}

		String range = settings.lang == null ? null : LangFacetValue.normalizeLanguageTag(settings.lang);
		List<FacetValueDto> values = new ArrayList<>();
		FacetValueDto missing = null;
		for (FacetField.Count bucket : facetField.getValues()) {
			if (bucket.getName() == null) {
				missing = new FacetValueDto(null, bucket.getCount(), List.of());
				continue;
			}
			Set<String> langSet = langsByLabel.get(bucket.getName());
			List<String> langs = langSet == null ? List.of() : List.copyOf(langSet);
			if (range != null && !untaggedLabels.contains(bucket.getName())
					&& langs.stream().noneMatch(lang -> LangFacetValue.matchesLanguage(lang, range))) {
				continue;
			}
			values.add(new FacetValueDto(bucket.getName(), bucket.getCount(), langs));
		}
		if (range != null) {
			// Solr saw the client field unlimited; apply its ordering and the client's limit to what survived.
			values.sort(BY_COUNT_DESC_THEN_NAME);
			if (values.size() > settings.limit) {
				values = new ArrayList<>(values.subList(0, settings.limit));
			}
		}
		if (missing != null) {
			values.add(missing);
		}
		return values;
	}
}
