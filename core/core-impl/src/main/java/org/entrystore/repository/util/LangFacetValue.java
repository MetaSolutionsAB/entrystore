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

/**
 * A facet value of the {@code metadata.predicate.literal_l.*} Solr field family: the label of a literal together
 * with its language tag.
 *
 * <p>The indexer stores every literal as {@code label + U+001F + language} (empty language for untagged and typed
 * literals), so one Solr term exists per distinct (label, language) pair and faceting on the field yields one
 * bucket per pair. The separator is always present: a BCP 47 language tag can never contain it, so
 * {@link #decode(String)} splits at the last occurrence and recovers the label even when the label itself contains
 * the separator. The separator never reaches clients; the REST layer decodes each bucket before serialising it.
 *
 * @param label the lexical form of the literal
 * @param lang  the language tag as stored, or {@code null} when the literal has none
 */
public record LangFacetValue(String label, String lang) {

	public static final String FIELD_PREFIX = "metadata.predicate.literal_l.";

	private static final String RELATED_FIELD_PREFIX = "related." + FIELD_PREFIX;

	private static final char SEPARATOR = '\u001F';

	private static final String SEPARATOR_REGEX = "\\x1F";

	public LangFacetValue {
		if (lang != null && lang.isEmpty()) {
			lang = null;
		}
	}

	public static String encode(Literal literal) {
		return literal.getLabel() + SEPARATOR + literal.getLanguage().orElse("");
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
				&& (fieldName.startsWith(FIELD_PREFIX) || fieldName.startsWith(RELATED_FIELD_PREFIX));
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
