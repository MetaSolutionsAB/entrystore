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

import org.entrystore.repository.util.SolrSearchIndex;
import org.entrystore.rest.springboot.model.api.FacetSettingsRequestParams;
import org.entrystore.rest.springboot.model.exception.BadRequestException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Boundary validation for the Solr-backed {@code /search?type=solr} endpoint. The endpoint is
 * guest-accessible, so every parameter that flows into {@link org.apache.solr.client.solrj.request.SolrQuery}
 * is treated as untrusted input. The validator caps each parameter's length, caps the number of
 * filter-queries and facet-fields, restricts {@code sort} and {@code facetFields} field names to a
 * fixed allow-list (plus a few dynamic {@code metadata.predicate.*} families), and constrains
 * {@code facetMatches} to a literal-only pattern so Solr's per-field regex filter cannot be
 * abused for ReDoS or arbitrary regex evaluation. Violations throw {@link BadRequestException};
 * {@code AppExceptionHandler} maps that to {@code 400 Bad Request}. The validator also
 * normalises the two request values that are bounded rather than rejected: {@code limit} is clamped to
 * {@code entrystore.solr.max-limit} and the comma-separated {@code filterQuery} is split into its individual
 * filter queries.
 */
@Service
public class SolrSearchInputValidator {

	/**
	 * Field names exposed to client {@code sort} and {@code facetFields} input. Adding a field here
	 * makes it user-controllable on the public {@code /search} endpoint — verify the field is
	 * declared and indexed in the production Solr schema before adding.
	 */
	private static final Set<String> ALLOWED_NAMED_FIELDS = Set.of(
			"uri", "resource", "context", "rdfType", "creator", "contributors",
			"lists", "entryType", "resourceType", "username", "contextname",
			"profile", "projectType", "lang", "status", "email", "tag.uri",
			"acl.admin", "acl.metadata.r", "acl.metadata.rw",
			"acl.resource.r", "acl.resource.rw",
			// Sort defaults applied in SearchService when no `sort` is supplied; clients may also
			// request these explicitly. `score` is Solr's implicit relevance pseudo-field, not a
			// schema field.
			"score", "modified", "created");

	/**
	 * Allowed prefixes for dynamic Solr fields. The tail after the prefix must match
	 * {@link #DYNAMIC_TAIL}. The {@code metadata.predicate.literal.} prefix is the shorthand form
	 * rewritten to {@code metadata.predicate.literal_s.} by {@code SearchService} when used as a
	 * facet field; the rewrite does NOT apply to sort clauses, so passing
	 * {@code metadata.predicate.literal.<tail>} in {@code sort=} reaches Solr unrewritten. The
	 * {@code title.} prefix covers the sort form {@code title.<lang>}, which {@code SearchService}
	 * rewrites to {@code title_sort.<lang>}.
	 */
	private static final List<String> ALLOWED_DYNAMIC_PREFIXES = List.of(
			"metadata.predicate.uri.",
			"metadata.predicate.literal_s.",
			"metadata.predicate.literal_t.",
			"metadata.predicate.literal.",
			"related.metadata.predicate.uri.",
			"related.metadata.predicate.literal_s.",
			"related.metadata.predicate.literal_t.",
			"title.");

	/**
	 * Conservative tail for dynamic-prefix fields: alphanumerics, underscore, dash, and dot. This
	 * covers hashed predicate URIs (hex, sometimes with separators) and ISO language tags used for
	 * the {@code title.} sort form.
	 */
	private static final Pattern DYNAMIC_TAIL = Pattern.compile("^[A-Za-z0-9_.\\-]{1,64}$");

	/**
	 * Constrains {@code facetMatches} to a literal-only character class (alphanumerics, underscore,
	 * dash) so the value cannot exploit Solr's regex evaluator. Any change to this pattern must
	 * preserve that property — relaxing the class to include regex metacharacters (e.g. {@code .},
	 * {@code *}, {@code +}, parentheses) reopens the ReDoS surface this class was added to close.
	 */
	private static final Pattern FACET_MATCHES = Pattern.compile("^[\\w-]{1,64}$");

	/** Page size for a negative {@code limit}; the same 50 the endpoint declares as its default. */
	private static final int DEFAULT_LIMIT = 50;
	private static final int DEFAULT_FACET_LIMIT = 100;

	@Value("${entrystore.solr.search.query.max-length:1024}")
	private int maxQueryLength;

	@Value("${entrystore.solr.search.sort.max-length:1024}")
	private int maxSortLength;

	@Value("${entrystore.solr.search.filter-query.max-length:1024}")
	private int maxFilterQueryLength;

	@Value("${entrystore.solr.search.filter-query.max-count:16}")
	private int maxFilterQueryCount;

	@Value("${entrystore.solr.search.facet-fields.max-length:1024}")
	private int maxFacetFieldsLength;

	@Value("${entrystore.solr.search.facet-fields.max-count:16}")
	private int maxFacetFieldCount;

	@Value("${entrystore.solr.max-limit:100}")
	private int maxLimit;

	@Value("${entrystore.solr.facet-max-limit:1000}")
	private int maxFacetLimit;

	public void validateQuery(String query) {
		if (query != null && query.length() > maxQueryLength) {
			throw new BadRequestException(
					"Query parameter 'query' exceeds maximum length of " + maxQueryLength);
		}
	}

