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

import org.eclipse.rdf4j.rio.RDFFormat;
import org.entrystore.rest.springboot.model.exception.BadRequestException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ValidatorServiceTest {

	private static final String TURTLE = RDFFormat.TURTLE.getDefaultMIMEType();
	private static final String NTRIPLES = RDFFormat.NTRIPLES.getDefaultMIMEType();

	private final ValidatorService service = new ValidatorService();

	@Test
	void validate_validTurtle_doesNotThrow() {
		String turtle = """
				@prefix dc: <http://purl.org/dc/terms/> .
				<http://example.org/a> dc:title "Title" .
				""";
		assertDoesNotThrow(() -> service.validate(turtle, TURTLE));
	}

	@Test
	void validate_validTurtleWithLanguageLiterals_doesNotThrow() {
		String turtle = """
				@prefix dc: <http://purl.org/dc/terms/> .
				<http://example.org/a> dc:title "Title"@en, "Titel"@de .
				""";
		assertDoesNotThrow(() -> service.validate(turtle, TURTLE));
	}

	@Test
	void validate_validNTriples_doesNotThrow() {
		String ntriples = "<http://example.org/a> <http://purl.org/dc/terms/title> \"Title\" .\n";
		assertDoesNotThrow(() -> service.validate(ntriples, NTRIPLES));
	}

	@Test
	void validate_validHttpPrefixedStringLiteral_doesNotThrow() {
		String ntriples = "<http://example.org/a> <http://example.org/p> \"http://valid.example.org/path\" .\n";
		assertDoesNotThrow(() -> service.validate(ntriples, NTRIPLES));
	}

	@Test
	void validate_malformedTurtle_throwsBadRequest() {
		String malformed = "this is definitely not turtle <<<>>>";
		BadRequestException ex = assertThrows(BadRequestException.class,
				() -> service.validate(malformed, TURTLE));
		assertTrue(ex.getMessage().contains("Malformed RDF"),
				"Expected message to mention malformed RDF, but was: " + ex.getMessage());
	}

	@Test
	void validate_unsupportedMediaType_throwsBadRequest() {
		BadRequestException ex = assertThrows(BadRequestException.class,
				() -> service.validate("anything", "text/plain"));
		assertTrue(ex.getMessage().contains("text/plain"),
				"Expected media type in error message, but was: " + ex.getMessage());
	}

	@Test
	void validate_literalLookingLikeBadHttpUri_throwsBadRequest() {
		String ntriples = "<http://example.org/a> <http://example.org/p> \"http://bad uri/x\" .\n";
		BadRequestException ex = assertThrows(BadRequestException.class,
				() -> service.validate(ntriples, NTRIPLES));
		assertTrue(ex.getMessage().contains("http://bad uri/x"),
				"Expected the offending value in the error message, but was: " + ex.getMessage());
	}

	@Test
	void validate_repeatedBadIriLiteral_reportsErrorOnce() {
		String bad = "\"http://bad uri/x\"";
		String ntriples = "<http://example.org/a> <http://example.org/p> " + bad + " .\n"
				+ "<http://example.org/b> <http://example.org/p> " + bad + " .\n"
				+ "<http://example.org/c> <http://example.org/p> " + bad + " .\n";

		BadRequestException ex = assertThrows(BadRequestException.class,
				() -> service.validate(ntriples, NTRIPLES));
		String message = ex.getMessage();
		long occurrences = message.split("http://bad uri/x", -1).length - 1;
		assertEquals(1, occurrences,
				"Expected exactly one occurrence of the offending value in: " + message);
	}

	@Test
	void validate_multipleDistinctBadIriLiterals_reportedInInputOrder() {
		String ntriples = """
				<http://example.org/a> <http://example.org/p> "http://bad a/x" .
				<http://example.org/b> <http://example.org/p> "http://bad b/y" .
				<http://example.org/c> <http://example.org/p> "http://bad c/z" .
				""";

		BadRequestException ex = assertThrows(BadRequestException.class,
				() -> service.validate(ntriples, NTRIPLES));
		String message = ex.getMessage();
		int idxA = message.indexOf("http://bad a/x");
		int idxB = message.indexOf("http://bad b/y");
		int idxC = message.indexOf("http://bad c/z");
		assertTrue(idxA >= 0 && idxB >= 0 && idxC >= 0,
				"Expected all three offending values in: " + message);
		assertTrue(idxA < idxB && idxB < idxC,
				"Expected offending values to appear in input order, but was: " + message);
	}

	@Test
	void validate_overlongBadIriLiteral_truncatedInErrorMessage() {
		String longTail = "/" + "x".repeat(500);
		String overlongBadIri = "http://bad uri" + longTail;
		String ntriples = "<http://example.org/a> <http://example.org/p> \"" + overlongBadIri + "\" .\n";

		BadRequestException ex = assertThrows(BadRequestException.class,
				() -> service.validate(ntriples, NTRIPLES));
		String message = ex.getMessage();
		assertTrue(message.contains("..."),
				"Expected truncation marker in: " + message);
		assertTrue(message.length() <= "Invalid IRI: ".length() + 200,
				"Expected message length capped near 200, but was: " + message.length());
		assertFalse(message.contains(longTail),
				"Did not expect full bad-IRI tail to appear in: " + message);
	}

	@Test
	void validate_manyDistinctBadIriLiterals_errorCountCappedAt50() {
		StringBuilder body = new StringBuilder();
		int distinctBadIris = 100;
		for (int i = 0; i < distinctBadIris; i++) {
			body.append("<http://example.org/s").append(i)
					.append("> <http://example.org/p> \"http://bad uri/").append(i)
					.append("\" .\n");
		}

		BadRequestException ex = assertThrows(BadRequestException.class,
				() -> service.validate(body.toString(), NTRIPLES));
		long occurrences = ex.getMessage().split("Invalid IRI: ", -1).length - 1;
		assertEquals(50, occurrences,
				"Expected error count capped at 50, but was: " + occurrences);
	}

}
