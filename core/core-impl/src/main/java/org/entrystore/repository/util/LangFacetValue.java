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

package org.entrystore.repository.util;

import org.eclipse.rdf4j.model.Literal;
import org.eclipse.rdf4j.model.util.Literals;

import java.util.IllformedLocaleException;
import java.util.Locale;

/**
 * One term of the internal {@code metadata.predicate.literal_l.*} Solr field family: the label of a literal together
 * with its normalised language tag. The family is the companion of {@code metadata.predicate.literal_s.*}: the REST
 * layer facets on it server-side to attach the languages a label occurs in and to filter labels by language. It is
 * never accepted from clients as a facet or sort field and never returned to them.
 *
 * <p>The indexer stores every literal whose label is at most {@link #MAX_LABEL_LENGTH} characters as
 * {@code label + U+001F + language} (empty language for untagged and typed literals), so one Solr term exists per
 * distinct (label, language) pair. The separator is always present and a BCP 47 language tag can never contain it,
 * so {@link #decode(String)} splits at the last occurrence and recovers a label that itself contains the separator.
 * Language tags are normalised with {@link Literals#normalizeLanguageTag(String)} at index time, so {@code en-gb}
 * and {@code en-GB} share one term.
 *
 * @param label the lexical form of the literal
 * @param lang  the normalised language tag, or {@code null} when the literal has none
 */
public record LangFacetValue(String label, String lang) {

	public static final String FIELD_PREFIX = "metadata.predicate.literal_l.";

	/** Labels longer than this get no companion term: they are descriptions, not facet values. */
	public static final int MAX_LABEL_LENGTH = 256;

	private static final String RELATED_PREFIX = "related.";

	private static final String LITERAL_S_PREFIX = "metadata.predicate.literal_s.";

	private static final char SEPARATOR = '\u001F';

	private static final String SEPARATOR_REGEX = "\\x1F";

	public LangFacetValue {
		if (lang != null && lang.isEmpty()) {
			lang = null;
		}
	}

	public static String encode(Literal literal) {
		return literal.getLabel() + SEPARATOR + literal.getLanguage().map(LangFacetValue::normalizeLanguageTag).orElse("");
	}

	public static boolean exceedsLabelCap(String label) {
		return label.length() > MAX_LABEL_LENGTH;
	}

	/**
	 * A term without a separator degrades to a label-only value, so the response path never throws on an
	 * unexpected term.
	 */
	public static LangFacetValue decode(String term) {
		int separatorIndex = term.lastIndexOf(SEPARATOR);
		if (separatorIndex < 0) {
			return new LangFacetValue(term, null);
		}
		return new LangFacetValue(term.substring(0, separatorIndex), term.substring(separatorIndex + 1));
	}

	public static boolean isLangFacetField(String fieldName) {
		return fieldName != null
				&& (fieldName.startsWith(FIELD_PREFIX) || fieldName.startsWith(RELATED_PREFIX + FIELD_PREFIX));
	}

	/**
	 * The internal companion of a client-visible literal facet field: {@code [related.]metadata.predicate.literal_s.<tail>}
	 * maps to {@code [related.]metadata.predicate.literal_l.<tail>}. Every other field, including the
	 * {@code metadata.predicate.literal.} shorthand before its rewrite, yields {@code null}, so callers can test for a
	 * literal facet and derive its companion in one step.
	 */
	public static String companionField(String facetField) {
		if (facetField == null) {
			return null;
		}
		boolean related = facetField.startsWith(RELATED_PREFIX);
		String base = related ? facetField.substring(RELATED_PREFIX.length()) : facetField;
		if (!base.startsWith(LITERAL_S_PREFIX) || base.length() == LITERAL_S_PREFIX.length()) {
			return null;
		}
		String companion = FIELD_PREFIX + base.substring(LITERAL_S_PREFIX.length());
		return related ? RELATED_PREFIX + companion : companion;
	}

	/**
	 * Canonical BCP 47 casing via RDF4J ({@code en-gb} becomes {@code en-GB}, {@code SV} becomes {@code sv}). An
	 * ill-formed tag is kept verbatim rather than dropping the literal from the companion field.
	 */
	public static String normalizeLanguageTag(String tag) {
		try {
			return Literals.normalizeLanguageTag(tag);
		} catch (IllformedLocaleException e) {
			return tag;
		}
	}

	/**
	 * RFC 4647 basic filtering, case-insensitive: {@code facetLang} matches a tag that equals it or that continues it
	 * with a subtag separator, so {@code en} matches {@code en} and {@code en-GB} but not {@code eng}.
	 */
	public static boolean matchesLanguage(String tag, String facetLang) {
		if (tag == null || facetLang == null) {
			return false;
		}
		String candidate = tag.toLowerCase(Locale.ROOT);
		String range = facetLang.toLowerCase(Locale.ROOT);
		return candidate.equals(range)
				|| (candidate.length() > range.length() && candidate.startsWith(range) && candidate.charAt(range.length()) == '-');
	}

	/**
	 * Anchors a client {@code facet.matches} pattern to the label part of an encoded term. Solr applies
	 * {@code facet.matches} as a full-string regex, so a pattern written against the visible label would otherwise
	 * never match a term that ends in the separator and language tag.
	 */
	public static String labelMatchesRegex(String labelRegex) {
		return "(?:" + labelRegex + ")" + SEPARATOR_REGEX + "[^" + SEPARATOR_REGEX + "]*";
	}
}
