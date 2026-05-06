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

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * SPARQL tuple-result format enum + content negotiation. Each value carries the canonical
 * MIME string, parsed Spring {@link MediaType}, and any legacy aliases that should resolve
 * to the value. Downstream switches (e.g. {@code SparqlService.createWriter}) over this
 * enum are exhaustive at compile time, so adding a fifth result type fails to compile until
 * every consumer has wired it up.
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

	private static final Map<String, SparqlResultFormat> ALIAS_TO_FORMAT = buildAliasMap();

	private static Map<String, SparqlResultFormat> buildAliasMap() {
		Map<String, SparqlResultFormat> map = new HashMap<>();
		for (SparqlResultFormat f : values()) {
			map.put(f.canonical, f);
			for (String alias : f.aliases) {
				map.put(alias, f);
			}
		}
		return Map.copyOf(map);
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
		String visible = value.replaceAll("\\p{Cntrl}", "?");
		return visible.length() <= ECHOED_VALUE_MAX_LENGTH
				? visible
				: visible.substring(0, ECHOED_VALUE_MAX_LENGTH) + "…";
	}
}