	public void validateSort(String sort) {
		if (sort == null || sort.isEmpty()) {
			return;
		}
		if (sort.length() > maxSortLength) {
			throw new BadRequestException(
					"Query parameter 'sort' exceeds maximum length of " + maxSortLength);
		}
		// split(-1) preserves trailing empty clauses (default split strips them) so
		// sort=modified+desc, and sort=,, are both rejected as malformed at the boundary.
		for (String clause : sort.split(",", -1)) {
			String trimmed = clause.trim();
			if (trimmed.isEmpty()) {
				throw new BadRequestException(
						"Query parameter 'sort' contains an empty clause");
			}
			String fieldName = trimmed.split("\\s+", 2)[0];
			requireAllowedField(fieldName, "sort");
		}
	}

	/**
	 * Clamps the requested page size to {@code entrystore.solr.max-limit}. A negative value falls back to the
	 * default page size; zero is allowed on purpose, since it lets a client ask for the result count alone.
	 */
	public int clampLimit(int limit) {
		if (limit > maxLimit) {
			return maxLimit;
		}
		if (limit < 0) {
			return DEFAULT_LIMIT;
		}
		return limit;
	}

	/**
	 * Splits the raw {@code filterQuery} parameter on commas and URL-decodes each part afterwards, so an
	 * unencoded comma separates filter queries while an encoded one stays inside a filter query. The raw
	 * value is length-capped before the split and the part count is capped before anything is decoded.
	 */
	public List<String> parseFilterQueries(String rawFilterQuery) {
		if (rawFilterQuery == null || rawFilterQuery.isEmpty()) {
			return List.of();
		}
		if (rawFilterQuery.length() > maxFilterQueryLength) {
			throw new BadRequestException(
					"Query parameter 'filterQuery' exceeds maximum length of " + maxFilterQueryLength);
		}
		String[] parts = rawFilterQuery.split(",");
		if (parts.length > maxFilterQueryCount) {
			throw new BadRequestException(
					"Query parameter 'filterQuery' contains more than " + maxFilterQueryCount + " entries");
		}
		List<String> filterQueries = new ArrayList<>(parts.length);
		for (String part : parts) {
			try {
				filterQueries.add(URLDecoder.decode(part, StandardCharsets.UTF_8));
			} catch (IllegalArgumentException e) {
				throw new BadRequestException(
						"Query parameter 'filterQuery' contains a malformed percent-encoding", e);
			}
		}
		return filterQueries;
	}

	/**
	 * Validates the facet request and converts it, bounding the requested facet limit by
	 * {@code entrystore.solr.facet-max-limit}.
	 */
	public SolrSearchIndex.FacetSettings toFacetSettings(FacetSettingsRequestParams request) {
		validateFacetSettings(request);
		return request.toSolrFacetSettings(maxFacetLimit, DEFAULT_FACET_LIMIT);
	}

	public void validateFacetSettings(FacetSettingsRequestParams request) {
		String facetFields = request.getFacetFields();
		String facetMatches = request.getFacetMatches();
		validateFacetFields(facetFields);
		validateFacetMatches(facetMatches, facetFields);
	}

	private void validateFacetFields(String facetFields) {
		if (facetFields == null || facetFields.isEmpty()) {
			return;
		}
		if (facetFields.length() > maxFacetFieldsLength) {
			throw new BadRequestException(
					"Query parameter 'facetFields' exceeds maximum length of " + maxFacetFieldsLength);
		}
		String[] fields = facetFields.split(",", -1);
		if (fields.length > maxFacetFieldCount) {
			throw new BadRequestException(
					"Query parameter 'facetFields' contains more than " + maxFacetFieldCount + " entries");
		}
		for (String field : fields) {
			requireAllowedField(field.trim(), "facetFields");
		}
	}

	private static void validateFacetMatches(String matches, String facetFields) {
		if (matches == null || matches.isEmpty()) {
			// An empty value is equivalent to omitting the parameter — Solr treats it as a no-op.
			// Reject only when a regex shape is actually present.
			return;
		}
		if (facetFields == null || facetFields.isEmpty()) {
			throw new BadRequestException(
					"Query parameter 'facetMatches' requires 'facetFields' to be set");
		}
		if (!FACET_MATCHES.matcher(matches).matches()) {
			throw new BadRequestException(
					"Query parameter 'facetMatches' must match pattern " + FACET_MATCHES.pattern());
		}
	}

	private static void requireAllowedField(String field, String parameterName) {
		if (field.isEmpty()) {
			throw new BadRequestException(
					"Query parameter '" + parameterName + "' contains an empty field name");
		}
		if (isAllowedField(field)) {
			return;
		}
		throw new BadRequestException(
				"Field '" + field + "' is not permitted in '" + parameterName + "'");
	}

	private static boolean isAllowedField(String field) {
		if (ALLOWED_NAMED_FIELDS.contains(field)) {
			return true;
		}
		return ALLOWED_DYNAMIC_PREFIXES.stream().anyMatch(prefix ->
				field.startsWith(prefix)
						&& DYNAMIC_TAIL.matcher(field.substring(prefix.length())).matches());
	}
}
