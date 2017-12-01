/*
 * Copyright (c) 2007-2017 MetaSolutions AB
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

import org.openrdf.model.Literal;

import java.util.HashSet;
import java.util.Set;

/**
 * @author Hannes Ebner
 */
public class MetadataUtil {

	public static Set<String> integerDataTypes;

	static {
		integerDataTypes = new HashSet();
		integerDataTypes.add("http://www.w3.org/2001/XMLSchema#byte");
		integerDataTypes.add("http://www.w3.org/2001/XMLSchema#int");
		integerDataTypes.add("http://www.w3.org/2001/XMLSchema#integer");
		integerDataTypes.add("http://www.w3.org/2001/XMLSchema#long");
		integerDataTypes.add("http://www.w3.org/2001/XMLSchema#negativeInteger");
		integerDataTypes.add("http://www.w3.org/2001/XMLSchema#nonNegativeInteger");
		integerDataTypes.add("http://www.w3.org/2001/XMLSchema#nonPositiveInteger");
		integerDataTypes.add("http://www.w3.org/2001/XMLSchema#positiveInteger");
		integerDataTypes.add("http://www.w3.org/2001/XMLSchema#short");
		integerDataTypes.add("http://www.w3.org/2001/XMLSchema#unsignedLong");
		integerDataTypes.add("http://www.w3.org/2001/XMLSchema#unsignedInt");
		integerDataTypes.add("http://www.w3.org/2001/XMLSchema#unsignedShort");
		integerDataTypes.add("http://www.w3.org/2001/XMLSchema#unsignedByte");
	}

	/**
	 * Filters all invalid XML characters out of the string.
	 * 
	 * @param s
	 *            string to be filtered.
	 * @return A valid XML string.
	 */
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

	public static boolean isIntegerLiteral(Literal l) {
		if (l == null) {
			throw new IllegalArgumentException("Literal must not be null");
		}

		if (l.getDatatype() == null) {
			return false;
		}

		return integerDataTypes.contains(l.getDatatype().stringValue());
	}

	public static boolean isDateLiteral(Literal l) {
		if (l == null) {
			throw new IllegalArgumentException("Literal must not be null");
		}

		if (l.getDatatype() == null) {
			return false;
		}

		return "http://www.w3.org/2001/XMLSchema#date".equals(l.getDatatype().stringValue());
	}

}