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

import org.entrystore.rest.springboot.model.exception.CustomResponseException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GraphUtilTest {

	@ParameterizedTest
	@ValueSource(strings = {
			"application/rdf+xml",
			"application/json",
			"application/rdf+json",
			"text/n3",
			"text/turtle",
			"application/trix",
			"application/n-triples",
			"application/trig",
			"application/ld+json"
	})
	void validateRdfMediaType_shouldAcceptAllowedTypes(String mediaType) {
		assertEquals(mediaType, GraphUtil.validateRdfMediaType(mediaType));
	}

	@Test
	void validateRdfMediaType_shouldNormalizeCaseAndReturnLowercase() {
		assertEquals("application/json", GraphUtil.validateRdfMediaType("APPLICATION/JSON"));
		assertEquals("text/turtle", GraphUtil.validateRdfMediaType("Text/Turtle"));
	}

	@ParameterizedTest
	@ValueSource(strings = {
			"text/html",
			"text/xml",
			"application/javascript",
			"image/png",
			"text/plain",
			""
	})
	void validateRdfMediaType_shouldRejectDisallowedTypes(String mediaType) {
		CustomResponseException exception = assertThrows(
				CustomResponseException.class,
				() -> GraphUtil.validateRdfMediaType(mediaType));
		assertEquals(HttpStatus.NOT_ACCEPTABLE, exception.getStatus());
	}

	@Test
	void validateRdfMediaType_shouldRejectNull() {
		CustomResponseException exception = assertThrows(
				CustomResponseException.class,
				() -> GraphUtil.validateRdfMediaType(null));
		assertEquals(HttpStatus.NOT_ACCEPTABLE, exception.getStatus());
	}

	@Test
	void validateRdfMediaType_shouldUseCustomRejectStatus() {
		CustomResponseException exception = assertThrows(
				CustomResponseException.class,
				() -> GraphUtil.validateRdfMediaType("text/html", HttpStatus.UNSUPPORTED_MEDIA_TYPE));
		assertEquals(HttpStatus.UNSUPPORTED_MEDIA_TYPE, exception.getStatus());
	}

	@Test
	void validateRdfMediaType_shouldUseCustomRejectStatusForNull() {
		CustomResponseException exception = assertThrows(
				CustomResponseException.class,
				() -> GraphUtil.validateRdfMediaType(null, HttpStatus.UNSUPPORTED_MEDIA_TYPE));
		assertEquals(HttpStatus.UNSUPPORTED_MEDIA_TYPE, exception.getStatus());
	}

	@Test
	void validateRdfMediaType_shouldAcceptAllowedTypesWithCustomStatus() {
		assertEquals("text/turtle",
				GraphUtil.validateRdfMediaType("text/turtle", HttpStatus.UNSUPPORTED_MEDIA_TYPE));
	}

	@Test
	void resolveAcceptedMediaType_shouldReturnDefaultForWildcard() {
		assertEquals("application/rdf+xml",
				GraphUtil.resolveAcceptedMediaType("*/*", "application/rdf+xml"));
	}

	@Test
	void resolveAcceptedMediaType_shouldReturnDefaultForNull() {
		assertEquals("application/rdf+xml",
				GraphUtil.resolveAcceptedMediaType(null, "application/rdf+xml"));
	}

	@Test
	void resolveAcceptedMediaType_shouldReturnDefaultForBlank() {
		assertEquals("application/rdf+xml",
				GraphUtil.resolveAcceptedMediaType("", "application/rdf+xml"));
	}

	@Test
	void resolveAcceptedMediaType_shouldReturnFirstSupportedType() {
		assertEquals("application/json",
				GraphUtil.resolveAcceptedMediaType("text/html, application/json", "application/rdf+xml"));
	}

	@Test
	void resolveAcceptedMediaType_shouldReturnDefaultWhenWildcardInList() {
		assertEquals("application/rdf+xml",
				GraphUtil.resolveAcceptedMediaType("text/html, */*", "application/rdf+xml"));
	}

	@Test
	void resolveAcceptedMediaType_shouldReturnDefaultForWildcardSubtype() {
		assertEquals("application/rdf+xml",
				GraphUtil.resolveAcceptedMediaType("application/*", "application/rdf+xml"));
	}

	@Test
	void resolveAcceptedMediaType_shouldRejectWhenNoSupportedType() {
		CustomResponseException exception = assertThrows(
				CustomResponseException.class,
				() -> GraphUtil.resolveAcceptedMediaType("text/html, text/xml", "application/rdf+xml"));
		assertEquals(HttpStatus.NOT_ACCEPTABLE, exception.getStatus());
	}

	@Test
	void resolveAcceptedMediaType_shouldRejectMalformedAcceptHeader() {
		CustomResponseException exception = assertThrows(
				CustomResponseException.class,
				() -> GraphUtil.resolveAcceptedMediaType("not a valid header!!!", "application/rdf+xml"));
		assertEquals(HttpStatus.NOT_ACCEPTABLE, exception.getStatus());
	}

	@Test
	void resolveAcceptedMediaType_shouldHandleSingleSupportedType() {
		assertEquals("text/turtle",
				GraphUtil.resolveAcceptedMediaType("text/turtle", "application/rdf+xml"));
	}

	@Test
	void resolveAcceptedMediaType_shouldRespectQualityWeights() {
		assertEquals("text/turtle",
				GraphUtil.resolveAcceptedMediaType("application/json;q=0.5, text/turtle;q=1.0", "application/rdf+xml"));
	}

	@Test
	void validateRdfMediaType_shouldNormalizeLegacyN3Type() {
		assertEquals("text/n3", GraphUtil.validateRdfMediaType("text/rdf+n3"));
	}

	@Test
	void validateRdfMediaType_shouldNormalizeLegacyN3TypeCaseInsensitive() {
		assertEquals("text/n3", GraphUtil.validateRdfMediaType("TEXT/RDF+N3"));
	}

	@Test
	void resolveAcceptedMediaType_shouldNormalizeLegacyN3Type() {
		assertEquals("text/n3",
				GraphUtil.resolveAcceptedMediaType("text/rdf+n3", "application/rdf+xml"));
	}

	@Test
	void resolveAcceptedMediaType_shouldPreferSpecificTypeOverWildcardSubtype() {
		assertEquals("application/rdf+xml",
				GraphUtil.resolveAcceptedMediaType("text/*, application/rdf+xml", "text/turtle"));
	}

	@Test
	void resolveAcceptedMediaType_shouldPreferHigherQualityOverWildcard() {
		assertEquals("text/turtle",
				GraphUtil.resolveAcceptedMediaType("*/*;q=0.1, text/turtle;q=0.9", "application/rdf+xml"));
	}

	@Test
	void resolveAcceptedMediaType_shouldPreferQualityOverSpecificity() {
		assertEquals("text/turtle",
				GraphUtil.resolveAcceptedMediaType("application/rdf+xml;q=0.5, text/turtle;q=1.0", "application/rdf+xml"));
	}

	@Test
	void resolveAcceptedMediaType_shouldPreferSpecificTypeOverFullWildcard() {
		assertEquals("application/json",
				GraphUtil.resolveAcceptedMediaType("*/*, application/json", "application/rdf+xml"));
	}

	@Test
	void resolveAcceptedMediaType_shouldSortMultipleTypesByQuality() {
		assertEquals("application/rdf+xml",
				GraphUtil.resolveAcceptedMediaType(
						"text/turtle;q=0.8, application/rdf+xml;q=0.9, application/json;q=0.7",
						"text/turtle"));
	}

	@Test
	void resolveAcceptedMediaType_shouldPreferFirstTypeWhenQualityAndSpecificityAreEqual() {
		assertEquals("text/turtle",
				GraphUtil.resolveAcceptedMediaType(
						"text/turtle;q=0.8, application/rdf+xml;q=0.8", "application/json"));
	}

	@Test
	void normalizeLegacyMediaType_shouldMapLegacyN3() {
		assertEquals("text/n3", GraphUtil.normalizeLegacyMediaType("text/rdf+n3"));
	}

	@Test
	void normalizeLegacyMediaType_shouldNotChangeStandardN3() {
		assertEquals("text/n3", GraphUtil.normalizeLegacyMediaType("text/n3"));
	}

	@Test
	void normalizeLegacyMediaType_shouldNotChangeOtherTypes() {
		assertEquals("text/turtle", GraphUtil.normalizeLegacyMediaType("text/turtle"));
	}

	@Test
	void normalizeLegacyMediaType_shouldReturnNullForNullInput() {
		assertNull(GraphUtil.normalizeLegacyMediaType(null));
	}
}
