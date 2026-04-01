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

import org.owasp.html.HtmlPolicyBuilder;
import org.owasp.html.PolicyFactory;
import org.owasp.html.Sanitizers;

/**
 * Utility class for sanitizing HTML content in user-supplied messages.
 *
 * @author Patrik Kompuš
 */
public class HtmlSanitizer {

	private static final PolicyFactory EMAIL_HTML_POLICY = Sanitizers.FORMATTING
			.and(Sanitizers.BLOCKS)
			.and(Sanitizers.LINKS)
			.and(Sanitizers.TABLES)
			.and(Sanitizers.IMAGES)
			.and(Sanitizers.STYLES)
			.and(new HtmlPolicyBuilder()
					.allowElements("hr", "sub", "sup", "code", "pre", "blockquote")
					.toFactory());

	private static final PolicyFactory PLAIN_TEXT_POLICY = new HtmlPolicyBuilder().toFactory();

	private HtmlSanitizer() {
	}

	/**
	 * Sanitizes HTML content for use in email bodies. Allows safe formatting tags
	 * while stripping dangerous elements (script, iframe, event handlers, javascript: URLs, etc.).
	 */
	public static String sanitizeHtmlBody(String html) {
		if (html == null) {
			return null;
		}
		return EMAIL_HTML_POLICY.sanitize(html);
	}

	/**
	 * Strips all HTML tags from the input, returning plain text.
	 * Suitable for email subjects and other contexts where HTML is not expected.
	 */
	public static String sanitizeToPlainText(String html) {
		if (html == null) {
			return null;
		}
		return PLAIN_TEXT_POLICY.sanitize(html);
	}
}
