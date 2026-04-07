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

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HtmlSanitizerTest {

	@Nested
	class SanitizeHtmlBody {

		@Test
		void preservesSafeFormattingTags() {
			String html = "<p>Hello <strong>world</strong> and <em>italic</em></p>";
			String result = HtmlSanitizer.sanitizeHtmlBody(html);
			assertEquals("<p>Hello <strong>world</strong> and <em>italic</em></p>", result);
		}

		@Test
		void preservesLinks() {
			String html = "<a href=\"https://example.com\">link</a>";
			String result = HtmlSanitizer.sanitizeHtmlBody(html);
			assertEquals("<a href=\"https://example.com\" rel=\"nofollow\">link</a>", result);
		}

		@Test
		void preservesMailtoLinks() {
			String html = "<a href=\"mailto:user@example.com\">email</a>";
			String result = HtmlSanitizer.sanitizeHtmlBody(html);
			assertEquals("<a href=\"mailto:user&#64;example.com\" rel=\"nofollow\">email</a>", result);
		}

		@Test
		void preservesTableElements() {
			String html = "<table><tr><td>cell</td></tr></table>";
			String result = HtmlSanitizer.sanitizeHtmlBody(html);
			assertEquals("<table><tbody><tr><td>cell</td></tr></tbody></table>", result);
		}

		@Test
		void preservesListElements() {
			String html = "<ul><li>item 1</li><li>item 2</li></ul>";
			String result = HtmlSanitizer.sanitizeHtmlBody(html);
			assertEquals("<ul><li>item 1</li><li>item 2</li></ul>", result);
		}

		@Test
		void preservesHeadings() {
			String html = "<h1>Title</h1><h2>Subtitle</h2>";
			String result = HtmlSanitizer.sanitizeHtmlBody(html);
			assertEquals("<h1>Title</h1><h2>Subtitle</h2>", result);
		}

		@Test
		void stripsScriptTags() {
			String html = "<p>Hello</p><script>alert('xss')</script>";
			String result = HtmlSanitizer.sanitizeHtmlBody(html);
			assertEquals("<p>Hello</p>", result);
		}

		@Test
		void stripsIframeTags() {
			String html = "<p>Content</p><iframe src=\"https://evil.com\"></iframe>";
			String result = HtmlSanitizer.sanitizeHtmlBody(html);
			assertEquals("<p>Content</p>", result);
		}

		@Test
		void stripsObjectAndEmbedTags() {
			String html = "<object data=\"evil.swf\"></object><embed src=\"evil.swf\">";
			String result = HtmlSanitizer.sanitizeHtmlBody(html);
			assertEquals("", result);
		}

		@Test
		void stripsFormTags() {
			String html = "<form action=\"https://evil.com\"><input type=\"text\"></form>";
			String result = HtmlSanitizer.sanitizeHtmlBody(html);
			assertEquals("", result);
		}

		@Test
		void stripsEventHandlerAttributes() {
			String html = "<p onclick=\"alert('xss')\">Click me</p>";
			String result = HtmlSanitizer.sanitizeHtmlBody(html);
			assertEquals("<p>Click me</p>", result);
		}

		@Test
		void stripsJavascriptUrls() {
			String html = "<a href=\"javascript:alert('xss')\">click</a>";
			String result = HtmlSanitizer.sanitizeHtmlBody(html);
			assertEquals("click", result);
		}

		@Test
		void returnsNullForNullInput() {
			assertNull(HtmlSanitizer.sanitizeHtmlBody(null));
		}

		@Test
		void handlesEmptyInput() {
			assertEquals("", HtmlSanitizer.sanitizeHtmlBody(""));
		}

		@Test
		void stripsMixedCaseScriptTags() {
			String html = "<p>Hello</p><ScRiPt>alert('xss')</ScRiPt>";
			assertEquals("<p>Hello</p>", HtmlSanitizer.sanitizeHtmlBody(html));
		}

		@Test
		void stripsDataUriImages() {
			String html = "<img src=\"data:image/svg+xml,<svg onload=alert(1)>\" />";
			String result = HtmlSanitizer.sanitizeHtmlBody(html);
			assertFalse(result.contains("data:"));
		}

		@Test
		void stripsDangerousCssProperties() {
			String html = "<div style=\"position:absolute;z-index:9999;background-image:url(https://evil.com/track)\">overlay</div>";
			String result = HtmlSanitizer.sanitizeHtmlBody(html);
			assertFalse(result.contains("position"));
			assertFalse(result.contains("z-index"));
			assertFalse(result.contains("background-image"));
		}

		@Test
		void stripsEventHandlersOnAllowedElements() {
			String html = "<blockquote onclick=\"alert('xss')\">quote</blockquote>";
			String result = HtmlSanitizer.sanitizeHtmlBody(html);
			assertEquals("<blockquote>quote</blockquote>", result);
		}

		@Test
		void stripsExternalImages() {
			String html = "<img src=\"https://attacker.com/pixel.gif\" />";
			String result = HtmlSanitizer.sanitizeHtmlBody(html);
			assertFalse(result.contains("img"));
		}

		@Test
		void allowsSafeCssProperties() {
			String html = "<p style=\"color:red;font-size:14px;text-align:center\">styled</p>";
			String result = HtmlSanitizer.sanitizeHtmlBody(html);
			assertTrue(result.contains("color"));
			assertTrue(result.contains("font-size"));
		}
	}

	@Nested
	class SanitizeToPlainText {

		@Test
		void stripsAllHtmlTags() {
			String html = "<p>Hello <b>world</b></p>";
			String result = HtmlSanitizer.sanitizeToPlainText(html);
			assertEquals("Hello world", result);
		}

		@Test
		void stripsScriptTagsAndContent() {
			String html = "Subject <script>alert('xss')</script> text";
			String result = HtmlSanitizer.sanitizeToPlainText(html);
			assertEquals("Subject  text", result);
		}

		@Test
		void passesPlainTextThrough() {
			String text = "Just plain text";
			assertEquals("Just plain text", HtmlSanitizer.sanitizeToPlainText(text));
		}

		@Test
		void decodesHtmlEntitiesInPlainText() {
			assertEquals("Q&A Session", HtmlSanitizer.sanitizeToPlainText("Q&A Session"));
			assertEquals("1 < 2 & 3 > 0", HtmlSanitizer.sanitizeToPlainText("1 < 2 & 3 > 0"));
			assertEquals("He said \"hello\"", HtmlSanitizer.sanitizeToPlainText("He said \"hello\""));
		}

		@Test
		void returnsNullForNullInput() {
			assertNull(HtmlSanitizer.sanitizeToPlainText(null));
		}

		@Test
		void handlesEmptyInput() {
			assertEquals("", HtmlSanitizer.sanitizeToPlainText(""));
		}
	}
}
