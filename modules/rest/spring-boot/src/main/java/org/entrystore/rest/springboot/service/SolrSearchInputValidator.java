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

import jakarta.annotation.PostConstruct;
import org.entrystore.repository.util.LangFacetValue;
import org.entrystore.rest.springboot.model.api.FacetSettingsRequestParams;
import org.entrystore.rest.springboot.model.exception.BadRequestException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URLDecoder;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * Boundary validation for the Solr-backed {@code /search?type=solr} endpoint, which is guest-accessible.
 *
 * <p>{@code sort} and {@code facetFields} accept any plain field name, including dynamic ones such as
 * {@code metadata.predicate.integer.<hash>}; Solr itself answers an unknown field with 400. Two shapes are
 * refused: anything but a plain name (see {@link #PLAIN_FIELD}), because Solr reads local params such as
 * {@code {!facet.matches=...}} in a facet field and lets them override the request, and the server-internal
 * language companion {@code [related.]metadata.predicate.literal_l.*} (see {@code LanguageAwareFacets}).
 *
 * <p>{@code facetMatches} and {@code facetLang} are restricted to literal-only patterns because Solr evaluates
 * them as regular expressions, so a crafted pattern could tie up Solr (ReDoS). The length and count caps are
 * opt-in ({@code entrystore.solr.search.*}, 0 by default, which means unlimited). Violations throw
 * {@link BadRequestException}, which {@code AppExceptionHandler} maps to 400.
 */
@Service
public class SolrSearchInputValidator {

	/**
	 * A plain Solr field name, which covers every field the index declares, e.g. {@code title.sv-SE} and
	 * {@code related.metadata.predicate.literal_s.<hash>}. Admitting braces, exclamation marks, equals signs or
	 * spaces would let local params through, which override facet.matches, facet.limit and the response key.
	 */
	private static final Pattern PLAIN_FIELD = Pattern.compile("^[A-Za-z0-9_.\\-]+$");

	/**
	 * Constrains {@code facetMatches} to a literal-only character class (alphanumerics, underscore,
	 * dash) so the value cannot exploit Solr's regex evaluator. Any change to this pattern must
	 * preserve that property — relaxing the class to include regex metacharacters (e.g. {@code .},
	 * {@code *}, {@code +}, parentheses) reopens the ReDoS surface this class was added to close.
	 */
	private static final Pattern FACET_MATCHES = Pattern.compile("^[\\w-]{1,64}$");

	/**
	 * Constrains {@code facetLang} to the BCP 47 alphabet (alphanumerics and dash, at most 35 characters, the
	 * length limit of a well-formed tag). The value is embedded verbatim in the {@code facet.matches} regex that
	 * Solr evaluates on the language companion field. Any change to this pattern must keep it literal-only, like
	 * {@link #FACET_MATCHES}: admitting regex metacharacters (e.g. a {@code *-CH} wildcard) reopens the ReDoS and
	 * regex-injection surface.
	 */
	private static final Pattern FACET_LANG = Pattern.compile("^[A-Za-z0-9-]{1,35}$");

	@Value("${entrystore.solr.search.query.max-length:0}")
	private int maxQueryLength;

	@Value("${entrystore.solr.search.sort.max-length:0}")
	private int maxSortLength;

	@Value("${entrystore.solr.search.filter-query.max-length:0}")
	private int maxFilterQueryLength;

	@Value("${entrystore.solr.search.filter-query.max-count:0}")
	private int maxFilterQueryCount;

	@Value("${entrystore.solr.search.facet-fields.max-length:0}")
	private int maxFacetFieldsLength;

	@Value("${entrystore.solr.search.facet-fields.max-count:0}")
	private int maxFacetFieldCount;

	/**
	 * Fails startup on a negative limit, which would otherwise silently act as "unlimited".
	 */
	@PostConstruct
	void checkLimits() {
		requireNonNegative(maxQueryLength, "entrystore.solr.search.query.max-length");
		requireNonNegative(maxSortLength, "entrystore.solr.search.sort.max-length");
		requireNonNegative(maxFilterQueryLength, "entrystore.solr.search.filter-query.max-length");
		requireNonNegative(maxFilterQueryCount, "entrystore.solr.search.filter-query.max-count");
		requireNonNegative(maxFacetFieldsLength, "entrystore.solr.search.facet-fields.max-length");
		requireNonNegative(maxFacetFieldCount, "entrystore.solr.search.facet-fields.max-count");
	}

	private static void requireNonNegative(int limit, String key) {
		if (limit < 0) {
			throw new IllegalStateException(key + " must be 0 (unlimited) or positive, got " + limit);
		}
	}

	/** A limit of 0 means unlimited. */
	private static boolean exceeds(int value, int limit) {
		return limit > 0 && value > limit;
	}

	public void validateQuery(String query) {
		if (query != null && exceeds(query.length(), maxQueryLength)) {
			throw new BadRequestException(
					"Query parameter 'query' exceeds maximum length of " + maxQueryLength);
		}
	}

	public void validateSort(String sort) {
		if (sort == null || sort.isEmpty()) {
			return;
		}
		if (exceeds(sort.length(), maxSortLength)) {
			throw new BadRequestException(
					"Query parameter 'sort' exceeds maximum length of " + maxSortLength);
		}
		for (String token : sort.split("[,\\s]+")) {
			if (!token.isEmpty()) {
				requirePlainField(token, "sort");
			}
		}
	}

	/**
	 * Splits the raw {@code filterQuery} parameter into individual Solr filter queries and validates them.
	 * The comma separator is matched before URL-decoding so that an unencoded comma separates filter
	 * queries while an encoded one ({@code %2C}) stays inside a filter query.
	 *
	 * @return the decoded filter queries; empty when the parameter is absent (a blank value yields one empty query)
	 * @throws BadRequestException if the raw value exceeds {@code entrystore.solr.search.filter-query.max-length} or
	 *                             splits into more than {@code entrystore.solr.search.filter-query.max-count} entries,
	 *                             where a limit of 0 means unlimited
	 */
	public List<String> parseFilterQueries(String rawFilterQuery) {
		List<String> filterQueries = rawFilterQuery == null
				? List.of()
				: Arrays.stream(rawFilterQuery.split(",")).map(fq -> URLDecoder.decode(fq, UTF_8)).toList();
		validateFilterQueries(filterQueries, rawFilterQuery);
		return filterQueries;
	}

	void validateFilterQueries(List<String> filterQueries, String rawFilterQuery) {
		if (rawFilterQuery == null || rawFilterQuery.isEmpty()) {
			return;
		}
		if (exceeds(rawFilterQuery.length(), maxFilterQueryLength)) {
			throw new BadRequestException(
					"Query parameter 'filterQuery' exceeds maximum length of " + maxFilterQueryLength);
		}
		if (exceeds(filterQueries.size(), maxFilterQueryCount)) {
			throw new BadRequestException(
					"Query parameter 'filterQuery' contains more than " + maxFilterQueryCount + " entries");
		}
		// Per-entry backstop. Unreachable via parseFilterQueries (decode only shrinks the already
		// length-capped raw value); kept for same-package callers passing pre-decoded entries.
		for (String fq : filterQueries) {
			if (fq != null && exceeds(fq.length(), maxFilterQueryLength)) {
				throw new BadRequestException(
						"Query parameter 'filterQuery' entry exceeds maximum length of " + maxFilterQueryLength);
			}
		}
	}

	public void validateFacetSettings(FacetSettingsRequestParams request) {
		String facetFields = request.getFacetFields();
		String facetMatches = request.getFacetMatches();
		validateFacetFields(facetFields);
		validateFacetMatches(facetMatches, facetFields);
		validateFacetLang(request.getFacetLang(), facetFields);
	}

	private void validateFacetFields(String facetFields) {
		if (facetFields == null || facetFields.isEmpty()) {
			return;
		}
		if (exceeds(facetFields.length(), maxFacetFieldsLength)) {
			throw new BadRequestException(
					"Query parameter 'facetFields' exceeds maximum length of " + maxFacetFieldsLength);
		}
		String[] fields = facetFields.split(",");
		if (exceeds(fields.length, maxFacetFieldCount)) {
			throw new BadRequestException(
					"Query parameter 'facetFields' contains more than " + maxFacetFieldCount + " entries");
		}
		for (String field : fields) {
			String clientField = LanguageAwareFacets.clientField(field);
			if (!clientField.isEmpty()) {
				requirePlainField(clientField, "facetFields");
			}
		}
	}

	/** Without facetFields, facetMatches never reaches Solr, so it is ignored. */
	private static void validateFacetMatches(String matches, String facetFields) {
		if (matches == null || matches.isEmpty() || facetFields == null || facetFields.isEmpty()) {
			return;
		}
		if (!FACET_MATCHES.matcher(matches).matches()) {
			throw new BadRequestException(
					"Query parameter 'facetMatches' must match pattern " + FACET_MATCHES.pattern());
		}
	}

	private static void validateFacetLang(String facetLang, String facetFields) {
		if (facetLang == null || facetLang.isEmpty()) {
			// An empty value is equivalent to omitting the parameter, like an empty facetMatches.
			return;
		}
		if (facetFields == null || facetFields.isEmpty()) {
			throw new BadRequestException(
					"Query parameter 'facetLang' requires 'facetFields' to be set");
		}
		if (!FACET_LANG.matcher(facetLang).matches()) {
			throw new BadRequestException(
					"Query parameter 'facetLang' must be a BCP 47 language tag matching pattern " + FACET_LANG.pattern());
		}
	}

	private static void requirePlainField(String field, String parameterName) {
		if (!PLAIN_FIELD.matcher(field).matches() || LangFacetValue.isLangFacetField(field)) {
			throw new BadRequestException(
					"Field '" + field + "' is not permitted in '" + parameterName + "'");
		}
	}
}
