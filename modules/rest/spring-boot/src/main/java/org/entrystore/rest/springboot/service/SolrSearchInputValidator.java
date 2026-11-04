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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.regex.MatchResult;
import java.util.regex.Matcher;
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
 * {@code facetFields} also refuses the catch-all text fields in {@link #UNFACETABLE_FIELDS}.
 *
 * <p>{@code query} and {@code filterQuery} may carry local params, since entrystore.js sends same-core joins, but
 * neither {@code fromIndex}, which joins against another core on the same Solr node that may hold another
 * instance's index, nor, in a value that carries local params or a function query, parameter dereferencing
 * ({@code $name}), which makes Solr parse another parameter's text. Elsewhere Solr reads {@code $} as part of a
 * term, so a search such as {@code title:US$ AND title:rates} stays allowed. {@code facetMatches}, the only other
 * free-text parameter, is checked for {@code fromIndex} too, and {@code SolrSearchIndex} switches macro expansion
 * off, so no {@code ${…}} macro can assemble the key in Solr.
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
	 * Tokenized catch-all fields without docValues. Faceting on them makes Solr uninvert every token of every
	 * document onto its heap after each commit, and their buckets are word fragments no client uses.
	 */
	private static final Set<String> UNFACETABLE_FIELDS = Set.of("all", "fulltext", "metadata.object.literal");

	/**
	 * Literal text for {@code facetMatches}, which Solr evaluates as a full-match regular expression: any character
	 * but a regex metacharacter or a control character, or a metacharacter escaped with a backslash, so
	 * {@code Dr\. Smith} matches "Dr. Smith" as it did in 5.x. Letters of every script pass. Admitting an unescaped
	 * metacharacter (quantifiers, groups, alternation, classes) reopens the ReDoS surface. Control characters are
	 * refused because U+001F separates label and language in the companion terms that {@code facetLang} matches.
	 */
	private static final Pattern FACET_MATCHES =
			Pattern.compile("^(?:[^\\\\^$.|?*+()\\[\\]{}\\p{Cc}]|\\\\[\\\\^$.|?*+()\\[\\]{}])+$");

	/** Checked before {@link #FACET_MATCHES}, whose repeated group recurses once per character. */
	private static final int MAX_FACET_MATCHES_LENGTH = 256;

	/**
	 * Constrains {@code facetLang} to the BCP 47 alphabet (alphanumerics and dash, at most 35 characters, the
	 * length limit of a well-formed tag). The value is embedded verbatim in the {@code facet.matches} regex that
	 * Solr evaluates on the language companion field. Any change to this pattern must keep it literal-only, like
	 * {@link #FACET_MATCHES}: admitting regex metacharacters (e.g. a {@code *-CH} wildcard) reopens the ReDoS and
	 * regex-injection surface.
	 */
	private static final Pattern FACET_LANG = Pattern.compile("^[A-Za-z0-9-]{1,35}$");

	/**
	 * One level of backslash escaping: a backslash, {@code u} and the next four characters (group 1), or any other
	 * escaped character (group 2).
	 */
	private static final Pattern ESCAPE = Pattern.compile("(?s)\\\\(?:u(.{4})|(.))");

	/**
	 * Parameter dereferencing ({@code v=$name}, {@code query($name)}, {@code $'name'}) in Solr query text. Solr skips
	 * any {@link Character#isWhitespace} character after the {@code $}, which {@code \p{javaWhitespace}} matches and
	 * {@code \s} does not.
	 */
	private static final Pattern DEREFERENCE =
			Pattern.compile("\\$\\p{javaWhitespace}*[\\p{javaJavaIdentifierStart}'\"]");

	/**
	 * Syntax through which Solr reads {@code $name} as a parameter: local params ({@code {!query v=$name}},
	 * {@code {!func}query($name)}) and the function-query field {@code _val_}. Outside them the lucene parser, which
	 * reads {@code q} and {@code fq} as no {@code defType} is set, takes {@code $} as part of a term.
	 */
	private static final Pattern DEREFERENCING_SYNTAX = Pattern.compile("\\{!|_val_");

	/**
	 * Escape levels {@link #escapeLevels} decodes. Solr decodes one level per nested query, and no client nests that
	 * deep; the cap bounds the work for a chain of backslashes written as unicode escapes, which sheds only one level
	 * per pass however long the query is.
	 */
	private static final int MAX_ESCAPE_LEVELS = 16;

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
		requireSafeQueryText(query, "query");
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
			requireSafeQueryText(fq, "filterQuery");
		}
	}

	/**
	 * Refuses {@code fromIndex} anywhere, and parameter dereferencing in a value with {@link #DEREFERENCING_SYNTAX} at
	 * any escape level, however either is escaped: Solr unescapes a nested query ({@code _query_:"…"},
	 * {@code {!query v='…'}}) once per level before reading its local params.
	 */
	private static void requireSafeQueryText(String value, String parameterName) {
		List<String> levels = escapeLevels(value, parameterName);
		boolean canDereference = levels.stream().anyMatch(level -> DEREFERENCING_SYNTAX.matcher(level).find());
		for (String level : levels) {
			requireNoFromIndex(level, parameterName);
			if (canDereference && DEREFERENCE.matcher(level).find()) {
				throw new BadRequestException(
						"Parameter dereferencing ($name) is not permitted in '" + parameterName + "'");
			}
		}
	}

	/** Solr reads local-param keys case-sensitively, so only the exact key is refused, wherever it occurs. */
	private static void requireNoFromIndex(String text, String parameterName) {
		if (text.contains("fromIndex")) {
			throw new BadRequestException("Local parameter 'fromIndex' is not permitted in '" + parameterName + "'");
		}
	}

	/**
	 * The value and each further unescaped form of it, until unescaping changes nothing.
	 *
	 * @throws BadRequestException if the value nests escapes more than {@link #MAX_ESCAPE_LEVELS} levels deep
	 */
	private static List<String> escapeLevels(String value, String parameterName) {
		if (value == null) {
			return List.of();
		}
		List<String> levels = new ArrayList<>(List.of(value));
		for (int level = 0; level <= MAX_ESCAPE_LEVELS; level++) {
			String next = unescapeOnce(levels.getLast());
			if (next.equals(levels.getLast())) {
				return levels;
			}
			levels.add(next);
		}
		throw new BadRequestException("Query parameter '" + parameterName + "' nests escapes more than "
				+ MAX_ESCAPE_LEVELS + " levels deep");
	}

	/**
	 * Removes one level of backslash escaping. A unicode escape decodes as Solr's quoted local-param values decode it,
	 * with {@link Integer#parseInt(String, int)}, which also reads a sign and non-ASCII digits; four characters it
	 * cannot read stay literal, as Solr fails on them. Any other escaped character stands for itself, also the
	 * {@code n} or {@code t} that Solr turns into a control character, which only makes the checks refuse more.
	 */
	private static String unescapeOnce(String value) {
		if (value.indexOf('\\') < 0) {
			return value;
		}
		return ESCAPE.matcher(value).replaceAll(escape -> Matcher.quoteReplacement(unescape(escape)));
	}

	private static String unescape(MatchResult escape) {
		String unicode = escape.group(1);
		if (unicode == null) {
			return escape.group(2);
		}
		try {
			return String.valueOf((char) Integer.parseInt(unicode, 16));
		} catch (NumberFormatException notASolrEscape) {
			return "u" + unicode;
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
				if (UNFACETABLE_FIELDS.contains(clientField)) {
					throw new BadRequestException("Field '" + clientField + "' is not permitted in 'facetFields'");
				}
			}
		}
	}

	/** Without facetFields, facetMatches never reaches Solr, so it is ignored. */
	private static void validateFacetMatches(String matches, String facetFields) {
		if (matches == null || matches.isEmpty() || facetFields == null || facetFields.isEmpty()) {
			return;
		}
		if (matches.length() > MAX_FACET_MATCHES_LENGTH || !FACET_MATCHES.matcher(matches).matches()) {
			throw new BadRequestException("Query parameter 'facetMatches' must be literal text of at most "
					+ MAX_FACET_MATCHES_LENGTH + " characters; escape any of \\^$.|?*+()[]{} with a backslash");
		}
		// a query can make Solr parse this parameter as query text, see DEREFERENCE
		for (String level : escapeLevels(matches, "facetMatches")) {
			requireNoFromIndex(level, "facetMatches");
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
