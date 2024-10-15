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
	private final Literal nullLiteral = valueFactory.createLiteral("test null", CoreDatatype.XSD.NONE.toString());

	@Test
	public void isTypedLiteral_null() {
		try {
			MetadataUtil.isTypedLiteral(null, "integer");
		} catch (Exception e) {
			assertEquals(e.getMessage(), "Literal must not be null.");
		}
	}

	@Test
	public void isTypedLiteral_nullDatatype() {
		assertFalse(MetadataUtil.isTypedLiteral(nullLiteral, "integer"));
	}

	@Test
	public void isTypedLiteral_integer() {

		String type = "integer";

		assertTrue(MetadataUtil.isTypedLiteral(byteLiteral, type));
		assertTrue(MetadataUtil.isTypedLiteral(intLiteral, type));
		assertTrue(MetadataUtil.isTypedLiteral(integerLiteral, type));
		assertTrue(MetadataUtil.isTypedLiteral(longLiteral, type));
		assertTrue(MetadataUtil.isTypedLiteral(negativeIntegerLiteral, type));
		assertTrue(MetadataUtil.isTypedLiteral(nonNegativeIntegerLiteral, type));
		assertTrue(MetadataUtil.isTypedLiteral(nonPositiveIntegerLiteral, type));
		assertTrue(MetadataUtil.isTypedLiteral(positiveIntegerLiteral, type));
		assertTrue(MetadataUtil.isTypedLiteral(shortIntegerLiteral, type));
		assertTrue(MetadataUtil.isTypedLiteral(unsignedByteLiteral, type));
		assertTrue(MetadataUtil.isTypedLiteral(unsignedIntLiteral, type));
		assertTrue(MetadataUtil.isTypedLiteral(unsignedLongLiteral, type));
		assertTrue(MetadataUtil.isTypedLiteral(unsignedShortLiteral, type));
		assertTrue(MetadataUtil.isTypedLiteral(gYearLiteral, type));
		assertFalse(MetadataUtil.isTypedLiteral(gDayLiteral, type));
		assertFalse(MetadataUtil.isTypedLiteral(gYearMonthLiteral, type));
		assertFalse(MetadataUtil.isTypedLiteral(gMonthLiteral, type));
		assertFalse(MetadataUtil.isTypedLiteral(dateLiteral, type));
		assertFalse(MetadataUtil.isTypedLiteral(dateTimeLiteral, type));
		assertFalse(MetadataUtil.isTypedLiteral(dateTimeStampLiteral, type));
		assertFalse(MetadataUtil.isTypedLiteral(booleanLiteral, type));
		assertFalse(MetadataUtil.isTypedLiteral(stringLiteral, type));
		assertFalse(MetadataUtil.isTypedLiteral(langStringLiteral, type));
	}

	@Test
	public void isTypedLiteral_date() {

		String type = "date";

		assertFalse(MetadataUtil.isTypedLiteral(byteLiteral, type));
		assertFalse(MetadataUtil.isTypedLiteral(intLiteral, type));
		assertFalse(MetadataUtil.isTypedLiteral(integerLiteral, type));
		assertFalse(MetadataUtil.isTypedLiteral(longLiteral, type));
		assertFalse(MetadataUtil.isTypedLiteral(negativeIntegerLiteral, type));
		assertFalse(MetadataUtil.isTypedLiteral(nonNegativeIntegerLiteral, type));
		assertFalse(MetadataUtil.isTypedLiteral(nonPositiveIntegerLiteral, type));
		assertFalse(MetadataUtil.isTypedLiteral(positiveIntegerLiteral, type));
		assertFalse(MetadataUtil.isTypedLiteral(shortIntegerLiteral, type));
		assertFalse(MetadataUtil.isTypedLiteral(unsignedByteLiteral, type));
		assertFalse(MetadataUtil.isTypedLiteral(unsignedIntLiteral, type));
		assertFalse(MetadataUtil.isTypedLiteral(unsignedLongLiteral, type));
		assertFalse(MetadataUtil.isTypedLiteral(unsignedShortLiteral, type));
		assertTrue(MetadataUtil.isTypedLiteral(gYearLiteral, type));

		assertTrue(MetadataUtil.isTypedLiteral(gDayLiteral, type));
		assertTrue(MetadataUtil.isTypedLiteral(gYearMonthLiteral, type));
		assertTrue(MetadataUtil.isTypedLiteral(gMonthLiteral, type));
		assertTrue(MetadataUtil.isTypedLiteral(dateLiteral, type));
		assertTrue(MetadataUtil.isTypedLiteral(dateTimeLiteral, type));
		assertTrue(MetadataUtil.isTypedLiteral(dateTimeStampLiteral, type));
		assertFalse(MetadataUtil.isTypedLiteral(booleanLiteral, type));
		assertFalse(MetadataUtil.isTypedLiteral(stringLiteral, type));
		assertFalse(MetadataUtil.isTypedLiteral(langStringLiteral, type));
	}

	@Test
	public void isTypedLiteral_literal() {

		String type = "literal";

		assertFalse(MetadataUtil.isTypedLiteral(byteLiteral, type));
		assertFalse(MetadataUtil.isTypedLiteral(intLiteral, type));
		assertFalse(MetadataUtil.isTypedLiteral(integerLiteral, type));
		assertFalse(MetadataUtil.isTypedLiteral(longLiteral, type));
		assertFalse(MetadataUtil.isTypedLiteral(negativeIntegerLiteral, type));
		assertFalse(MetadataUtil.isTypedLiteral(nonNegativeIntegerLiteral, type));
		assertFalse(MetadataUtil.isTypedLiteral(nonPositiveIntegerLiteral, type));
		assertFalse(MetadataUtil.isTypedLiteral(positiveIntegerLiteral, type));
		assertFalse(MetadataUtil.isTypedLiteral(shortIntegerLiteral, type));
		assertFalse(MetadataUtil.isTypedLiteral(unsignedByteLiteral, type));
		assertFalse(MetadataUtil.isTypedLiteral(unsignedIntLiteral, type));
		assertFalse(MetadataUtil.isTypedLiteral(unsignedLongLiteral, type));
		assertFalse(MetadataUtil.isTypedLiteral(unsignedShortLiteral, type));
		assertFalse(MetadataUtil.isTypedLiteral(gYearLiteral, type));

		assertFalse(MetadataUtil.isTypedLiteral(gDayLiteral, type));
		assertFalse(MetadataUtil.isTypedLiteral(gYearMonthLiteral, type));
		assertFalse(MetadataUtil.isTypedLiteral(gMonthLiteral, type));
		assertFalse(MetadataUtil.isTypedLiteral(dateLiteral, type));
		assertFalse(MetadataUtil.isTypedLiteral(dateTimeLiteral, type));
		assertFalse(MetadataUtil.isTypedLiteral(dateTimeStampLiteral, type));
		assertFalse(MetadataUtil.isTypedLiteral(booleanLiteral, type));
		assertTrue(MetadataUtil.isTypedLiteral(stringLiteral, type));
		assertTrue(MetadataUtil.isTypedLiteral(langStringLiteral, type));
	}
}
