/*
 * Copyright (c) 2007-2024 MetaSolutions AB
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
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.base.CoreDatatype;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.rio.RDFFormat;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class MetadataUtilTest {

	private final ValueFactory valueFactory = SimpleValueFactory.getInstance();
	private final Literal booleanLiteral = valueFactory.createLiteral("false", CoreDatatype.XSD.BOOLEAN);
	private final Literal byteLiteral = valueFactory.createLiteral("1", CoreDatatype.XSD.BYTE);
	private final Literal intLiteral = valueFactory.createLiteral("123", CoreDatatype.XSD.INT);
	private final Literal integerLiteral = valueFactory.createLiteral("123", CoreDatatype.XSD.INTEGER);
	private final Literal longLiteral = valueFactory.createLiteral("1", CoreDatatype.XSD.LONG);
	private final Literal negativeIntegerLiteral = valueFactory.createLiteral("-1", CoreDatatype.XSD.NEGATIVE_INTEGER);
	private final Literal nonNegativeIntegerLiteral = valueFactory.createLiteral("0", CoreDatatype.XSD.NON_NEGATIVE_INTEGER);
	private final Literal nonPositiveIntegerLiteral = valueFactory.createLiteral("0", CoreDatatype.XSD.NON_POSITIVE_INTEGER);
	private final Literal positiveIntegerLiteral = valueFactory.createLiteral("1", CoreDatatype.XSD.POSITIVE_INTEGER);
	private final Literal shortIntegerLiteral = valueFactory.createLiteral("1", CoreDatatype.XSD.SHORT);
	private final Literal unsignedLongLiteral = valueFactory.createLiteral("1", CoreDatatype.XSD.UNSIGNED_LONG);
	private final Literal unsignedIntLiteral = valueFactory.createLiteral("1", CoreDatatype.XSD.UNSIGNED_INT);
	private final Literal unsignedShortLiteral = valueFactory.createLiteral("1", CoreDatatype.XSD.UNSIGNED_SHORT);
	private final Literal unsignedByteLiteral = valueFactory.createLiteral("1", CoreDatatype.XSD.UNSIGNED_BYTE);
	private final Literal gYearLiteral = valueFactory.createLiteral("1999", CoreDatatype.XSD.GYEAR);
	private final Literal gYearMonthLiteral = valueFactory.createLiteral("1999-11", CoreDatatype.XSD.GYEARMONTH);
	private final Literal gMonthLiteral = valueFactory.createLiteral("--11Z", CoreDatatype.XSD.GMONTH);
	private final Literal gDayLiteral = valueFactory.createLiteral("---01Z", CoreDatatype.XSD.GDAY);
	private final Literal dateLiteral = valueFactory.createLiteral("2002-09-24", CoreDatatype.XSD.DATE);
	private final Literal dateTimeLiteral = valueFactory.createLiteral("2012-03-07T08:00:01Z", CoreDatatype.XSD.DATETIME);
	private final Literal dateTimeStampLiteral = valueFactory.createLiteral("2012-03-07T08:00:01Z", CoreDatatype.XSD.DATETIMESTAMP);
	private final Literal stringLiteral = valueFactory.createLiteral("test", CoreDatatype.XSD.STRING);
	private final Literal langStringLiteral = valueFactory.createLiteral("test@en", CoreDatatype.RDF.LANGSTRING.toString());
	private final Literal nullLiteral = valueFactory.createLiteral("this is rdf:langString type by default", CoreDatatype.XSD.NONE.toString());

	@Test
	public void isIntegerLiteral_null() {
		try {
			MetadataUtil.isIntegerLiteral(null);
		} catch (Exception e) {
			assertEquals(e.getMessage(), "Literal must not be null.");
		}
	}

	@Test
	public void isDateLiteral_null() {
		try {
			MetadataUtil.isDateLiteral(null);
		} catch (Exception e) {
			assertEquals(e.getMessage(), "Literal must not be null.");
		}
	}

	@Test
	public void isStringLiteral_null() {
		try {
			MetadataUtil.isStringLiteral(null);
		} catch (Exception e) {
			assertEquals(e.getMessage(), "Literal must not be null.");
		}
	}

	@Test
	public void isAnyLiteral_nullDatatype() {
		assertFalse(MetadataUtil.isIntegerLiteral(nullLiteral));
		assertFalse(MetadataUtil.isDateLiteral(nullLiteral));
		assertTrue(MetadataUtil.isStringLiteral(nullLiteral));
	}

	@Test
	public void isIntegerLiteral() {

		assertTrue(MetadataUtil.isIntegerLiteral(byteLiteral));
		assertTrue(MetadataUtil.isIntegerLiteral(intLiteral));
		assertTrue(MetadataUtil.isIntegerLiteral(integerLiteral));
		assertTrue(MetadataUtil.isIntegerLiteral(longLiteral));
		assertTrue(MetadataUtil.isIntegerLiteral(negativeIntegerLiteral));
		assertTrue(MetadataUtil.isIntegerLiteral(nonNegativeIntegerLiteral));
		assertTrue(MetadataUtil.isIntegerLiteral(nonPositiveIntegerLiteral));
		assertTrue(MetadataUtil.isIntegerLiteral(positiveIntegerLiteral));
		assertTrue(MetadataUtil.isIntegerLiteral(shortIntegerLiteral));
		assertTrue(MetadataUtil.isIntegerLiteral(unsignedByteLiteral));
		assertTrue(MetadataUtil.isIntegerLiteral(unsignedIntLiteral));
		assertTrue(MetadataUtil.isIntegerLiteral(unsignedLongLiteral));
		assertTrue(MetadataUtil.isIntegerLiteral(unsignedShortLiteral));
		assertTrue(MetadataUtil.isIntegerLiteral(gYearLiteral));
		assertFalse(MetadataUtil.isIntegerLiteral(gDayLiteral));
		assertFalse(MetadataUtil.isIntegerLiteral(gYearMonthLiteral));
		assertFalse(MetadataUtil.isIntegerLiteral(gMonthLiteral));
		assertFalse(MetadataUtil.isIntegerLiteral(dateLiteral));
		assertFalse(MetadataUtil.isIntegerLiteral(dateTimeLiteral));
		assertFalse(MetadataUtil.isIntegerLiteral(dateTimeStampLiteral));
		assertFalse(MetadataUtil.isIntegerLiteral(booleanLiteral));
		assertFalse(MetadataUtil.isIntegerLiteral(stringLiteral));
		assertFalse(MetadataUtil.isIntegerLiteral(langStringLiteral));
	}

	@Test
	public void isDateLiteral() {

		assertFalse(MetadataUtil.isDateLiteral(byteLiteral));
		assertFalse(MetadataUtil.isDateLiteral(intLiteral));
		assertFalse(MetadataUtil.isDateLiteral(integerLiteral));
		assertFalse(MetadataUtil.isDateLiteral(longLiteral));
		assertFalse(MetadataUtil.isDateLiteral(negativeIntegerLiteral));
		assertFalse(MetadataUtil.isDateLiteral(nonNegativeIntegerLiteral));
		assertFalse(MetadataUtil.isDateLiteral(nonPositiveIntegerLiteral));
		assertFalse(MetadataUtil.isDateLiteral(positiveIntegerLiteral));
		assertFalse(MetadataUtil.isDateLiteral(shortIntegerLiteral));
		assertFalse(MetadataUtil.isDateLiteral(unsignedByteLiteral));
		assertFalse(MetadataUtil.isDateLiteral(unsignedIntLiteral));
		assertFalse(MetadataUtil.isDateLiteral(unsignedLongLiteral));
		assertFalse(MetadataUtil.isDateLiteral(unsignedShortLiteral));
		assertTrue(MetadataUtil.isDateLiteral(gYearLiteral));

		assertTrue(MetadataUtil.isDateLiteral(gDayLiteral));
		assertTrue(MetadataUtil.isDateLiteral(gYearMonthLiteral));
		assertTrue(MetadataUtil.isDateLiteral(gMonthLiteral));
		assertTrue(MetadataUtil.isDateLiteral(dateLiteral));
		assertTrue(MetadataUtil.isDateLiteral(dateTimeLiteral));
		assertTrue(MetadataUtil.isDateLiteral(dateTimeStampLiteral));
		assertFalse(MetadataUtil.isDateLiteral(booleanLiteral));
		assertFalse(MetadataUtil.isDateLiteral(stringLiteral));
		assertFalse(MetadataUtil.isDateLiteral(langStringLiteral));
	}

	@Test
	public void isStringLiteral() {

		assertFalse(MetadataUtil.isStringLiteral(byteLiteral));
		assertFalse(MetadataUtil.isStringLiteral(intLiteral));
		assertFalse(MetadataUtil.isStringLiteral(integerLiteral));
		assertFalse(MetadataUtil.isStringLiteral(longLiteral));
		assertFalse(MetadataUtil.isStringLiteral(negativeIntegerLiteral));
		assertFalse(MetadataUtil.isStringLiteral(nonNegativeIntegerLiteral));
		assertFalse(MetadataUtil.isStringLiteral(nonPositiveIntegerLiteral));
		assertFalse(MetadataUtil.isStringLiteral(positiveIntegerLiteral));
		assertFalse(MetadataUtil.isStringLiteral(shortIntegerLiteral));
		assertFalse(MetadataUtil.isStringLiteral(unsignedByteLiteral));
		assertFalse(MetadataUtil.isStringLiteral(unsignedIntLiteral));
		assertFalse(MetadataUtil.isStringLiteral(unsignedLongLiteral));
		assertFalse(MetadataUtil.isStringLiteral(unsignedShortLiteral));
		assertFalse(MetadataUtil.isStringLiteral(gYearLiteral));

		assertFalse(MetadataUtil.isStringLiteral(gDayLiteral));
		assertFalse(MetadataUtil.isStringLiteral(gYearMonthLiteral));
		assertFalse(MetadataUtil.isStringLiteral(gMonthLiteral));
		assertFalse(MetadataUtil.isStringLiteral(dateLiteral));
		assertFalse(MetadataUtil.isStringLiteral(dateTimeLiteral));
		assertFalse(MetadataUtil.isStringLiteral(dateTimeStampLiteral));
		assertFalse(MetadataUtil.isStringLiteral(booleanLiteral));
		assertTrue(MetadataUtil.isStringLiteral(stringLiteral));
		assertTrue(MetadataUtil.isStringLiteral(langStringLiteral));
	}

	@Test
	public void getRDFFormat_all() {
		String formatString = "RDF/XML";
		RDFFormat format = MetadataUtil.getRDFFormat(formatString);
		assertNotNull(format);
		assertEquals(formatString, format.getName());

		formatString = "N-Triples";
		format = MetadataUtil.getRDFFormat(formatString);
		assertNotNull(format);
		assertEquals(formatString, format.getName());

		formatString = "Turtle";
		format = MetadataUtil.getRDFFormat(formatString);
		assertNotNull(format);
		assertEquals(formatString, format.getName());

		formatString = "N3";
		format = MetadataUtil.getRDFFormat(formatString);
		assertNotNull(format);
		assertEquals(formatString, format.getName());

		formatString = "TriX";
		format = MetadataUtil.getRDFFormat(formatString);
		assertNotNull(format);
		assertEquals(formatString, format.getName());

		formatString = "TriG";
		format = MetadataUtil.getRDFFormat(formatString);
		assertNotNull(format);
		assertEquals(formatString, format.getName());

		formatString = "BinaryRDF";
		format = MetadataUtil.getRDFFormat(formatString);
		assertNotNull(format);
		assertEquals(formatString, format.getName());

		formatString = "N-Quads";
		format = MetadataUtil.getRDFFormat(formatString);
		assertNotNull(format);
		assertEquals(formatString, format.getName());

		formatString = "JSON-LD";
		format = MetadataUtil.getRDFFormat(formatString);
		assertNotNull(format);
		assertEquals(formatString, format.getName());

		formatString = "RDF/JSON";
		format = MetadataUtil.getRDFFormat(formatString);
		assertNotNull(format);
		assertEquals(formatString, format.getName());

		formatString = "RDFa";
		format = MetadataUtil.getRDFFormat(formatString);
		assertNotNull(format);
		assertEquals(formatString, format.getName());

		// the decision was made to not currently support the following formats by this method
		formatString = "Turtle-star";
		format = MetadataUtil.getRDFFormat(formatString);
		assertNull(format);

		formatString = "TriG-star";
		format = MetadataUtil.getRDFFormat(formatString);
		assertNull(format);

		formatString = "NDJSON-LD";
		format = MetadataUtil.getRDFFormat(formatString);
		assertNull(format);

		formatString = "HDT";
		format = MetadataUtil.getRDFFormat(formatString);
		assertNull(format);

		formatString = "anything";
		format = MetadataUtil.getRDFFormat(formatString);
		assertNull(format);

	}
}
