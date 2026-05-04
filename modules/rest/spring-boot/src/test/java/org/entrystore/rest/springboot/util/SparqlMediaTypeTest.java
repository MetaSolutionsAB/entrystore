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

import org.entrystore.rest.springboot.model.exception.BadRequestException;
import org.entrystore.rest.springboot.model.exception.CustomResponseException;
import org.entrystore.rest.springboot.model.exception.InternalServerErrorException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SparqlMediaTypeTest {

	@Test
	void resolve_formatParam_takesPrecedenceOverAcceptHeader() {
		String result = SparqlMediaType.resolve("text/csv", "application/sparql-results+json");
		assertEquals(SparqlMediaType.CSV, result);
	}

	@Test
	void resolve_unknownFormatParam_throwsBadRequest() {
		assertThrows(BadRequestException.class,
				() -> SparqlMediaType.resolve("text/plain", "application/sparql-results+xml"));
	}

	@Test
	void resolve_blankFormatParam_fallsThroughToAcceptHeader() {
		String result = SparqlMediaType.resolve("   ", "application/sparql-results+xml");
		assertEquals(SparqlMediaType.SPARQL_RESULTS_XML, result);
	}

	@Test
	void resolve_nullFormat_nullAccept_returnsBinary() {
		assertEquals(SparqlMediaType.BINARY, SparqlMediaType.resolve(null, null));
	}

	@Test
	void resolve_blankAcceptHeader_returnsBinary() {
		assertEquals(SparqlMediaType.BINARY, SparqlMediaType.resolve(null, "   "));
	}

	@Test
	void resolve_acceptWildcard_returnsBinary() {
		assertEquals(SparqlMediaType.BINARY, SparqlMediaType.resolve(null, "*/*"));
	}

	@Test
	void resolve_acceptQualityValueOrdering_picksHighestQ() {
		String result = SparqlMediaType.resolve(null,
				"text/csv;q=0.5, application/sparql-results+json;q=0.9");
		assertEquals(SparqlMediaType.SPARQL_RESULTS_JSON, result);
	}

	@Test
	void resolve_acceptLegacyAliasJson_returnsSparqlJson() {
		assertEquals(SparqlMediaType.SPARQL_RESULTS_JSON,
				SparqlMediaType.resolve(null, MediaType.APPLICATION_JSON_VALUE));
	}

	@Test
	void resolve_acceptLegacyAliasXml_returnsSparqlXml() {
		assertEquals(SparqlMediaType.SPARQL_RESULTS_XML,
				SparqlMediaType.resolve(null, MediaType.APPLICATION_XML_VALUE));
	}

	@Test
	void resolve_acceptUnsupportedTypeOnly_throwsNotAcceptable() {
		CustomResponseException ex = assertThrows(CustomResponseException.class,
				() -> SparqlMediaType.resolve(null, "text/html"));
		assertEquals(HttpStatus.NOT_ACCEPTABLE, ex.getStatus());
	}

	@Test
	void resolve_acceptMultipleUnsupportedTypes_throwsNotAcceptable() {
		assertThrows(CustomResponseException.class,
				() -> SparqlMediaType.resolve(null, "text/html, application/rdf+xml"));
	}

	@Test
	void resolve_acceptUnsupportedPlusWildcard_returnsBinaryViaWildcard() {
		assertEquals(SparqlMediaType.BINARY,
				SparqlMediaType.resolve(null, "text/html, */*"));
	}

	@Test
	void resolve_malformedAcceptHeader_throwsBadRequest() {
		assertThrows(BadRequestException.class,
				() -> SparqlMediaType.resolve(null, "!@#$%"));
	}

	@Test
	void resolve_formatWithSpaceInsteadOfPlus_normalisedToCanonical() {
		String result = SparqlMediaType.resolve("application/sparql-results json", null);
		assertEquals(SparqlMediaType.SPARQL_RESULTS_JSON, result);
	}

	@Test
	void fromOutputForm_nullOutput_defaultsToSparqlJson() {
		assertEquals(SparqlMediaType.SPARQL_RESULTS_JSON, SparqlMediaType.fromOutputForm(null));
	}

	@Test
	void fromOutputForm_mixedCase_normalisesToCanonical() {
		assertEquals(SparqlMediaType.SPARQL_RESULTS_JSON, SparqlMediaType.fromOutputForm("JSON"));
		assertEquals(SparqlMediaType.SPARQL_RESULTS_XML, SparqlMediaType.fromOutputForm("Xml"));
		assertEquals(SparqlMediaType.CSV, SparqlMediaType.fromOutputForm(" csv "));
	}

	@Test
	void fromOutputForm_unknownValue_throwsBadRequest() {
		BadRequestException ex = assertThrows(BadRequestException.class,
				() -> SparqlMediaType.fromOutputForm("jsom"));
		assertEquals("Unsupported SPARQL output format: jsom", ex.getMessage());
	}

	@Test
	void fromOutputForm_emptyString_throwsBadRequest() {
		assertThrows(BadRequestException.class, () -> SparqlMediaType.fromOutputForm(""));
	}

	@Test
	void fromOutputForm_overlyLongUnknownValue_truncatesEcho() {
		String longGarbage = "x".repeat(500);
		BadRequestException ex = assertThrows(BadRequestException.class,
				() -> SparqlMediaType.fromOutputForm(longGarbage));
		assertTrue(ex.getMessage().endsWith("…"),
				"Expected truncation marker, got: " + ex.getMessage());
		assertTrue(ex.getMessage().length() < 200,
				"Expected truncated message under 200 chars, got: " + ex.getMessage().length());
	}

	@Test
	void toMediaType_eachConstant_returnsParsedType() {
		assertEquals(MediaType.parseMediaType(SparqlMediaType.SPARQL_RESULTS_JSON),
				SparqlMediaType.toMediaType(SparqlMediaType.SPARQL_RESULTS_JSON));
		assertEquals(MediaType.parseMediaType(SparqlMediaType.SPARQL_RESULTS_XML),
				SparqlMediaType.toMediaType(SparqlMediaType.SPARQL_RESULTS_XML));
		assertEquals(MediaType.parseMediaType(SparqlMediaType.CSV),
				SparqlMediaType.toMediaType(SparqlMediaType.CSV));
		assertEquals(MediaType.parseMediaType(SparqlMediaType.BINARY),
				SparqlMediaType.toMediaType(SparqlMediaType.BINARY));
	}

	@Test
	void toMediaType_unknownString_throwsInternalServerError() {
		InternalServerErrorException ex = assertThrows(InternalServerErrorException.class,
				() -> SparqlMediaType.toMediaType("application/json"));
		assertEquals("Unable to resolve SPARQL media type", ex.getMessage());
	}
}
