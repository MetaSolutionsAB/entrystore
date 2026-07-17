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

package org.entrystore.rest.springboot.model.api;

import org.junit.jupiter.api.Test;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MediaTypeConverterTest {

	private final MediaTypeConverter converter = new MediaTypeConverter();

	@Test
	void convert_plainMediaType_parses() {
		assertEquals(MediaType.parseMediaType("application/rdf+xml"), converter.convert("application/rdf+xml"));
	}

	@Test
	void convert_spaceRestoredToPlus() {
		// Servlet decoding turns an unencoded '+' into a space; the converter must restore it.
		assertEquals(MediaType.parseMediaType("application/rdf+xml"), converter.convert("application/rdf xml"));
	}

	@Test
	void convert_trimsWhitespace() {
		assertEquals(MediaType.parseMediaType("text/turtle"), converter.convert(" text/turtle "));
	}

	@Test
	void convert_uppercase_normalizedToLowercase() {
		assertEquals("text/turtle", converter.convert("Text/Turtle").toString());
	}

	@Test
	void convert_parameterizedMediaTypeWithSpace_notCorrupted() {
		// Parse-first: a properly %20-encoded space before a parameter must not be rewritten to '+'.
		assertEquals(MediaType.parseMediaType("text/turtle;charset=UTF-8"),
				converter.convert("text/turtle; charset=UTF-8"));
	}

	@Test
	void convert_blank_returnsNullSoParamIsTreatedAsAbsent() {
		// Matches the convention of Spring's built-in String converters: an empty format= param binds
		// to null and the endpoint falls back to Accept-header negotiation instead of failing.
		assertNull(converter.convert(""));
		assertNull(converter.convert("   "));
	}

	@Test
	void convert_unparseable_throwsInvalidMediaType() {
		// Deliberately not translated to an application exception: binding swallows converter exceptions
		// (TypeConverterDelegate falls back to spring-web's MediaTypeEditor), so the client-facing 400
		// message is crafted in AppExceptionHandler from the MethodArgumentTypeMismatchException.
		assertThrows(InvalidMediaTypeException.class, () -> converter.convert("not a media type"));
	}
}
