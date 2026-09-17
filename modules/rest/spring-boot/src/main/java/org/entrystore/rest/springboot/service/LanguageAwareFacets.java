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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Server-side language filtering for literal facets on {@code GET /search?type=solr}.
 *
 * <p>Without {@code facetLang} this adds nothing: the Solr request is the one the endpoint sent before language
 * support existed, with the configured facet limit applied. With {@code facetLang} the selection happens inside
 * Solr, against the internal companion field {@code metadata.predicate.literal_l.<hash>} whose terms are
 * {@code label + U+001F + language}. Candidate labels come from that companion under a bounded overrequest, and
 * their exact counts from a second {@code rows=0} request on the client field, so the buckets a client sees carry
 * the same counts as an unfiltered facet. Companion fields never reach the client.
 *
 * <p>Two consequences of the overrequest are worth knowing, and are documented on the {@code facetLang} parameter.
 * The counts of the returned buckets are exact, but the last slots of the top N are approximate, which is the same
 * contract Solr's own distributed faceting gives. And a label longer than {@link LangFacetValue#MAX_LABEL_LENGTH}
 * has no companion term, so it cannot be selected while {@code facetLang} is set.
 *
 * <p>Counts are Solr's, taken before the per-entry authorization filtering in {@code SolrSearchIndex.sendQuery}, so
 * a caller who may not read every matching entry can see a count higher than the hits a drill-down returns.
 */
final class LanguageAwareFacets {

	private static final Logger log = LoggerFactory.getLogger(LanguageAwareFacets.class);

	private static final String LITERAL_SHORTHAND_PREFIX = "metadata.predicate.literal.";

	private static final String LITERAL_S_PREFIX = "metadata.predicate.literal_s.";

	/** Terms per requested bucket to ask the companion for, since several terms can share one label. */
	private static final int OVERREQUEST_FACTOR = 4;

	private static final Comparator<FacetValueDto> BY_COUNT_DESC_THEN_NAME =
			Comparator.comparingLong(FacetValueDto::count).reversed().thenComparing(FacetValueDto::name);

	private LanguageAwareFacets() {
	}

	/** The field a client-supplied facet name addresses: the literal shorthand is rewritten to its string field. */
	static String clientField(String requested) {
		return requested.trim().replace(LITERAL_SHORTHAND_PREFIX, LITERAL_S_PREFIX);
	}

	/**
	 * Adds the requested facet fields to {@code query}, and with {@code facetLang} the bounded companion facet that
	 * selects the candidate labels.
	 *
	 * @param maxFacetLimit the configured {@code entrystore.solr.facet-max-limit}, which bounds the overrequest
	 */
	static void configure(SolrQuery query, SolrSearchIndex.FacetSettings settings, int maxFacetLimit) {
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
			if (settings.lang == null) {
				continue;
			}
			String companion = LangFacetValue.companionField(field);
			if (companion == null) {
				continue;
			}
			query.addFacetField(companion);
			query.setParam("f." + companion + ".facet.limit", Integer.toString(overrequestLimit(settings, maxFacetLimit)));
			query.setParam("f." + companion + ".facet.mincount", "1");
			query.setParam("f." + companion + ".facet.missing", "false");
			query.setParam("f." + companion + ".facet.matches", languageMatchesRegex(settings));
		}
	}

	/**
	 * Bounded: several terms can map to one label, but never more than the configured cap allows. A cap of zero or
	 * less means none was configured, which must not collapse the overrequest and return an empty facet.
	 */
	private static int overrequestLimit(SolrSearchIndex.FacetSettings settings, int maxFacetLimit) {
		int bound = maxFacetLimit > 0 ? Math.min(settings.limit, maxFacetLimit) : settings.limit;
		return Math.max(bound, 1) * OVERREQUEST_FACTOR;
	}

	/**
	 * Full-string regex over an encoded companion term: the label part, the separator, then the empty tag, the
	 * requested range, or the range followed by a subtag. {@code facetLang} is validated against
	 * {@code [A-Za-z0-9-]}, so it carries no regex metacharacter.
	 */
	private static String languageMatchesRegex(SolrSearchIndex.FacetSettings settings) {
		String label = settings.matches == null ? null : settings.matches;
		String range = LangFacetValue.normalizeLanguageTag(settings.lang);
		return LangFacetValue.languageMatchesRegex(label, range);
	}

	/**
	 * Turns the raw Solr facet fields into the client-facing list. Companion fields are consumed and dropped; with
	 * {@code facetLang} the surviving labels are re-counted exactly and cut to the client's limit.
	 */
	static List<FacetValuesDto> merge(List<FacetField> response, SolrSearchIndex.FacetSettings settings,
									  SolrQuery executedQuery, SolrSearchIndex index) {
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
					: languageFilteredValues(facetField, companion, settings, executedQuery, index);
			result.add(new FacetValuesDto(facetField.getName(), values));
		}
		return result;
	}

	private static List<FacetValueDto> plainValues(FacetField facetField) {
		List<FacetValueDto> values = new ArrayList<>();
		for (FacetField.Count bucket : facetField.getValues()) {
			values.add(new FacetValueDto(bucket.getName(), bucket.getCount()));
		}
		return values;
	}

	private static List<FacetValueDto> languageFilteredValues(FacetField facetField, FacetField companion,
															  SolrSearchIndex.FacetSettings settings,
															  SolrQuery executedQuery, SolrSearchIndex index) {
		Set<String> candidates = new LinkedHashSet<>();
		for (FacetField.Count bucket : companion.getValues()) {
			if (bucket.getName() != null) {
				candidates.add(LangFacetValue.decode(bucket.getName()).label());
			}
		}

		FacetValueDto missing = missingBucket(facetField);
		if (candidates.isEmpty()) {
			// No overlap between the filters is an ordinary answer, so the stale index is told apart from it by
			// asking whether the companion holds any term at all here; only this path pays for that request.
			if (facetField.getValueCount() > 0 && !index.hasFacetTerms(executedQuery, companion.getName())) {
				log.warn("facetLang found no language terms on {} although the facet has {} buckets; the index predates "
								+ "the language companion field. Run a reindex to enable language filtering. Returning "
								+ "the unfiltered facet.", facetField.getName(), facetField.getValueCount());
				return plainValues(facetField);
			}
			return missing == null ? List.of() : List.of(missing);
		}

		Map<String, Long> exactCounts = index.facetCountsForLabels(executedQuery, facetField.getName(), candidates);
		List<FacetValueDto> values = new ArrayList<>();
		for (Map.Entry<String, Long> counted : exactCounts.entrySet()) {
			if (counted.getValue() >= settings.minCount) {
				values.add(new FacetValueDto(counted.getKey(), counted.getValue()));
			}
		}
		values.sort(BY_COUNT_DESC_THEN_NAME);
		if (values.size() > settings.limit) {
			values = new ArrayList<>(values.subList(0, settings.limit));
		}
		if (missing != null) {
			values.add(missing);
		}
		return values;
	}

	private static FacetValueDto missingBucket(FacetField facetField) {
		for (FacetField.Count bucket : facetField.getValues()) {
			if (bucket.getName() == null) {
				return new FacetValueDto(null, bucket.getCount());
			}
		}
		return null;
	}
}
