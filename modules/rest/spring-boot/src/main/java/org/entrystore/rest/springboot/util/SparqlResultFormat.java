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

package org.entrystore.rest.springboot.util;

import lombok.Getter;
import org.entrystore.rest.springboot.model.exception.BadRequestException;
import org.entrystore.rest.springboot.model.exception.CustomResponseException;
import org.springframework.http.HttpStatus;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * SPARQL tuple-result format enum + content negotiation. Each value carries the canonical
 * MIME string, parsed Spring {@link MediaType}, and any legacy aliases that should resolve
 * to the value.
 *
 * <p>Switch expressions over this enum (e.g. {@code SparqlService.createWriter}) are
 * exhaustive at compile time, so adding a fifth result type fails to compile until every
 * such switch has wired it up. The static-init blocks below catch the remaining invariants
 * at class-load: {@code PARTIAL_WILDCARD_PREFERENCE} must list every value, and
 * {@code ALIAS_TO_FORMAT} must not have any alias collisions.</p>
 */
public enum SparqlResultFormat {

	BINARY("application/x-binary-rdf-results-table", List.of()),
	SPARQL_RESULTS_JSON("application/sparql-results+json", List.of(MediaType.APPLICATION_JSON_VALUE)),
	SPARQL_RESULTS_XML("application/sparql-results+xml", List.of(MediaType.APPLICATION_XML_VALUE)),
	CSV("text/csv", List.of());

	private static final int ECHOED_VALUE_MAX_LENGTH = 64;

	@Getter
	private final String canonical;

	@Getter
	private final MediaType mediaType;

	private final List<String> aliases;

	SparqlResultFormat(String canonical, List<String> aliases) {
		this.canonical = canonical;
		this.mediaType = MediaType.parseMediaType(canonical);
		this.aliases = aliases;
	}

	// BINARY first so application/* keeps preferring the most efficient SPARQL result format.
	private static final List<SparqlResultFormat> PARTIAL_WILDCARD_PREFERENCE =
			List.of(BINARY, SPARQL_RESULTS_JSON, SPARQL_RESULTS_XML, CSV);

	static {
		// Fail at class-load if a future enum value forgets to register itself in
		// PARTIAL_WILDCARD_PREFERENCE; otherwise the new value would silently be unreachable for
		// `application/*` / `text/*` Accept resolution with no test that would catch it.
		if (!EnumSet.copyOf(PARTIAL_WILDCARD_PREFERENCE).containsAll(EnumSet.allOf(SparqlResultFormat.class))) {
			throw new IllegalStateException(
					"PARTIAL_WILDCARD_PREFERENCE must include every SparqlResultFormat value");
		}
	}

	private static final Map<String, SparqlResultFormat> ALIAS_TO_FORMAT = buildAliasMap();

	private static Map<String, SparqlResultFormat> buildAliasMap() {
		Map<String, SparqlResultFormat> map = new HashMap<>();
		for (SparqlResultFormat f : values()) {
			putUnique(map, f.canonical, f);
			for (String alias : f.aliases) {
				putUnique(map, alias, f);
			}
		}
		return Map.copyOf(map);
	}

	private static void putUnique(Map<String, SparqlResultFormat> map, String key, SparqlResultFormat f) {
		// Fail at class-load if a future value declares an alias matching another value's canonical
		// or alias, instead of letting HashMap.put silently last-one-wins by enum-iteration order.
		SparqlResultFormat prior = map.putIfAbsent(key, f);
		if (prior != null && prior != f) {
			throw new IllegalStateException(
					"SparqlResultFormat alias collision: '" + key + "' maps to both " + prior + " and " + f);
		}
	}

	public static SparqlResultFormat resolve(String formatParam, String acceptHeader) {
		SparqlResultFormat fromFormat = fromFormatParam(formatParam);
		if (fromFormat != null) {
			return fromFormat;
		}
		return fromAcceptHeader(acceptHeader);
	}

	/**
	 * A missing {@code output} field defaults to {@link #SPARQL_RESULTS_JSON} (preserves
	 * legacy client compatibility); any unrecognised value triggers
	 * {@link BadRequestException}.
	 */
	public static SparqlResultFormat fromOutputForm(String output) {
		if (output == null) {
			return SPARQL_RESULTS_JSON;
		}
		return switch (output.trim().toLowerCase(Locale.ROOT)) {
			case "json" -> SPARQL_RESULTS_JSON;
			case "xml" -> SPARQL_RESULTS_XML;
			case "csv" -> CSV;
			default -> throw new BadRequestException("Unsupported SPARQL output format: " + truncate(output));
		};
	}

	private static SparqlResultFormat fromFormatParam(String formatParam) {
		String normalized = HttpUtil.determineMediaType(formatParam, null);
		if (normalized == null || normalized.isBlank()) {
			return null;
		}
		SparqlResultFormat mapped = ALIAS_TO_FORMAT.get(normalized.toLowerCase(Locale.ROOT));
		if (mapped == null) {
			throw new BadRequestException("Unsupported SPARQL format: " + truncate(formatParam));
		}
		return mapped;
	}

	private static SparqlResultFormat fromAcceptHeader(String acceptHeader) {
		if (acceptHeader == null || acceptHeader.isBlank()) {
			return BINARY;
		}
		List<MediaType> acceptTypes;
		try {
			acceptTypes = MediaType.parseMediaTypes(acceptHeader);
		} catch (InvalidMediaTypeException e) {
			throw new BadRequestException("Invalid Accept header: " + truncate(acceptHeader), e);
		}
		acceptTypes.sort(GraphUtil.QUALITY_THEN_SPECIFICITY);
		for (MediaType type : acceptTypes) {
			// RFC 7231 §5.3.1: q=0 means "explicitly unacceptable"; skip without matching.
			// Must precede the wildcard branch so a `*/*;q=0` entry does not return BINARY.
			if (type.getQualityValue() <= 0.0) {
				continue;
			}
			if (type.isWildcardType()) {
				return BINARY;
			}
			if (type.isWildcardSubtype()) {
				String topLevel = type.getType().toLowerCase(Locale.ROOT) + "/";
				for (SparqlResultFormat f : PARTIAL_WILDCARD_PREFERENCE) {
					if (f.canonical.startsWith(topLevel)) {
						return f;
					}
				}
				continue;
			}
			String typeStr = (type.getType() + "/" + type.getSubtype()).toLowerCase(Locale.ROOT);
			SparqlResultFormat mapped = ALIAS_TO_FORMAT.get(typeStr);
			if (mapped != null) {
				return mapped;
			}
		}
		throw new CustomResponseException(
				"None of the requested Accept types are supported by the SPARQL endpoint: " + truncate(acceptHeader),
				HttpStatus.NOT_ACCEPTABLE);
	}

	private static String truncate(String value) {
		// Same hardening as SparqlService.truncate: strip ASCII C0/C1 controls AND U+2028/U+2029
		// line/paragraph separators so an attacker-supplied format/Accept value cannot forge log
		// lines via the BadRequestException message that AppExceptionHandler echoes (CWE-117).
		String visible = value.replaceAll("[\\p{Cntrl}\\u2028\\u2029]", "?");
		return visible.length() <= ECHOED_VALUE_MAX_LENGTH
				? visible
				: visible.substring(0, ECHOED_VALUE_MAX_LENGTH) + "…";
	}
}
