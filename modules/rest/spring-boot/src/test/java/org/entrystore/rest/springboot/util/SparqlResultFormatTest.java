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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SparqlResultFormatTest {

	@Test
	void resolve_formatParam_takesPrecedenceOverAcceptHeader() {
		SparqlResultFormat result = SparqlResultFormat.resolve("text/csv", "application/sparql-results+json");
		assertEquals(SparqlResultFormat.CSV, result);
	}

	@Test
	void resolve_unknownFormatParam_throwsBadRequest() {
		assertThrows(BadRequestException.class,
				() -> SparqlResultFormat.resolve("text/plain", "application/sparql-results+xml"));
	}

	@Test
	void resolve_blankFormatParam_fallsThroughToAcceptHeader() {
		SparqlResultFormat result = SparqlResultFormat.resolve("   ", "application/sparql-results+xml");
		assertEquals(SparqlResultFormat.SPARQL_RESULTS_XML, result);
	}

	@Test
	void resolve_nullFormat_nullAccept_returnsBinary() {
		assertEquals(SparqlResultFormat.BINARY, SparqlResultFormat.resolve(null, null));
	}

	@Test
	void resolve_blankAcceptHeader_returnsBinary() {
		assertEquals(SparqlResultFormat.BINARY, SparqlResultFormat.resolve(null, "   "));
	}

	@Test
	void resolve_acceptWildcard_returnsBinary() {
		assertEquals(SparqlResultFormat.BINARY, SparqlResultFormat.resolve(null, "*/*"));
	}

	@Test
	void resolve_acceptQualityValueOrdering_picksHighestQ() {
		SparqlResultFormat result = SparqlResultFormat.resolve(null,
				"text/csv;q=0.5, application/sparql-results+json;q=0.9");
		assertEquals(SparqlResultFormat.SPARQL_RESULTS_JSON, result);
	}

	@Test
	void resolve_acceptQZeroOnly_throwsNotAcceptable() {
		// RFC 7231 §5.3.1: q=0 means "explicitly unacceptable"; with no other entries the
		// loop must fall through to the 406 throw rather than matching CSV.
		CustomResponseException ex = assertThrows(CustomResponseException.class,
				() -> SparqlResultFormat.resolve(null, "text/csv;q=0"));
		assertEquals(HttpStatus.NOT_ACCEPTABLE, ex.getStatus());
	}

	@Test
	void resolve_acceptQZeroAndPositive_picksPositive() {
		// q=0 entry must be skipped even though it sorts first by specificity; the positive
		// entry later in the list wins.
		SparqlResultFormat result = SparqlResultFormat.resolve(null,
				"text/csv;q=0, application/sparql-results+json;q=0.5");
		assertEquals(SparqlResultFormat.SPARQL_RESULTS_JSON, result);
	}

	@Test
	void resolve_acceptQZeroWildcardAndPositive_picksPositive() {
		// q=0 on */* must not return BINARY just because it's the wildcard arm; the q-skip
		// must precede the wildcard branch so a positive entry later in the list still wins.
		SparqlResultFormat result = SparqlResultFormat.resolve(null,
				"*/*;q=0, text/csv;q=0.5");
		assertEquals(SparqlResultFormat.CSV, result);
	}

	@Test
	void resolve_acceptQZeroPartialWildcardOnly_throwsNotAcceptable() {
		// Pins that the q-skip also precedes the partial-wildcard (`application/*` /
		// `text/*`) branch. A regression that re-orders the q=0 check after isWildcardSubtype
		// would let `application/*;q=0` match BINARY (the first entry in PARTIAL_WILDCARD_PREFERENCE
		// whose top-level matches), violating RFC 7231 §5.3.1.
		CustomResponseException ex = assertThrows(CustomResponseException.class,
				() -> SparqlResultFormat.resolve(null, "application/*;q=0"));
		assertEquals(HttpStatus.NOT_ACCEPTABLE, ex.getStatus());
	}

	@Test
	void resolve_acceptLegacyAliasJson_returnsSparqlJson() {
		assertEquals(SparqlResultFormat.SPARQL_RESULTS_JSON,
				SparqlResultFormat.resolve(null, MediaType.APPLICATION_JSON_VALUE));
	}

	@Test
	void resolve_acceptLegacyAliasXml_returnsSparqlXml() {
		assertEquals(SparqlResultFormat.SPARQL_RESULTS_XML,
				SparqlResultFormat.resolve(null, MediaType.APPLICATION_XML_VALUE));
	}

	@ParameterizedTest(name = "{0}")
	@CsvSource(delimiter = '|', textBlock = """
			CSV alias              | application/sparql-results+csv              | CSV
			TSV                    | text/tab-separated-values                   | TSV
			CSV alias with charset | APPLICATION/SPARQL-RESULTS+CSV;charset=UTF-8 | CSV
			TSV with charset       | TEXT/TAB-SEPARATED-VALUES;charset=UTF-8      | TSV
			""")
	void resolve_acceptCsvAliasAndTsv_returnsExpectedFormat(
			String description, String acceptHeader, SparqlResultFormat expected) {
		assertEquals(expected, SparqlResultFormat.resolve(null, acceptHeader));
	}

	@ParameterizedTest(name = "{0}")
	@CsvSource(delimiter = '|', textBlock = """
			CSV alias              | application/sparql-results+csv | CSV
			CSV alias decoded plus | application/sparql-results csv | CSV
			TSV                    | text/tab-separated-values      | TSV
			TSV mixed case         | TEXT/TAB-SEPARATED-VALUES       | TSV
			""")
	void resolve_csvAliasAndTsvFormatParam_takesPrecedenceOverAcceptHeader(
			String description, String formatParam, SparqlResultFormat expected) {
		assertEquals(expected, SparqlResultFormat.resolve(formatParam, "application/sparql-results+json"));
	}

	@Test
	void resolve_acceptTsvWithHigherQualityThanCsvAlias_returnsTsv() {
		assertEquals(SparqlResultFormat.TSV, SparqlResultFormat.resolve(null,
				"application/sparql-results+csv;q=0.5, text/tab-separated-values;q=0.9"));
	}

	@Test
	void resolve_acceptCsvAliasWithHigherQualityThanTsv_returnsCsv() {
		assertEquals(SparqlResultFormat.CSV, SparqlResultFormat.resolve(null,
				"text/tab-separated-values;q=0.5, application/sparql-results+csv;q=0.9"));
	}

	@Test
	void resolve_acceptUnsupportedTypeOnly_throwsNotAcceptable() {
		CustomResponseException ex = assertThrows(CustomResponseException.class,
				() -> SparqlResultFormat.resolve(null, "text/html"));
		assertEquals(HttpStatus.NOT_ACCEPTABLE, ex.getStatus());
	}

	@Test
	void resolve_acceptMultipleUnsupportedTypes_throwsNotAcceptable() {
		assertThrows(CustomResponseException.class,
				() -> SparqlResultFormat.resolve(null, "text/html, application/rdf+xml"));
	}

	@Test
	void resolve_acceptUnsupportedPlusWildcard_returnsBinaryViaWildcard() {
		assertEquals(SparqlResultFormat.BINARY,
				SparqlResultFormat.resolve(null, "text/html, */*"));
	}

	@Test
	void resolve_acceptApplicationWildcard_returnsBinary() {
		// PARTIAL_WILDCARD_PREFERENCE puts BINARY first, and BINARY is application/x-binary-rdf-results-table
		// so application/* matches BINARY before JSON or XML.
		assertEquals(SparqlResultFormat.BINARY, SparqlResultFormat.resolve(null, "application/*"));
	}

	@Test
	void resolve_acceptTextWildcard_returnsCsv() {
		// Both CSV and TSV are text/*; CSV wins because it precedes TSV in PARTIAL_WILDCARD_PREFERENCE.
		assertEquals(SparqlResultFormat.CSV, SparqlResultFormat.resolve(null, "text/*"));
	}

	@Test
	void resolve_acceptUnsupportedTopLevelWildcardOnly_throwsNotAcceptable() {
		// image/* matches no PARTIAL_WILDCARD_PREFERENCE entry, so the loop continues past the
		// wildcard branch and falls through to the 406 path.
		CustomResponseException ex = assertThrows(CustomResponseException.class,
				() -> SparqlResultFormat.resolve(null, "image/*"));
		assertEquals(HttpStatus.NOT_ACCEPTABLE, ex.getStatus());
	}

	@Test
	void resolve_malformedAcceptHeader_throwsBadRequest() {
		assertThrows(BadRequestException.class,
				() -> SparqlResultFormat.resolve(null, "!@#$%"));
	}

	@Test
	void resolve_formatWithSpaceInsteadOfPlus_normalisedToCanonical() {
		SparqlResultFormat result = SparqlResultFormat.resolve("application/sparql-results json", null);
		assertEquals(SparqlResultFormat.SPARQL_RESULTS_JSON, result);
	}

	@Test
	void fromOutputForm_nullOutput_defaultsToSparqlJson() {
		assertEquals(SparqlResultFormat.SPARQL_RESULTS_JSON, SparqlResultFormat.fromOutputForm(null));
	}

	@ParameterizedTest(name = "{0} resolves to {1}")
	@CsvSource({"JSON, SPARQL_RESULTS_JSON", "Xml, SPARQL_RESULTS_XML", "' csv ', CSV", "' TsV ', TSV"})
	void fromOutputForm_mixedCase_normalisesToCanonical(String output, SparqlResultFormat expected) {
		assertEquals(expected, SparqlResultFormat.fromOutputForm(output));
	}

	@Test
	void fromOutputForm_unknownValue_throwsBadRequest() {
		BadRequestException ex = assertThrows(BadRequestException.class,
				() -> SparqlResultFormat.fromOutputForm("jsom"));
		assertEquals("Unsupported SPARQL output format: jsom (supported: json, xml, csv, tsv)", ex.getMessage());
	}

	@Test
	void fromOutputForm_emptyString_throwsBadRequest() {
		assertThrows(BadRequestException.class, () -> SparqlResultFormat.fromOutputForm(""));
	}

	@Test
	void fromOutputForm_overlyLongUnknownValue_truncatesEcho() {
		String longGarbage = "x".repeat(500);
		BadRequestException ex = assertThrows(BadRequestException.class,
				() -> SparqlResultFormat.fromOutputForm(longGarbage));
		assertTrue(ex.getMessage().endsWith("… (supported: json, xml, csv, tsv)"),
				"Expected truncation marker before the supported-values suffix, got: " + ex.getMessage());
		assertTrue(ex.getMessage().length() < 200,
				"Expected truncated message under 200 chars, got: " + ex.getMessage().length());
	}

	@Test
	void getMediaType_eachValue_matchesCanonical() {
		for (SparqlResultFormat format : SparqlResultFormat.values()) {
			assertEquals(MediaType.parseMediaType(format.getCanonical()), format.getMediaType(),
					"MediaType cache must match the canonical string for " + format);
		}
	}

	@Test
	void putUnique_aliasCollision_throws() throws Exception {
		// Pin the class-load defense: a future enum value declaring an alias that matches
		// another value's canonical or alias must fail at class-load instead of letting
		// HashMap.put silently last-one-wins by enum-iteration order. Reflection-invoked
		// against a synthetic map so the test does not need to restructure the enum to fail.
		java.lang.reflect.Method putUnique = SparqlResultFormat.class.getDeclaredMethod(
				"putUnique", java.util.Map.class, String.class, SparqlResultFormat.class);
		putUnique.setAccessible(true);

		java.util.Map<String, SparqlResultFormat> map = new java.util.HashMap<>();
		putUnique.invoke(null, map, "text/csv", SparqlResultFormat.CSV);

		java.lang.reflect.InvocationTargetException ite = assertThrows(
				java.lang.reflect.InvocationTargetException.class,
				() -> putUnique.invoke(null, map, "text/csv", SparqlResultFormat.BINARY));
		assertInstanceOf(IllegalStateException.class, ite.getCause(), "Expected IllegalStateException from putUnique on collision, got: " + ite.getCause());
		assertTrue(ite.getCause().getMessage().contains("alias collision"),
				"Expected 'alias collision' in message, got: " + ite.getCause().getMessage());
	}

	@Test
	void partialWildcardPreference_includesAllValues() throws Exception {
		// Mirrors the static-init guard that fires at class-load. Without this regression test
		// a future enum value forgotten from the preference list would silently be unreachable
		// for `application/*` / `text/*` Accept resolution and the 406 path would mask it.
		java.lang.reflect.Field field = SparqlResultFormat.class.getDeclaredField("PARTIAL_WILDCARD_PREFERENCE");
		field.setAccessible(true);
		@SuppressWarnings("unchecked")
		java.util.List<SparqlResultFormat> preference = (java.util.List<SparqlResultFormat>) field.get(null);
		assertEquals(java.util.EnumSet.allOf(SparqlResultFormat.class),
				java.util.EnumSet.copyOf(preference),
				"PARTIAL_WILDCARD_PREFERENCE must include every SparqlResultFormat value");
	}
}
