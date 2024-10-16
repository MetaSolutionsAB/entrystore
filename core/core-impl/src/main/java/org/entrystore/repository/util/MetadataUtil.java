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
import org.eclipse.rdf4j.model.base.CoreDatatype;

import java.util.HashSet;
import java.util.Set;

/**
 * @author Hannes Ebner
 */
public class MetadataUtil {

	public static final String INTEGER_TYPE = "integer";
	public static final String DATE_TYPE = "date";
	public static final String STRING_TYPE = "string";

	public static Set<CoreDatatype> integerDataTypes;

	public static Set<CoreDatatype> dateDataTypes;

	public static Set<CoreDatatype> stringDataTypes;

	static {
		integerDataTypes = new HashSet<>();
		integerDataTypes.add(CoreDatatype.XSD.BYTE);
		integerDataTypes.add(CoreDatatype.XSD.INT);
		integerDataTypes.add(CoreDatatype.XSD.INTEGER);
		integerDataTypes.add(CoreDatatype.XSD.LONG);
		integerDataTypes.add(CoreDatatype.XSD.NEGATIVE_INTEGER);
		integerDataTypes.add(CoreDatatype.XSD.NON_NEGATIVE_INTEGER);
		integerDataTypes.add(CoreDatatype.XSD.NON_POSITIVE_INTEGER);
		integerDataTypes.add(CoreDatatype.XSD.POSITIVE_INTEGER);
		integerDataTypes.add(CoreDatatype.XSD.SHORT);
		integerDataTypes.add(CoreDatatype.XSD.UNSIGNED_LONG);
		integerDataTypes.add(CoreDatatype.XSD.UNSIGNED_INT);
		integerDataTypes.add(CoreDatatype.XSD.UNSIGNED_SHORT);
		integerDataTypes.add(CoreDatatype.XSD.UNSIGNED_BYTE);
		integerDataTypes.add(CoreDatatype.XSD.GYEAR);

		dateDataTypes = new HashSet<>();
		dateDataTypes.add(CoreDatatype.XSD.DATE);
		dateDataTypes.add(CoreDatatype.XSD.DATETIME);
		dateDataTypes.add(CoreDatatype.XSD.GYEAR);
		dateDataTypes.add(CoreDatatype.XSD.GYEARMONTH);
		dateDataTypes.add(CoreDatatype.XSD.GDAY);
		dateDataTypes.add(CoreDatatype.XSD.GMONTH);
		dateDataTypes.add(CoreDatatype.XSD.DATETIMESTAMP);

		stringDataTypes = new HashSet<>();
		stringDataTypes.add(CoreDatatype.RDF.LANGSTRING);
		stringDataTypes.add(CoreDatatype.XSD.STRING);
	}

	/**
	 * Filters all invalid XML characters out of the string.
	 *
	 * @param s
	 *            string to be filtered.
	 * @return A valid XML string.
	 */
	/* NEVER USED
	public static String removeInvalidXMLCharacters(String s) {
		StringBuilder out = new StringBuilder(); // Used to hold the output.
		// used to reference the current char
		int codePoint;

		// unicode character, represented by two code units
		// String ss = "\ud801\udc00";
		// System.out.println(ss.codePointCount(0, ss.length())); // See: 1

		int i = 0;
		while (i < s.length()) {
			// unicode code of the character
			codePoint = s.codePointAt(i);
			if ((codePoint == 0x9) || (codePoint == 0xA) || (codePoint == 0xD)
					|| ((codePoint >= 0x20) && (codePoint <= 0xD7FF))
					|| ((codePoint >= 0xE000) && (codePoint <= 0xFFFD))
					|| ((codePoint >= 0x10000) && (codePoint <= 0x10FFFF))) {
				out.append(Character.toChars(codePoint));
			}
			// increment with number of code units (java chars) needed to
			// represent a unicode char
			i += Character.charCount(codePoint);
		}

		return out.toString();
	}
	*/

	public static boolean isIntegerLiteral(Literal l) {
		return isTypedLiteral(l, INTEGER_TYPE);
	}

	public static boolean isDateLiteral(Literal l) {
		return isTypedLiteral(l, DATE_TYPE);
	}

	public static boolean isStringLiteral(Literal l) {
		return isTypedLiteral(l, STRING_TYPE);
	}

	private static boolean isTypedLiteral(Literal l, String type) {

		if (l == null) {
			throw new IllegalArgumentException("Literal must not be null.");
		}

		CoreDatatype datatype = CoreDatatype.from(l.getDatatype());

		if (datatype == CoreDatatype.XSD.NONE) {
			return false;
		}

		return switch (type) {
			case INTEGER_TYPE -> integerDataTypes.contains(datatype);
			case DATE_TYPE -> dateDataTypes.contains(datatype);
			default -> stringDataTypes.contains(datatype);
		};
	}

}
