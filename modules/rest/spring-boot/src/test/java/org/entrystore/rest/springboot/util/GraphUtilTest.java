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

import org.eclipse.rdf4j.model.Model;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.LinkedHashModel;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.rio.RDFHandlerException;
import org.eclipse.rdf4j.rio.jsonld.JSONLDWriter;
import org.eclipse.rdf4j.rio.turtle.TurtleWriter;
import org.entrystore.rest.springboot.model.exception.CustomResponseException;
import org.entrystore.rest.springboot.model.exception.InternalServerErrorException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

import java.io.IOException;
import java.io.OutputStream;
import java.io.StringWriter;
import java.io.Writer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

	@Test
	void resolveRdfMediaType_formatParameterWinsOverAcceptHeader() {
		assertEquals("text/turtle",
				GraphUtil.resolveRdfMediaType(MediaType.parseMediaType("text/turtle"), "application/json"));
	}

	@Test
	void resolveRdfMediaType_unsupportedFormat_rejectedWith406() {
		CustomResponseException exception = assertThrows(
				CustomResponseException.class,
				() -> GraphUtil.resolveRdfMediaType(MediaType.TEXT_HTML, "application/json"));
		assertEquals(HttpStatus.NOT_ACCEPTABLE, exception.getStatus());
	}

	@Test
	void resolveRdfMediaType_nullFormat_negotiatesAcceptHeader() {
		assertEquals("application/json",
				GraphUtil.resolveRdfMediaType(null, "text/html, application/json"));
	}

	@Test
	void resolveRdfMediaType_formatWithParameters_rejectedWith406() {
		// Pins current behavior: the format parameter is matched with its parameters intact, so a
		// supported base type with a charset parameter is rejected — unlike the Accept-header path,
		// which strips parameters before the lookup.
		CustomResponseException exception = assertThrows(
				CustomResponseException.class,
				() -> GraphUtil.resolveRdfMediaType(MediaType.parseMediaType("text/turtle;charset=UTF-8"), null));
		assertEquals(HttpStatus.NOT_ACCEPTABLE, exception.getStatus());
	}

	@Test
	void serializeGraph_classOverloadAndWriterOverload_produceSameTurtle() {
		Model graph = sampleGraph();

		String viaClassOverload = GraphUtil.serializeGraph(graph, TurtleWriter.class);
		StringWriter stringWriter = new StringWriter();
		GraphUtil.serializeGraph(graph, new TurtleWriter(stringWriter));

		assertEquals(viaClassOverload, stringWriter.toString());
	}

	@Test
	void serializeGraph_jsonld_limitsNamespacesToUsedOnes() {
		String jsonld = GraphUtil.serializeGraph(sampleGraph(), JSONLDWriter.class);

		assertTrue(jsonld.contains("http://purl.org/dc/terms/"),
				"the namespace used by the graph must appear in the JSON-LD context");
		assertFalse(jsonld.contains("http://xmlns.com/foaf/0.1/"),
				"unused namespaces must not appear in the JSON-LD context");
	}

	// Both JSON media types must route to RDFJSON. application/json is the arm ResourceService relies on for
	// a non-list Graph resource, which used to call RDFJSON.graphToRdfJson directly.
	@ParameterizedTest(name = "{0}")
	@ValueSource(strings = {"application/json", "application/rdf+json"})
	void serializeGraph_stringDispatcher_jsonTypesMatchRdfJsonUtil(String mediaType) {
		Model graph = sampleGraph();
		assertEquals(RDFJSON.graphToRdfJson(graph), GraphUtil.serializeGraph(graph, mediaType));
	}

	@Test
	void serializeGraph_stringDispatcher_turtleMatchesClassOverload() {
		Model graph = sampleGraph();
		assertEquals(GraphUtil.serializeGraph(graph, TurtleWriter.class), GraphUtil.serializeGraph(graph, "text/turtle"));
	}

	@Test
	void serializeGraph_stringDispatcher_unknownMediaType_throwsIllegalArgument() {
		assertThrows(IllegalArgumentException.class, () -> GraphUtil.serializeGraph(sampleGraph(), "image/png"));
	}

	@Test
	void serializeGraph_stringDispatcher_nullGraph_throwsIllegalArgument() {
		assertThrows(IllegalArgumentException.class, () -> GraphUtil.serializeGraph(null, "text/turtle"));
	}

	// The writer throws after the namespace prefixes are already in the buffer, so a log-and-return would
	// serve that truncated document to the client with status 200.
	@Test
	void serializeGraph_writerFailsMidStream_throwsInsteadOfReturningTruncatedRdf() {
		assertThrows(InternalServerErrorException.class,
				() -> GraphUtil.serializeGraph(sampleGraph(), FailsMidStreamWriter.class));
	}

	// The realistic failure mode for the caller-supplied-writer overload: the sink breaks and RDF4J surfaces
	// the IOException as an RDFHandlerException.
	@Test
	void serializeGraph_writerOverload_brokenSink_throws() {
		TurtleWriter writerOverBrokenSink = new TurtleWriter(new Writer() {
			@Override
			public void write(char[] cbuf, int off, int len) throws IOException {
				throw new IOException("simulated sink failure");
			}

			@Override
			public void flush() throws IOException {
				throw new IOException("simulated sink failure");
			}

			@Override
			public void close() {
				// nothing to release
			}
		});

		assertThrows(InternalServerErrorException.class,
				() -> GraphUtil.serializeGraph(sampleGraph(), writerOverBrokenSink));
	}

	@Test
	void serializeGraph_writerWithoutWriterConstructor_throwsInsteadOfReturningNull() {
		assertThrows(InternalServerErrorException.class,
				() -> GraphUtil.serializeGraph(sampleGraph(), NoWriterConstructorWriter.class));
	}

	// Pins the contract against a real writer on realistic data rather than a synthetic one: RDFXMLPrettyWriter
	// cannot namespace-qualify <urn:987> and fails with StringIndexOutOfBoundsException, which is not an
	// RDFHandlerException. Such a triple is storable through a valid Turtle PUT.
	@Test
	void serializeGraph_rdfXmlWithUnqualifiablePredicate_throwsInternalServerError() {
		ValueFactory vf = SimpleValueFactory.getInstance();
		Model graph = new LinkedHashModel();
		graph.add(vf.createIRI("http://example.com/s"), vf.createIRI("urn:987"), vf.createLiteral("x"));

		assertThrows(InternalServerErrorException.class,
				() -> GraphUtil.serializeGraph(graph, GraphUtil.DEFAULT_RDF_MEDIA_TYPE));
	}

	/**
	 * Fails on the first statement, after {@code handleNamespace} has written the Turtle prefix declarations
	 * into the buffer.
	 */
	public static class FailsMidStreamWriter extends TurtleWriter {

		public FailsMidStreamWriter(Writer writer) {
			super(writer);
		}

		@Override
		public void handleStatement(Statement st) {
			throw new RDFHandlerException("simulated mid-stream writer failure");
		}
	}

	/** Reflective instantiation fails: {@code GraphUtil} looks up a {@code (Writer)} constructor. */
	public static class NoWriterConstructorWriter extends TurtleWriter {

		public NoWriterConstructorWriter(OutputStream outputStream) {
			super(outputStream);
		}
	}

	private static Model sampleGraph() {
		ValueFactory vf = SimpleValueFactory.getInstance();
		Model model = new LinkedHashModel();
		model.add(vf.createIRI("http://example.com/s"), vf.createIRI("http://purl.org/dc/terms/title"),
				vf.createLiteral("Sample"));
		return model;
	}
}
