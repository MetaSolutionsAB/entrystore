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

public class StringUtils {

	public static String replace(String data, String from, String to) {
		StringBuilder builder = new StringBuilder(data.length());
		int pos;
		int i = 0;
		while ((pos = data.indexOf(from, i)) != -1) {
			builder.append(data, i, pos).append(to);
			i = pos + from.length();
		}
		builder.append(data.substring(i));
		return builder.toString();
	}

	/**
	 * Converts a string with or without units (kilo, mega, etc) to bytes.
	 * Supported unit abbreviations: k, m, g, t. Lower- and uppercase are supported.
	 *
	 * @param input A String with or without abbreviated unit. E.g., an input value of
	 *                 1024 returns the same result as an input value of 1k.
	 * @return The converted value in bytes as long variable.
	 */
	public static long convertUnitStringToByteSize(String input) {
		if (input == null || input.isEmpty()) {
			throw new IllegalArgumentException("Parameter must not be null or empty");
		}
		char unit = input.charAt(input.length() - 1);
		long factor = 1L;
		if (unit == 'k' || unit == 'K') { // Kilo
			factor = 1024L;
		} else if (unit == 'm' || unit == 'M') { // Mega
			factor = 1024L *1024;
		} else if (unit == 'g' || unit == 'G') { // Giga
			factor = 1024L *1024*1024;
		} else if (unit == 't' || unit == 'T') { // Tera
			factor = 1024L *1024*1024*1024;
		}

		if (factor > 1) {
			return Long.parseLong(input.substring(0, input.length() - 1)) * factor;
		} else {
			return Long.parseLong(input);
		}
	}

}
