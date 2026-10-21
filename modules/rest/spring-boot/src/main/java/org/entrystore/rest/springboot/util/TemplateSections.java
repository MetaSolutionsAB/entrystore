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

import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Splits a mail template into its sections, each delimited by exactly {@code <!--BEGIN name-->} and
 * {@code <!--END name-->}.
 *
 * <p>Parsing is strict so that a broken template fails loudly at load instead of mailing the wrong text: an
 * unpaired, nested, duplicated or unknown marker is rejected, and so is a comment that names a section but is
 * not spelled as a marker, such as {@code <!-- begin new-account -->}. Other comments are left alone.
 */
final class TemplateSections {

	private static final Pattern COMMENT = Pattern.compile("<!--(.*?)-->", Pattern.DOTALL);
	private static final Pattern MARKER = Pattern.compile("(BEGIN|END) ([A-Za-z0-9_-]+)");
	private static final Pattern SECTION = Pattern.compile("<!--BEGIN ([A-Za-z0-9_-]+)-->(.*?)<!--END \\1-->",
			Pattern.DOTALL);

	private TemplateSections() {
	}

	/**
	 * The mail of each section in {@code body}, keyed by section name: the body with that section's content kept
	 * and every other section, markers included, removed. Empty for a body without markers.
	 *
	 * @throws IllegalArgumentException naming the offending marker when a marker is unpaired, nested,
	 *         duplicated, names a section outside {@code allowedNames}, or names an allowed section without being
	 *         spelled as a marker
	 */
	static Map<String, String> split(String body, Set<String> allowedNames) {
		Map<String, String> mails = new HashMap<>();
		// Safe as a regex once validate has rejected nested, unpaired and duplicated markers.
		for (String name : validate(body, allowedNames)) {
			mails.put(name, SECTION.matcher(body).replaceAll(section ->
					section.group(1).equals(name) ? Matcher.quoteReplacement(section.group(2)) : ""));
		}
		return Map.copyOf(mails);
	}

	/** The names of the sections in {@code body}. */
	private static Set<String> validate(String body, Set<String> allowedNames) {
		Set<String> names = new HashSet<>();
		String openName = null;
		Matcher comment = COMMENT.matcher(body);
		while (comment.find()) {
			Matcher marker = MARKER.matcher(comment.group(1));
			if (!marker.matches()) {
				rejectMisspelledMarker(comment.group(), allowedNames);
				continue;
			}
			String name = marker.group(2);
			if (!allowedNames.contains(name)) {
				throw new IllegalArgumentException("Unknown section in " + comment.group()
						+ "; the template allows " + allowedNames);
			}
			if (marker.group(1).equals("BEGIN")) {
				if (openName != null) {
					throw new IllegalArgumentException(comment.group() + " is nested in section " + openName);
				}
				if (!names.add(name)) {
					throw new IllegalArgumentException("Section " + name + " appears more than once");
				}
				openName = name;
			} else {
				if (!name.equals(openName)) {
					throw new IllegalArgumentException(comment.group() + " has no matching <!--BEGIN " + name + "-->");
				}
				openName = null;
			}
		}
		if (openName != null) {
			throw new IllegalArgumentException("<!--BEGIN " + openName + "--> has no matching <!--END " + openName + "-->");
		}
		return names;
	}

	private static void rejectMisspelledMarker(String comment, Set<String> allowedNames) {
		String lowerCase = comment.toLowerCase(Locale.ROOT);
		for (String name : allowedNames) {
			if (lowerCase.contains(name.toLowerCase(Locale.ROOT))) {
				throw new IllegalArgumentException(comment + " names section " + name
						+ " but is not spelled <!--BEGIN " + name + "--> or <!--END " + name + "-->");
			}
		}
	}
}
