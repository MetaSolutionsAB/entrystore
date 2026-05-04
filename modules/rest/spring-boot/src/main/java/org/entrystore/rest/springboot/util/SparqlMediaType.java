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

import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import org.entrystore.rest.springboot.model.exception.BadRequestException;
import org.entrystore.rest.springboot.model.exception.CustomResponseException;
import org.entrystore.rest.springboot.model.exception.InternalServerErrorException;
import org.springframework.http.HttpStatus;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * SPARQL tuple-result MIME types and content negotiation. Kept separate from
 * {@link GraphUtil} (which handles RDF graph MIME types) but reuses its
 * quality/specificity comparator.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class SparqlMediaType {

	public static final String SPARQL_RESULTS_JSON = "application/sparql-results+json";
	public static final String SPARQL_RESULTS_XML = "application/sparql-results+xml";
	public static final String CSV = "text/csv";
	public static final String BINARY = "application/x-binary-rdf-results-table";

	private static final int ECHOED_VALUE_MAX_LENGTH = 64;

	private static final Map<String, MediaType> STRING_TO_MEDIA_TYPE = Map.of(
			SPARQL_RESULTS_JSON, MediaType.parseMediaType(SPARQL_RESULTS_JSON),
			SPARQL_RESULTS_XML, MediaType.parseMediaType(SPARQL_RESULTS_XML),
			CSV, MediaType.parseMediaType(CSV),
			BINARY, MediaType.parseMediaType(BINARY)
	);

	private static final Map<String, String> ALIAS_TO_SPARQL_TYPE = Map.of(
			SPARQL_RESULTS_JSON, SPARQL_RESULTS_JSON,
			SPARQL_RESULTS_XML, SPARQL_RESULTS_XML,
			CSV, CSV,
			BINARY, BINARY,
			MediaType.APPLICATION_JSON_VALUE, SPARQL_RESULTS_JSON,
			MediaType.APPLICATION_XML_VALUE, SPARQL_RESULTS_XML
	);

	// BINARY first so application/* keeps preferring the most efficient SPARQL result format.
	private static final List<String> PARTIAL_WILDCARD_PREFERENCE =
			List.of(BINARY, SPARQL_RESULTS_JSON, SPARQL_RESULTS_XML, CSV);

	public static MediaType toMediaType(String mediaType) {
		MediaType resolved = STRING_TO_MEDIA_TYPE.get(mediaType);
		if (resolved == null) {
			throw new InternalServerErrorException("Unable to resolve SPARQL media type",
					new IllegalStateException("Unknown SPARQL media type: " + mediaType));
		}
		return resolved;
	}

	public static String resolve(String formatParam, String acceptHeader) {
		String fromFormat = fromFormatParam(formatParam);
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
	public static String fromOutputForm(String output) {
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

	private static String fromFormatParam(String formatParam) {
		String normalized = HttpUtil.determineMediaType(formatParam, null);
		if (normalized == null || normalized.isBlank()) {
			return null;
		}
		String mapped = ALIAS_TO_SPARQL_TYPE.get(normalized.toLowerCase(Locale.ROOT));
		if (mapped == null) {
			throw new BadRequestException("Unsupported SPARQL format: " + truncate(formatParam));
		}
		return mapped;
	}

	private static String fromAcceptHeader(String acceptHeader) {
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
				for (String supported : PARTIAL_WILDCARD_PREFERENCE) {
					if (supported.startsWith(topLevel)) {
						return supported;
					}
				}
				continue;
			}
			String typeStr = (type.getType() + "/" + type.getSubtype()).toLowerCase(Locale.ROOT);
			String mapped = ALIAS_TO_SPARQL_TYPE.get(typeStr);
			if (mapped != null) {
				return mapped;
			}
		}
		throw new CustomResponseException(
				"None of the requested Accept types are supported by the SPARQL endpoint: " + truncate(acceptHeader),
				HttpStatus.NOT_ACCEPTABLE);
	}

	private static String truncate(String value) {
		String visible = value.replaceAll("[\\p{Cntrl}]", "?");
		return visible.length() <= ECHOED_VALUE_MAX_LENGTH
				? visible
				: visible.substring(0, ECHOED_VALUE_MAX_LENGTH) + "…";
	}
}
